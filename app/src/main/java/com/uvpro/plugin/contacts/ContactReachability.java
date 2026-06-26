package com.uvpro.plugin.contacts;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import com.atakmap.android.chat.GeoChatConnector;
import com.atakmap.android.chat.ChatManagerMapComponent;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.contact.IpConnector;
import com.atakmap.android.contact.PluginConnector;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.comms.NetConnectString;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.uvpro.plugin.chat.GeoChatContactListHelper;
import com.uvpro.plugin.cot.CotBridge;
import com.uvpro.plugin.network.WifiContactKeepalive;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Classifies peers as GeoChat-reachable from this device and applies comms connector policy.
 * Map markers / SA are unaffected — only chat/comms default actions are restricted.
 */
public final class ContactReachability {

    private static final String TAG = "UVPro.Reachability";
    private static final String ALL_CHAT_ROOMS = "All Chat Rooms";
    private static final Pattern OPAQUE_WIFI_DEVICE_UID =
            Pattern.compile("^[0-9A-F]{16}$");

    private ContactReachability() {
    }

    public static boolean isPolicyEnabled(Context context) {
        return false;
    }

    public static boolean isGeoChatReachable(IndividualContact contact, CotBridge bridge) {
        if (contact == null || isProtectedContact(contact)) {
            return false;
        }
        return hasWifiChatPath(contact, bridge) || hasRfChatPath(contact, bridge);
    }

    /**
     * Direct Wi‑Fi GeoChat: local Wi‑Fi up and a routable connector for a peer that owns
     * their LAN endpoint (not a relay-stamped IP shared with the bridge).
     */
    public static boolean hasWifiChatPath(IndividualContact contact, CotBridge bridge) {
        if (contact == null || bridge == null || !isLocalWifiAvailable()) {
            return false;
        }
        if (resolveRoutableNetworkEndpoint(contact) == null) {
            return false;
        }
        if (!isDirectLanWifiPeer(contact, bridge)) {
            return false;
        }
        String uid = contact.getUID();
        if (uid != null) {
            bridge.markWifiNativeContact(uid.trim());
        }
        return true;
    }

    public static boolean hasRfChatPath(IndividualContact contact, CotBridge bridge) {
        if (contact == null || bridge == null || !bridge.isRadioConnected()) {
            return false;
        }
        if (isWifiOnlyRemotePeer(contact, bridge)) {
            return false;
        }
        String uid = contact.getUID();
        if (uid != null && bridge.isRfNativeContact(uid.trim())) {
            return true;
        }
        String name = contact.getName();
        if (name != null && bridge.isRfHeardCallsign(name.trim())) {
            return true;
        }
        return uid != null && bridge.isBtechContactUid(uid.trim());
    }

    /**
     * Contact-targeted map CoT should go out over RF only when at least one recipient
     * is RF-reachable. WiFi/TAK-only peers are delivered by ATAK core; mixed selections
     * still trigger one RF broadcast for the RF leg.
     */
    public static boolean hasAnyRfReachableMapRecipient(String[] toUIDs, CotBridge bridge) {
        if (toUIDs == null || toUIDs.length == 0 || bridge == null || !bridge.isRadioConnected()) {
            return false;
        }
        String self = safeSelfUid();
        for (String uid : toUIDs) {
            if (uid == null || uid.trim().isEmpty()) {
                continue;
            }
            String trimmed = uid.trim();
            if (ALL_CHAT_ROOMS.equalsIgnoreCase(trimmed)) {
                continue;
            }
            if (!self.isEmpty() && self.equalsIgnoreCase(trimmed)) {
                continue;
            }
            try {
                Contact c = Contacts.getInstance().getContactByUuid(trimmed);
                if (c instanceof IndividualContact
                        && hasRfChatPath((IndividualContact) c, bridge)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
            // Plugin-registered UIDs can be WiFi/TAK-only; only trust the set when
            // the UID does not look like an opaque ATAK device id (JJ-4/JJ-5 pattern).
            if (bridge.isBtechContactUid(trimmed) && !isOpaqueWifiDeviceUid(trimmed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wi‑Fi-only peers (never heard on RF here) must not show an RF chat path.
     */
    private static boolean isWifiOnlyRemotePeer(IndividualContact contact, CotBridge bridge) {
        if (contact == null || bridge == null) {
            return false;
        }
        String uid = contact.getUID();
        if (uid == null || uid.trim().isEmpty()) {
            return false;
        }
        String trimmed = uid.trim();
        String name = contact.getName() != null ? contact.getName().trim() : "";

        // Dual-net / RF peers heard on radio here are not Wi‑Fi-only remote.
        if (!name.isEmpty() && bridge.isRfHeardCallsign(name)) {
            return false;
        }
        if (bridge.isRfNativeContact(trimmed)) {
            // Relay can stale-mark Wi‑Fi peers; confirm Wi‑Fi fingerprint before blocking RF.
            if (bridge.isWifiNativeContact(trimmed) || isDirectLanWifiPeer(contact, bridge)) {
                return true;
            }
            return false;
        }

        if (bridge.isWifiNativeContact(trimmed)) {
            return true;
        }
        if (isDirectLanWifiPeer(contact, bridge)) {
            return true;
        }
        if (!isLocalWifiAvailable() && resolveRoutableNetworkEndpoint(contact) != null) {
            return true;
        }
        return isOpaqueWifiDeviceUid(trimmed);
    }

    public static boolean isLocalWifiAvailable() {
        Context ctx = resolveContext();
        if (ctx == null) {
            return false;
        }
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            Network active = cm.getActiveNetwork();
            if (active == null) {
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * SA Relay (network → RF): skip Wi‑Fi-only peers; keep RF-registered / dual-net contacts.
     */
    public static boolean shouldSaRelayNetworkSa(CotEvent event, CotBridge bridge) {
        if (event == null) {
            return false;
        }
        String uid = event.getUID();
        if (uid == null || uid.trim().isEmpty()) {
            return false;
        }
        if (bridge != null && bridge.isBtechContactUid(uid.trim())) {
            return true;
        }
        String endpoint = extractCotContactEndpoint(event);
        if (endpoint != null && !endpoint.trim().isEmpty()) {
            Log.d(TAG, "SA Relay skip WiFi-only uid=" + uid + " endpoint=" + endpoint);
            return false;
        }
        if (isOpaqueWifiDeviceUid(uid)) {
            Log.d(TAG, "SA Relay skip opaque WiFi uid=" + uid);
            return false;
        }
        return true;
    }

    /**
     * Ignore directed network GeoChat not addressed to this device (bridge overhear).
     */
    public static boolean isInboundNetworkGeoChatForLocalDevice(CotEvent event) {
        if (event == null || !"b-t-f".equals(event.getType())) {
            return false;
        }
        if (GeoChatContactListHelper.isAllChatRoomsGeoChat(event)) {
            return true;
        }
        String selfUid = safeSelfUid();
        if (selfUid.isEmpty()) {
            return true;
        }
        String senderUid = GeoChatContactListHelper.extractChatSenderUid(event);
        if (selfUid.equalsIgnoreCase(senderUid)) {
            return false;
        }
        String peerThread = extractGeoChatPeerThreadUid(event);
        if (!peerThread.isEmpty() && selfUid.equalsIgnoreCase(peerThread)) {
            return true;
        }
        String remarksTo = extractGeoChatRemarksTo(event);
        if (!remarksTo.isEmpty() && selfUid.equalsIgnoreCase(remarksTo)) {
            return true;
        }
        Log.d(TAG, "Skip overheard network GeoChat self=" + selfUid
                + " thread=" + peerThread + " to=" + remarksTo
                + " from=" + senderUid);
        return false;
    }

    public static void applyAllContactCommsPolicies(CotBridge bridge) {
        try {
            Contacts contacts = Contacts.getInstance();
            List<Contact> all = contacts.getAllContacts();
            if (all == null) {
                return;
            }
            int updated = 0;
            for (Contact c : all) {
                if (!(c instanceof IndividualContact)) {
                    continue;
                }
                if (applyContactCommsPolicy((IndividualContact) c, bridge)) {
                    updated++;
                }
            }
            if (updated > 0) {
                Log.i(TAG, "Restored native GeoChat connectors for " + updated + " contact(s)");
            }
        } catch (Exception e) {
            Log.w(TAG, "applyAllContactCommsPolicies failed", e);
        }
    }

    /**
     * @return true when connectors or prefs were changed
     */
    public static boolean applyContactCommsPolicy(IndividualContact contact, CotBridge bridge) {
        if (contact == null || isProtectedContact(contact)) {
            return false;
        }
        boolean hasPositionOnly =
                contact.getConnector(PositionOnlyConnector.CONNECTOR_TYPE) != null;
        boolean missingGeoChat =
                contact.getConnector(GeoChatConnector.CONNECTOR_TYPE) == null;
        if (!hasPositionOnly && !missingGeoChat) {
            return false;
        }
        return com.uvpro.plugin.chat.ChatBridge.preferNativeContactActionInternal(contact);
    }

    static boolean isProtectedContact(IndividualContact contact) {
        if (contact == null) {
            return true;
        }
        String uid = contact.getUID();
        if (uid == null || uid.trim().isEmpty()) {
            return true;
        }
        String trimmed = uid.trim();
        if (ALL_CHAT_ROOMS.equals(trimmed)
                || trimmed.contains("All Chat Rooms")) {
            return true;
        }
        try {
            String role = ChatManagerMapComponent.getRoleName();
            if (role != null && role.equals(trimmed)) {
                return true;
            }
        } catch (Exception ignored) {
        }
        String self = safeSelfUid();
        return !self.isEmpty() && self.equalsIgnoreCase(trimmed);
    }

    static NetConnectString resolveRoutableNetworkEndpoint(IndividualContact contact) {
        if (contact == null) {
            return null;
        }
        NetConnectString ncs = parseRoutableEndpoint(contact.getConnector(IpConnector.CONNECTOR_TYPE));
        if (ncs != null) {
            return ncs;
        }
        return parseRoutableEndpoint(contact.getConnector(GeoChatConnector.CONNECTOR_TYPE));
    }

    private static NetConnectString parseRoutableEndpoint(com.atakmap.android.contact.Connector connector) {
        if (connector == null) {
            return null;
        }
        String cs = connector.getConnectionString();
        if (cs == null || cs.trim().isEmpty()) {
            return null;
        }
        NetConnectString ncs = NetConnectString.fromString(cs.trim());
        return WifiContactKeepalive.isRoutableEndpoint(ncs) ? ncs : null;
    }

    /**
     * True when this contact owns their LAN IP (direct Wi‑Fi peer), not a relay-stamped endpoint
     * shared with another contact (e.g. bridge IP on an RF-uplinked SA).
     */
    static boolean isDirectLanWifiPeer(IndividualContact contact, CotBridge bridge) {
        if (contact == null) {
            return false;
        }
        NetConnectString ncs = resolveRoutableNetworkEndpoint(contact);
        if (ncs == null) {
            return false;
        }
        String host = ncs.getHost();
        if (host == null || host.trim().isEmpty()) {
            return false;
        }
        IndividualContact hostOwner = findLanEndpointHostOwner(host.trim(), bridge);
        if (hostOwner == null) {
            return false;
        }
        String contactUid = contact.getUID();
        String ownerUid = hostOwner.getUID();
        return contactUid != null && ownerUid != null
                && contactUid.trim().equalsIgnoreCase(ownerUid.trim());
    }

    private static IndividualContact findLanEndpointHostOwner(String host, CotBridge bridge) {
        if (host == null || host.isEmpty()) {
            return null;
        }
        try {
            Contacts contacts = Contacts.getInstance();
            List<Contact> all = contacts.getAllContacts();
            if (all == null) {
                return null;
            }
            List<IndividualContact> onHost = new java.util.ArrayList<>();
            for (Contact c : all) {
                if (!(c instanceof IndividualContact)) {
                    continue;
                }
                IndividualContact ic = (IndividualContact) c;
                NetConnectString ncs = resolveRoutableNetworkEndpoint(ic);
                if (ncs == null || !host.equalsIgnoreCase(ncs.getHost())) {
                    continue;
                }
                onHost.add(ic);
            }
            if (onHost.isEmpty()) {
                return null;
            }
            if (bridge != null) {
                for (IndividualContact ic : onHost) {
                    String uid = ic.getUID();
                    if (uid != null && bridge.isWifiNativeContact(uid.trim())) {
                        return ic;
                    }
                }
            }
            if (onHost.size() == 1) {
                IndividualContact sole = onHost.get(0);
                if (connectorSuffixMatchesCallsign(sole, sole.getName())) {
                    return sole;
                }
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean connectorSuffixMatchesCallsign(IndividualContact contact, String callsign) {
        if (contact == null || callsign == null || callsign.trim().isEmpty()) {
            return false;
        }
        String normalized = callsign.trim().toUpperCase(Locale.US);
        for (String type : new String[] {
                IpConnector.CONNECTOR_TYPE,
                GeoChatConnector.CONNECTOR_TYPE
        }) {
            com.atakmap.android.contact.Connector connector = contact.getConnector(type);
            if (connector == null) {
                continue;
            }
            String cs = connector.getConnectionString();
            if (cs == null || cs.trim().isEmpty()) {
                continue;
            }
            NetConnectString ncs = NetConnectString.fromString(cs.trim());
            if (ncs == null) {
                continue;
            }
            String callsignPart = ncs.getCallsign();
            if (callsignPart != null
                    && normalized.equals(callsignPart.trim().toUpperCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }

    public static String extractCotContactEndpoint(CotEvent event) {
        if (event == null || event.getDetail() == null) {
            return null;
        }
        try {
            CotDetail contact = event.getDetail().getFirstChildByName(0, "contact");
            if (contact == null) {
                return null;
            }
            String endpoint = contact.getAttribute("endpoint");
            return endpoint != null ? endpoint.trim() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static String extractGeoChatPeerThreadUid(CotEvent event) {
        CotDetail chat = GeoChatContactListHelper.findChatDetailPublic(event);
        if (chat == null) {
            return "";
        }
        CotDetail chatgrp = chat.getFirstChildByName(0, "chatgrp");
        if (chatgrp == null) {
            chatgrp = chat.getFirstChildByName(0, "chatGroup");
        }
        if (chatgrp != null) {
            String uid1 = chatgrp.getAttribute("uid1");
            if (uid1 != null && !uid1.trim().isEmpty()) {
                return uid1.trim();
            }
        }
        String id = chat.getAttribute("id");
        return id != null ? id.trim() : "";
    }

    private static String extractGeoChatRemarksTo(CotEvent event) {
        if (event == null || event.getDetail() == null) {
            return "";
        }
        try {
            CotDetail remarks = event.getDetail().getFirstChildByName(0, "remarks");
            if (remarks == null) {
                return "";
            }
            String to = remarks.getAttribute("to");
            return to != null ? to.trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    static boolean isOpaqueWifiDeviceUid(String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return false;
        }
        String upper = uid.trim().toUpperCase(Locale.US);
        if (upper.startsWith("ANDROID-")) {
            upper = upper.substring("ANDROID-".length());
        }
        return OPAQUE_WIFI_DEVICE_UID.matcher(upper).matches();
    }

    private static String safeSelfUid() {
        try {
            String uid = MapView.getDeviceUid();
            return uid != null ? uid.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static Context resolveContext() {
        try {
            MapView mv = MapView.getMapView();
            if (mv != null && mv.getContext() != null) {
                return mv.getContext();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void writeDefaultConnectorPref(AtakPreferences prefs, String contactUid,
                                                    String connectorType) {
        if (prefs == null || contactUid == null || contactUid.trim().isEmpty()) {
            return;
        }
        String trimmed = contactUid.trim();
        prefs.set("contact.connector.default." + trimmed, connectorType);
        prefs.set("contact.connector.default." + trimmed.toUpperCase(Locale.US), connectorType);
        prefs.set("contact.connector.default." + trimmed.toLowerCase(Locale.US), connectorType);
    }

    private static void clearDefaultConnectorPref(AtakPreferences prefs, String contactUid) {
        writeDefaultConnectorPref(prefs, contactUid, "");
    }
}
