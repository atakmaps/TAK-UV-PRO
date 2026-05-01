package com.uvpro.plugin;

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

public class UVProContactHandler extends
        ContactConnectorManager.ContactConnectorHandler {

    /** Must match ChatBridge.ACTION_PLUGIN_CONTACT_GEOCHAT_SEND. */
    private static final String PLUGIN_GEOCHAT_ACTION =
            "com.uvpro.plugin.action.PLUGIN_CONTACT_GEOCHAT_SEND";

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

    public UVProContactHandler(Context pluginContext) {
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
        return FileSystemUtils.isEquals(type, PluginConnector.CONNECTOR_TYPE);
    }

    @Override
    public boolean hasFeature(
            ContactConnectorManager.ConnectorFeature feature) {
        return true;
    }

    @Override
    public String getName() {
        return "UV-PRO";
    }

    @Override
    public boolean handleContact(String connectorType, String contactUID,
            String connectorAddress) {

        Contact contact = Contacts.getInstance().getContactByUuid(contactUID);

        if (contact instanceof IndividualContact) {

            // Open chat UI
            ChatManagerMapComponent.getInstance().openConversation(
                    (IndividualContact) contact, true);

            // User is viewing this contact; clear unread badge.
            clearUnread(contactUID);
            Log.i("BTRelay", "Contact selected for chat: " + contactUID);
        }

        return true;
    }

    @Override
    public Object getFeature(String connectorType,
            ContactConnectorManager.ConnectorFeature feature,
            String contactUID, String connectorAddress) {

        Log.i("UVPro.Handler", "getFeature feature=" + feature
                + " uid=" + contactUID + " address=" + connectorAddress);

        if (feature == ContactConnectorManager.ConnectorFeature.NotificationCount) {
            // ATAK sums counts across connector queries; only the plugin connector may report
            // unread — all other addresses (including null) must return 0 or the UI shows 2.
            if (connectorAddress == null
                    || !PLUGIN_GEOCHAT_ACTION.equals(connectorAddress)) {
                Log.i("UVPro.Handler", "NotificationCount uid=" + contactUID + " addr="
                        + connectorAddress + " -> 0 (plugin-only)");
                return 0;
            }
            Set<String> keys = unreadKeysByUid.get(contactUID != null ? contactUID.trim() : "");
            int n = keys == null ? 0 : keys.size();
            Log.i("UVPro.Handler", "NotificationCount uid=" + contactUID + " addr=" + connectorAddress + " -> " + n);
            return n;
        }

        return null;
    }

    @Override
    public String getDescription() {
        return "UV-PRO Contact Handler";
    }
}
