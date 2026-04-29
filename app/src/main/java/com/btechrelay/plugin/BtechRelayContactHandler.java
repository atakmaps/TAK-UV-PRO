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

import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BtechRelayContactHandler extends
        ContactConnectorManager.ContactConnectorHandler {

    private final Context pluginContext;

    /**
     * Minimal unread counter store for plugin contacts. ATAK queries this via
     * {@link #getFeature} with {@code ConnectorFeature.NotificationCount} to drive UI badges.
     */
    private static final Map<String, Integer> unreadByUid = new ConcurrentHashMap<>();

    /**
     * Dedupe inbound message ids per contact. Radio links can legitimately retransmit;
     * ATAK may dedupe display while our simple counter would double-increment.
     */
    private static final int MAX_SEEN_MIDS_PER_UID = 128;
    private static final Map<String, Set<Integer>> seenInboundMidsByUid =
            new ConcurrentHashMap<>();

    public BtechRelayContactHandler(Context pluginContext) {
        this.pluginContext = pluginContext;
    }

    public static void incrementUnreadOnce(String contactUid, int radioPacketMessageId) {
        if (contactUid == null) return;
        String uid = contactUid.trim();
        if (uid.isEmpty()) return;

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
        }

        unreadByUid.merge(uid, 1, Integer::sum);
        try {
            Contacts.getInstance().updateTotalUnreadCount();
        } catch (Exception ignored) {
        }
    }

    public static void incrementUnread(String contactUid) {
        if (contactUid == null) return;
        String uid = contactUid.trim();
        if (uid.isEmpty()) return;
        unreadByUid.merge(uid, 1, Integer::sum);
        try {
            Contacts.getInstance().updateTotalUnreadCount();
        } catch (Exception ignored) {
        }
    }

    public static void clearUnread(String contactUid) {
        if (contactUid == null) return;
        String uid = contactUid.trim();
        if (uid.isEmpty()) return;
        unreadByUid.remove(uid);
        seenInboundMidsByUid.remove(uid);
        try {
            Contacts.getInstance().updateTotalUnreadCount();
        } catch (Exception ignored) {
        }
    }

    private static int getUnread(String contactUid) {
        if (contactUid == null) return 0;
        Integer v = unreadByUid.get(contactUid.trim());
        return v == null ? 0 : v;
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
            return getUnread(contactUID);
        }

        return null;
    }

    @Override
    public String getDescription() {
        return "BTECH Relay Contact Handler";
    }
}
