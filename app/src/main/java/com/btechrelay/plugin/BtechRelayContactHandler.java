package com.btechrelay.plugin;

import android.content.Context;
import android.util.Log;

import com.atakmap.android.chat.ChatManagerMapComponent;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.ContactConnectorManager;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.contact.PluginConnector;
import com.atakmap.coremap.filesystem.FileSystemUtils;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BtechRelayContactHandler extends
        ContactConnectorManager.ContactConnectorHandler {

    private final Context pluginContext;

    /**
     * Minimal unread counter store for plugin contacts. ATAK queries this via
     * {@link #getFeature} with {@code ConnectorFeature.NotificationCount} to drive UI badges.
     */
    private static final int MAX_UNREAD_KEYS_PER_UID = 128;
    private static final Map<String, Set<String>> unreadKeysByUid = new ConcurrentHashMap<>();

    /**
     * Dedupe inbound message ids per contact. Radio links can legitimately retransmit;
     * ATAK may dedupe display while our simple counter would double-increment.
     */
    private static final int MAX_SEEN_MIDS_PER_UID = 128;
    private static final Map<String, Set<Integer>> seenInboundMidsByUid =
            new ConcurrentHashMap<>();

    /** Last-message fingerprint per UID to suppress RF retransmits with new mids. */
    private static final long DUPLICATE_TEXT_WINDOW_MS = 15_000L;
    private static final Map<String, String> lastMsgFingerprintByUid = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastMsgFingerprintMsByUid = new ConcurrentHashMap<>();

    public BtechRelayContactHandler(Context pluginContext) {
        this.pluginContext = pluginContext;
    }

    public static void incrementUnreadOnce(String contactUid, int radioPacketMessageId,
                                           String messageText) {
        if (contactUid == null) return;
        String uid = contactUid.trim();
        if (uid.isEmpty()) return;

        // Use stable keys so we can count unique unread items instead of incrementing blindly.
        String unreadKey = null;

        if (messageText != null && !messageText.isEmpty()) {
            // Some radios re-send the same payload but regenerate/alter the mid.
            // ATAK may de-duplicate display; keep our badge in sync by suppressing
            // duplicates within a small time window.
            String fp = Integer.toHexString(messageText.hashCode());
            long now = System.currentTimeMillis();
            String last = lastMsgFingerprintByUid.get(uid);
            Long lastMs = lastMsgFingerprintMsByUid.get(uid);
            if (fp.equals(last) && lastMs != null && (now - lastMs) < DUPLICATE_TEXT_WINDOW_MS) {
                return;
            }
            lastMsgFingerprintByUid.put(uid, fp);
            lastMsgFingerprintMsByUid.put(uid, now);
            unreadKey = "fp:" + fp;
        }

        if (radioPacketMessageId != 0) {
            Set<Integer> seen = seenInboundMidsByUid.computeIfAbsent(uid,
                    k -> ConcurrentHashMap.newKeySet());
            if (!seen.add(radioPacketMessageId)) {
                return; // already counted this wire message id for this contact
            }
            // Keep memory bounded in long-running sessions.
            if (seen.size() > MAX_SEEN_MIDS_PER_UID) {
                seen.clear();
                seen.add(radioPacketMessageId);
            }
            unreadKey = "mid:" + radioPacketMessageId;
        }

        if (unreadKey == null) {
            // Fallback (should be rare): treat as one unread item.
            unreadKey = "t:" + System.currentTimeMillis();
        }

        Set<String> keys = unreadKeysByUid.computeIfAbsent(uid,
                k -> ConcurrentHashMap.newKeySet());
        keys.add(unreadKey);
        if (keys.size() > MAX_UNREAD_KEYS_PER_UID) {
            keys.clear();
            keys.add(unreadKey);
        }
        try {
            Contacts.getInstance().updateTotalUnreadCount();
        } catch (Exception ignored) {
        }
    }

    public static void incrementUnread(String contactUid) {
        if (contactUid == null) return;
        String uid = contactUid.trim();
        if (uid.isEmpty()) return;
        Set<String> keys = unreadKeysByUid.computeIfAbsent(uid,
                k -> ConcurrentHashMap.newKeySet());
        keys.add("t:" + System.currentTimeMillis());
        try {
            Contacts.getInstance().updateTotalUnreadCount();
        } catch (Exception ignored) {
        }
    }

    public static void clearUnread(String contactUid) {
        if (contactUid == null) return;
        String uid = contactUid.trim();
        if (uid.isEmpty()) return;
        unreadKeysByUid.remove(uid);
        seenInboundMidsByUid.remove(uid);
        lastMsgFingerprintByUid.remove(uid);
        lastMsgFingerprintMsByUid.remove(uid);
        try {
            Contacts.getInstance().updateTotalUnreadCount();
        } catch (Exception ignored) {
        }
    }

    public static void clearAllUnread() {
        unreadKeysByUid.clear();
        seenInboundMidsByUid.clear();
        lastMsgFingerprintByUid.clear();
        lastMsgFingerprintMsByUid.clear();
        try {
            Contacts.getInstance().updateTotalUnreadCount();
        } catch (Exception ignored) {
        }
    }

    private static int getUnread(String contactUid) {
        if (contactUid == null) return 0;
        Set<String> keys = unreadKeysByUid.get(contactUid.trim());
        return keys == null ? 0 : keys.size();
    }

    @Override
    public boolean isSupported(String type) {
        return FileSystemUtils.isEquals(type, PluginConnector.CONNECTOR_TYPE)
                || FileSystemUtils.isEquals(type, com.atakmap.android.contact.IpConnector.CONNECTOR_TYPE);
    }

    @Override
    public boolean hasFeature(
            ContactConnectorManager.ConnectorFeature feature) {
        return true;
    }

    @Override
    public String getName() {
        return "BTECH Relay";
    }

    @Override
    public boolean handleContact(String connectorType, String contactUID,
            String connectorAddress) {

        Contact contact = Contacts.getInstance().getContactByUuid(contactUID);

        if (contact instanceof IndividualContact) {

            // Open chat UI
            ChatManagerMapComponent.getInstance().openConversation(
                    (IndividualContact) contact, true);

            clearUnread(contactUID);
            Log.i("BTRelay", "Contact selected for chat: " + contactUID);
        }

        return true;
    }

    @Override
    public Object getFeature(String connectorType,
            ContactConnectorManager.ConnectorFeature feature,
            String contactUID, String connectorAddress) {

        Log.i("BTRelay.Handler", "getFeature feature=" + feature
                + " uid=" + contactUID + " address=" + connectorAddress);

        if (feature == ContactConnectorManager.ConnectorFeature.NotificationCount) {
            // Integer count; ATAK uses this to show red-dot badges in Contacts UI.
            int n = getUnread(contactUID);
            Log.i("BTRelay.Handler", "NotificationCount uid=" + contactUID + " -> " + n);
            return n;
        }

        return null;
    }

    @Override
    public String getDescription() {
        return "BTECH Relay Contact Handler";
    }
}
