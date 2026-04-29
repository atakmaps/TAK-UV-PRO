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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BtechRelayContactHandler extends
        ContactConnectorManager.ContactConnectorHandler {

    private final Context pluginContext;

    /**
     * Unread counter store for plugin contacts. ATAK queries this via
     * {@link #getFeature} with {@code ConnectorFeature.NotificationCount} to drive UI badges.
     *
     * NOTE: ATAK may query NotificationCount for multiple connectors for the same UID
     * (e.g. plugin connector + null/default). Return a count only for the plugin connector
     * address to avoid double-counting in the UI.
     */
    private static final int MAX_UNREAD_KEYS_PER_UID = 128;
    private static final Map<String, Set<String>> unreadKeysByUid = new ConcurrentHashMap<>();

    public BtechRelayContactHandler(Context pluginContext) {
        this.pluginContext = pluginContext;
    }

    public static void incrementUnreadOnce(String contactUid, int radioPacketMessageId,
                                           String messageText) {
        if (contactUid == null) return;
        String uid = contactUid.trim();
        if (uid.isEmpty()) return;

        String key;
        if (radioPacketMessageId != 0) {
            key = "mid:" + radioPacketMessageId;
        } else if (messageText != null && !messageText.isEmpty()) {
            key = "fp:" + Integer.toHexString(messageText.hashCode());
        } else {
            key = "t:" + System.currentTimeMillis();
        }

        Set<String> keys = unreadKeysByUid.computeIfAbsent(uid,
                k -> ConcurrentHashMap.newKeySet());
        keys.add(key);
        if (keys.size() > MAX_UNREAD_KEYS_PER_UID) {
            keys.clear();
            keys.add(key);
        }
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
        try {
            Contacts.getInstance().updateTotalUnreadCount();
        } catch (Exception ignored) {
        }
    }

    public static void clearAllUnread() {
        unreadKeysByUid.clear();
        try {
            Contacts.getInstance().updateTotalUnreadCount();
        } catch (Exception ignored) {
        }
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
            // Avoid double-count: ATAK may query multiple connectors for the same UID.
            if (connectorAddress == null) return 0;
            Set<String> keys = unreadKeysByUid.get(contactUID != null ? contactUID.trim() : "");
            return keys == null ? 0 : keys.size();
        }

        return null;
    }

    @Override
    public String getDescription() {
        return "BTECH Relay Contact Handler";
    }
}
