package com.uvpro.plugin;

import android.util.Log;

import com.atakmap.android.chat.ChatManagerMapComponent;
import com.atakmap.android.chat.GeoChatConnector;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.ContactConnectorManager;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.contact.PluginConnector;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.comms.NetConnectString;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.uvpro.plugin.chat.ChatBridge;
import com.uvpro.plugin.bluetooth.MeshBtConnectionManager;
import com.uvpro.plugin.contacts.MeshFavoriteConnector;
import com.uvpro.plugin.contacts.MeshRequestPositionConnector;
import com.uvpro.plugin.contacts.MeshSendMessageConnector;
import com.uvpro.plugin.contacts.PositionOnlyConnector;
import com.uvpro.plugin.protocol.PositionRequester;
import com.uvpro.plugin.util.CallsignUtil;

import android.widget.Toast;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class UVProContactHandler extends
        ContactConnectorManager.ContactConnectorHandler {

    private static final String MESH_NODE_UID_PREFIX = "MESHCORE-NODE-";
    private static final String MESH_RPTR_UID_PREFIX = "MESHCORE-RPTR-";

    /** Must match ChatBridge.ACTION_PLUGIN_CONTACT_GEOCHAT_SEND. */
    public static final String PLUGIN_GEOCHAT_ACTION =
            "com.uvpro.plugin.action.PLUGIN_CONTACT_GEOCHAT_SEND";

    private final android.content.Context pluginContext;

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

    public UVProContactHandler(android.content.Context pluginContext) {
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
                || FileSystemUtils.isEquals(type, GeoChatConnector.CONNECTOR_TYPE)
                || FileSystemUtils.isEquals(type, MeshFavoriteConnector.CONNECTOR_TYPE)
                || FileSystemUtils.isEquals(type, MeshSendMessageConnector.CONNECTOR_TYPE)
                || FileSystemUtils.isEquals(type, MeshRequestPositionConnector.CONNECTOR_TYPE);
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
            IndividualContact ic = (IndividualContact) contact;

            if (FileSystemUtils.isEquals(connectorType, MeshFavoriteConnector.CONNECTOR_TYPE)) {
                if (promoteMeshFavoriteContactByUid(ic.getUID(), ic.getName())) {
                    Toast.makeText(pluginContext,
                            "Favorited " + ic.getName(),
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            if (FileSystemUtils.isEquals(connectorType,
                    MeshRequestPositionConnector.CONNECTOR_TYPE)) {
                String uid = ic.getUID();
                if (uid != null && uid.startsWith(MESH_RPTR_UID_PREFIX)) {
                    Toast.makeText(pluginContext,
                            "Repeaters do not support ping",
                            Toast.LENGTH_LONG).show();
                    return true;
                }
                String atakTarget = resolvePingTargetCallsign(ic);
                if (atakTarget == null || atakTarget.isEmpty()) {
                    Toast.makeText(pluginContext,
                            "Could not resolve contact callsign",
                            Toast.LENGTH_LONG).show();
                    return true;
                }
                boolean ok = PositionRequester.requestPosition(
                        pluginContext, uid, atakTarget);
                String failReason = ok ? null
                        : (com.uvpro.plugin.contacts.ContactReachability.isLocalWifiAvailable()
                                ? "WiFi/TAK unavailable"
                                : "radio not connected");
                Toast.makeText(pluginContext,
                        ok ? "Ping sent to " + atakTarget
                                : "Ping failed (" + failReason + ")",
                        Toast.LENGTH_LONG).show();
                return true;
            }

            if (FileSystemUtils.isEquals(connectorType, GeoChatConnector.CONNECTOR_TYPE)
                    || FileSystemUtils.isEquals(connectorType, MeshSendMessageConnector.CONNECTOR_TYPE)) {
                clearUnread(contactUID);
                try {
                    android.content.Intent markRead = new android.content.Intent(
                            "com.atakmap.chat.markmessageread");
                    markRead.putExtra("conversationId", contactUID);
                    com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(markRead);
                } catch (Exception ignored) {
                }
                ChatManagerMapComponent.getInstance().openConversation(ic, false);
                Log.i("BTRelay", "Contact selected for chat: " + contactUID);
                return true;
            }
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
            // Return 0 for all contacts — let ATAK's native GeoChatConnector be the sole badge
            // source. ATAK's ContactConnectorManager sums return values across ALL registered
            // handlers for the same connector type. Returning our own count here doubles the badge
            // because ATAK's built-in GeoChatConnectorHandler also returns 1 for the same contact
            // ("Geo Chat: 1 + Send Message: 1" = 2). Native tracking clears correctly when the
            // user opens the conversation.
            Log.i("UVPro.Handler", "NotificationCount uid=" + contactUID
                    + " addr=" + connectorAddress + " -> 0 (native badge only)");
            return 0;
        }

        return null;
    }

    /**
     * Re-stamp GeoChatConnector as the default for this contact after GeoChatService.onCotEvent
     * may have overwritten the preference. For MESHCORE-* contacts, also posts a 600ms delayed
     * dispatchChangeEvent on the main thread so the contacts-list icon refreshes to the chat
     * bubble without triggering a duplicate message reload.
     */
    public static void repairAtakPeerConnectorDefault(String uid) {
        if (uid == null || uid.trim().isEmpty()) return;
        try {
            String u = uid.trim();
            Contacts contacts = Contacts.getInstance();
            Contact c = contacts.getContactByUuid(u);
            if (!(c instanceof IndividualContact)) return;
            IndividualContact ic = (IndividualContact) c;
            if (ic.getConnector(MeshSendMessageConnector.CONNECTOR_TYPE) == null) {
                ic.addConnector(new MeshSendMessageConnector());
            }
            ensurePingConnectorForContact(ic);
            writeDefaultConnectorPref(u, GeoChatConnector.CONNECTOR_TYPE);
            if (u.startsWith("MESHCORE-NODE-") || u.startsWith("MESHCORE-RPTR-")) {
                final IndividualContact finalIc = ic;
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(() -> {
                            try { finalIc.dispatchChangeEvent(); } catch (Exception ignored) {}
                        }, 600);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public String getDescription() {
        return "UV-PRO Contact Handler";
    }

    public static boolean promoteMeshFavoriteContactByUid(String contactUid, String currentName) {
        if (contactUid == null || contactUid.trim().isEmpty()) {
            return false;
        }
        try {
            final String uid = contactUid.trim();
            final Contacts contacts = Contacts.getInstance();
            final String favoriteName = formatMeshFavoriteName(currentName, uid);
            Contact existing = contacts.getContactByUuid(uid);
            MapItem item = existing instanceof IndividualContact
                    ? ((IndividualContact) existing).getMapItem()
                    : null;

            if (existing != null) {
                contacts.removeContact(existing);
            }
            IndividualContact favored = new IndividualContact(
                    favoriteName,
                    uid,
                    item,
                    buildNativeConnectorSeed(favoriteName));
            applyMeshContactConnectors(favored);
            contacts.addContact(favored);
            contacts.updateTotalUnreadCount();
            return true;
        } catch (Exception e) {
            Log.w("UVPro.Handler", "promoteMeshFavoriteContact failed", e);
            return false;
        }
    }

    /**
     * Ensure a chat contact for a mesh-node UID whose default action routes over the plugin RF DM
     * path ({@link MeshSendMessageConnector}) rather than native GeoChat/IP. Mesh nodes are
     * addressed by pubkey, so the synthesized {@code stcp/*:-1} Ip endpoint is unroutable and
     * makes ATAK's {@code CotDispatcher} throw "Send to unknown contact". Unlike
     * {@link #promoteMeshFavoriteContactByUid}, this does not rename the contact to the favorite
     * format; it only fixes the connector stack so map-selected sends actually relay.
     */
    public static boolean ensureMeshChatContactByUid(String contactUid, String currentName) {
        if (contactUid == null || contactUid.trim().isEmpty()) {
            return false;
        }
        try {
            final String uid = contactUid.trim();
            final Contacts contacts = Contacts.getInstance();
            MapItem item = null;
            com.atakmap.android.maps.MapView mv = com.atakmap.android.maps.MapView.getMapView();
            if (mv != null && mv.getRootGroup() != null) {
                item = mv.getRootGroup().deepFindUID(uid);
            }
            String name = formatMeshFavoriteName(currentName, uid, item);
            Contact existing = contacts.getContactByUuid(uid);
            if (existing instanceof IndividualContact) {
                IndividualContact ic = (IndividualContact) existing;
                if (name != null && !name.equals(ic.getName())) {
                    ic.setName(name);
                }
                applyMeshContactConnectors(ic);
                return true;
            }
            IndividualContact c = new IndividualContact(name, uid, item,
                    buildNativeConnectorSeed(name));
            applyMeshContactConnectors(c);
            contacts.addContact(c);
            contacts.updateTotalUnreadCount();
            return true;
        } catch (Exception e) {
            Log.w("UVPro.Handler", "ensureMeshChatContactByUid failed", e);
            return false;
        }
    }

    private static void applyMeshContactConnectors(IndividualContact contact) {
        if (contact == null) {
            return;
        }
        try {
            contact.removeConnector(PositionOnlyConnector.CONNECTOR_TYPE);
        } catch (Exception ignored) {
        }
        try {
            if (contact.getConnector(MeshFavoriteConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new MeshFavoriteConnector());
            }
            if (contact.getConnector(MeshSendMessageConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new MeshSendMessageConnector());
            }
            ensurePingConnectorForContact(contact);
            if (contact.getConnector(GeoChatConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new GeoChatConnector(
                        buildNativeConnectorSeed(contact.getName())));
            }
            writeDefaultConnectorPref(contact.getUID(), GeoChatConnector.CONNECTOR_TYPE);
            contact.dispatchChangeEvent();
        } catch (Exception e) {
            Log.w("UVPro.Handler", "applyMeshContactConnectors failed", e);
        }
    }

    /** Adds the Ping connector on the Connectors page for RF-capable individual contacts. */
    public static void ensurePingConnectorForContact(IndividualContact contact) {
        if (contact == null) {
            return;
        }
        String uid = contact.getUID();
        if (uid == null || uid.trim().isEmpty()) {
            return;
        }
        if (uid.startsWith(MESH_RPTR_UID_PREFIX)) {
            return;
        }
        if (resolvePingTargetCallsign(contact).isEmpty()) {
            return;
        }
        try {
            if (contact.getConnector(MeshRequestPositionConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new MeshRequestPositionConnector());
            }
        } catch (Exception e) {
            Log.w("UVPro.Handler", "ensurePingConnectorForContact failed", e);
        }
    }

    /**
     * Resolves the ATAK callsign for a directed ping to this specific contact.
     */
    public static String resolvePingTargetCallsign(IndividualContact contact) {
        if (contact == null) {
            return "";
        }
        String uid = contact.getUID();
        String contactName = contact.getName();

        com.uvpro.plugin.cot.CotBridge bridge = ChatBridge.getMergeRoutingBridge();
        if (bridge != null && uid != null && !uid.trim().isEmpty()) {
            String registered = bridge.resolveRegisteredCallsignForUid(uid, contactName);
            if (registered != null && !registered.trim().isEmpty()) {
                return stripMeshSuffix(registered.trim());
            }
        }

        String fromName = stripMeshSuffix(contactName);
        if (!fromName.isEmpty()) {
            return fromName;
        }

        com.atakmap.android.maps.MapView mv = com.atakmap.android.maps.MapView.getMapView();
        if (mv != null && mv.getRootGroup() != null && uid != null) {
            MapItem item = mv.getRootGroup().deepFindUID(uid);
            if (item != null) {
                String mapCall = item.getMetaString("callsign", item.getTitle());
                if (mapCall != null && !mapCall.trim().isEmpty()) {
                    return stripMeshSuffix(mapCall.trim());
                }
            }
        }

        String geoChatCallsign = geoChatCallsignFromContact(contact);
        if (!geoChatCallsign.isEmpty()) {
            return stripMeshSuffix(geoChatCallsign);
        }

        if (uid != null && uid.startsWith("ANDROID-")) {
            String bare = uid.substring("ANDROID-".length());
            if (!com.uvpro.plugin.cot.CotBridge.isOpaqueDeviceUid(bare)) {
                return stripMeshSuffix(bare);
            }
        }
        return "";
    }

    /**
     * Resolves a 6-character radio callsign for directed ping / position request.
     */
    public static String resolveRadioCallsignForContact(IndividualContact contact) {
        String atakTarget = resolvePingTargetCallsign(contact);
        if (atakTarget.isEmpty()) {
            return "";
        }
        return CallsignUtil.toRadioCallsign(atakTarget);
    }

    private static String stripMeshSuffix(String raw) {
        if (raw == null) {
            return "";
        }
        String base = raw.trim();
        if (base.isEmpty()) {
            return "";
        }
        if (base.toUpperCase(Locale.US).endsWith("-MESH")) {
            base = base.substring(0, base.length() - 5).trim();
        }
        return base;
    }

    private static String geoChatCallsignFromContact(IndividualContact contact) {
        if (contact == null) {
            return "";
        }
        try {
            com.atakmap.android.contact.Connector conn =
                    contact.getConnector(GeoChatConnector.CONNECTOR_TYPE);
            if (!(conn instanceof GeoChatConnector)) {
                return "";
            }
            String cs = conn.getConnectionString();
            if (cs == null || cs.trim().isEmpty()) {
                return "";
            }
            int lastColon = cs.lastIndexOf(':');
            if (lastColon >= 0 && lastColon + 1 < cs.length()) {
                return cs.substring(lastColon + 1).trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    public static void repairAllContactPingConnectors() {
        try {
            java.util.List<Contact> all = Contacts.getInstance().getAllContacts();
            if (all == null) {
                return;
            }
            for (Contact c : all) {
                if (c instanceof IndividualContact) {
                    ensurePingConnectorForContact((IndividualContact) c);
                }
            }
        } catch (Exception e) {
            Log.w("UVPro.Handler", "repairAllContactPingConnectors failed", e);
        }
    }

    public static String formatMeshFavoriteName(String currentName, String uid) {
        return formatMeshFavoriteName(currentName, uid, null);
    }

    /**
     * When a native MeshCore app sends a pubkey DM, ensure a GeoChat-capable contact exists
     * even if the operator has not favorited the node yet (map marker may exist from advert).
     */
    public static String ensureMeshInboundChatContact(String senderPubKeyPrefixHex) {
        if (senderPubKeyPrefixHex == null || senderPubKeyPrefixHex.trim().isEmpty()) {
            return "";
        }
        String prefix = senderPubKeyPrefixHex.trim().toUpperCase(Locale.US);
        try {
            com.atakmap.android.maps.MapView mv = com.atakmap.android.maps.MapView.getMapView();
            MapItem mapItem = findMapItemByPubKeyPrefix(mv, prefix);
            String uid = mapItem != null ? mapItem.getUID() : (MESH_NODE_UID_PREFIX + prefix);
            if (uid == null || uid.trim().isEmpty()) {
                return "";
            }
            uid = uid.trim();
            Contacts contacts = Contacts.getInstance();
            Contact existing = contacts.getContactByUuid(uid);
            if (existing instanceof IndividualContact) {
                IndividualContact ic = (IndividualContact) existing;
                applyMeshInboundConnectors(ic);
                return uid;
            }
            String nameHint = (mapItem == null && prefix.length() >= 8)
                    ? prefix.substring(0, 8) : null;
            String displayName = formatMeshFavoriteName(nameHint, uid, mapItem);
            IndividualContact created = new IndividualContact(
                    displayName,
                    uid,
                    mapItem,
                    buildNativeConnectorSeed(displayName));
            applyMeshInboundConnectors(created);
            contacts.addContact(created);
            contacts.updateTotalUnreadCount();
            Log.i("UVPro.Handler", "Created inbound mesh chat contact " + displayName + " uid=" + uid);
            return uid;
        } catch (Exception e) {
            Log.w("UVPro.Handler", "ensureMeshInboundChatContact failed prefix=" + prefix, e);
            return "";
        }
    }

    private static MapItem findMapItemByPubKeyPrefix(com.atakmap.android.maps.MapView mv,
                                                     String prefixUpper) {
        if (mv == null || mv.getRootGroup() == null || prefixUpper == null || prefixUpper.isEmpty()) {
            return null;
        }
        try {
            java.util.List<MapItem> items = mv.getRootGroup().deepFindItems("type", "a-f-G-U-C");
            if (items == null) {
                return null;
            }
            for (MapItem item : items) {
                if (item == null) {
                    continue;
                }
                String uid = item.getUID();
                if (uid == null) {
                    continue;
                }
                String u = uid.toUpperCase(Locale.US);
                if ((u.startsWith(MESH_NODE_UID_PREFIX) || u.startsWith(MESH_RPTR_UID_PREFIX))
                        && u.contains(prefixUpper)) {
                    return item;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void applyMeshInboundConnectors(IndividualContact contact) {
        if (contact == null) {
            return;
        }
        try {
            contact.removeConnector(PositionOnlyConnector.CONNECTOR_TYPE);
        } catch (Exception ignored) {
        }
        try {
            contact.removeConnector(PluginConnector.CONNECTOR_TYPE);
        } catch (Exception ignored) {
        }
        try {
            if (contact.getConnector(MeshFavoriteConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new MeshFavoriteConnector());
            }
            if (contact.getConnector(MeshSendMessageConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new MeshSendMessageConnector());
            }
            if (contact.getConnector(MeshRequestPositionConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new MeshRequestPositionConnector());
            }
            if (contact.getConnector(GeoChatConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new GeoChatConnector(
                        buildNativeConnectorSeed(contact.getName())));
            }
            writeDefaultConnectorPref(contact.getUID(), GeoChatConnector.CONNECTOR_TYPE);
        } catch (Exception e) {
            Log.w("UVPro.Handler", "applyMeshInboundConnectors failed", e);
        }
    }

    public static String uidForDeviceContact(MeshBtConnectionManager.MeshDeviceContact contact) {
        if (contact == null || contact.pubKeyHex == null || contact.pubKeyHex.length() < 12) {
            return "";
        }
        String prefix = contact.pubKeyHex.substring(0, 12).toUpperCase(Locale.US);
        if (contact.type == 0x02) {
            return MESH_RPTR_UID_PREFIX + prefix;
        }
        return MESH_NODE_UID_PREFIX + prefix;
    }

    public static boolean favoriteDeviceContact(MeshBtConnectionManager meshBt,
                                                MeshBtConnectionManager.MeshDeviceContact contact) {
        if (contact == null) {
            return false;
        }
        String uid = uidForDeviceContact(contact);
        if (uid.isEmpty()) {
            return false;
        }
        boolean atakOk = promoteMeshFavoriteContactByUid(uid, contact.name);
        boolean deviceOk = meshBt != null && meshBt.addOrUpdateDeviceContactFavorite(contact);
        return atakOk || deviceOk;
    }

    private static String formatMeshFavoriteName(String currentName, String uid, MapItem item) {
        String base = normalizeMeshBaseName(currentName);
        if (base.isEmpty() && item != null) {
            String mapCallsign = item.getMetaString("callsign", item.getTitle());
            base = normalizeMeshBaseName(mapCallsign);
        }
        if (base.isEmpty()) {
            base = normalizeMeshBaseName(uid);
        }
        if (base.isEmpty()) {
            base = "NODE";
        }
        return base + "-MESH";
    }

    private static String normalizeMeshBaseName(String raw) {
        if (raw == null) {
            return "";
        }
        String base = raw.trim();
        if (base.startsWith("#")) {
            base = base.substring(1);
        }
        base = base.trim();
        if (base.isEmpty()) {
            return "";
        }
        String upper = base.toUpperCase(Locale.US);
        if (upper.startsWith(MESH_NODE_UID_PREFIX) || upper.startsWith(MESH_RPTR_UID_PREFIX)) {
            return "";
        }
        if (upper.endsWith("-MESH")) {
            upper = upper.substring(0, upper.length() - 5).trim();
        }
        return upper;
    }

    private static NetConnectString buildNativeConnectorSeed(String callsign) {
        NetConnectString ncs = new NetConnectString("stcp", "127.0.0.1", 4242);
        if (callsign != null && !callsign.trim().isEmpty()) {
            ncs.setCallsign(callsign.trim().toUpperCase());
        }
        return ncs;
    }

    private static void writeDefaultConnectorPref(String contactUid, String connectorType) {
        try {
            com.atakmap.android.maps.MapView mv = com.atakmap.android.maps.MapView.getMapView();
            if (mv == null || contactUid == null || contactUid.trim().isEmpty()) {
                return;
            }
            AtakPreferences prefs = new AtakPreferences(mv.getContext());
            String uid = contactUid.trim();
            prefs.set("contact.connector.default." + uid, connectorType);
            prefs.set("contact.connector.default." + uid.toUpperCase(), connectorType);
            prefs.set("contact.connector.default." + uid.toLowerCase(), connectorType);
        } catch (Exception ignored) {
        }
    }
}
