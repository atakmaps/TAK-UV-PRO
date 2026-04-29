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
     * ATAK already tracks unread GeoChat state via its internal ChatDatabase / Contacts
     * system when we inject valid GeoChat CoT. Returning our own NotificationCount
     * causes double-counting (badge shows 2 when ChatDatabase already counts 1).
     */

    public BtechRelayContactHandler(Context pluginContext) {
        this.pluginContext = pluginContext;
    }

    public static void clearAllUnread() {
        // no-op: rely on ATAK native unread tracking
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
            // Let ATAK's native chat system drive unread counts for injected GeoChat.
            return null;
        }

        return null;
    }

    @Override
    public String getDescription() {
        return "BTECH Relay Contact Handler";
    }
}
