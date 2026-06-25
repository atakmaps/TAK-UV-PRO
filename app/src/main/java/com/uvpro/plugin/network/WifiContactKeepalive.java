package com.uvpro.plugin.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;

import com.atakmap.android.chat.GeoChatConnector;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.contact.IpConnector;
import com.atakmap.android.contact.PluginConnector;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.comms.CotDispatcher;
import com.atakmap.comms.NetConnectString;
import com.atakmap.commoncommo.CoTSendMethod;
import com.atakmap.coremap.cot.event.CotEvent;
import com.uvpro.plugin.cot.CotBridge;
import com.uvpro.plugin.cot.CotBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Periodically unicasts a mini self-SA to Wi‑Fi / mesh contacts so serverless multicast
 * peers stay fresh when inbound refresh is unreliable.
 */
public final class WifiContactKeepalive {

    private static final String TAG = "UVPro.WifiKeepalive";
    private static final long INTERVAL_MS = 60_000L;
    private static final long INITIAL_DELAY_MS = 10_000L;
    private static final long SA_STALE_MS = 120_000L;
    private static final String PREF_NON_STREAMING = "enableNonStreamingConnections";

    private final MapView mapView;
    private final CotBridge cotBridge;
    private final Handler handler;
    private final Runnable tick;
    private boolean running;

    public WifiContactKeepalive(MapView mapView, CotBridge cotBridge) {
        this.mapView = mapView;
        this.cotBridge = cotBridge;
        this.handler = new Handler(Looper.getMainLooper());
        this.tick = new Runnable() {
            @Override
            public void run() {
                onTick();
            }
        };
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        handler.postDelayed(tick, INITIAL_DELAY_MS);
        Log.i(TAG, "WiFi contact keepalive started (interval=" + (INTERVAL_MS / 1000) + "s)");
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    private void onTick() {
        if (!running) {
            return;
        }
        handler.postDelayed(tick, INTERVAL_MS);
        try {
            tickOnce();
        } catch (Exception e) {
            Log.w(TAG, "keepalive tick failed", e);
        }
    }

    private void tickOnce() {
        if (!isWifiAvailable()) {
            return;
        }
        if (cotBridge == null || !cotBridge.isWifiTransmitEnabled()) {
            return;
        }
        if (!isNonStreamingEnabled()) {
            return;
        }

        CotEvent sa = CotBuilder.buildSelfWifiKeepaliveCot(mapView, SA_STALE_MS);
        if (sa == null || !sa.isValid()) {
            return;
        }

        String selfUid = safeUid(MapView.getDeviceUid());
        List<IndividualContact> peers = collectWifiPeers(selfUid);
        if (peers.isEmpty()) {
            return;
        }

        CotDispatcher dispatcher = CotMapComponent.getExternalDispatcher();
        if (dispatcher == null) {
            return;
        }

        int sent = 0;
        for (IndividualContact peer : peers) {
            try {
                dispatcher.dispatchToContact(sa, peer, CoTSendMethod.POINT_TO_POINT);
                sent++;
            } catch (Exception e) {
                Log.d(TAG, "unicast keepalive failed uid=" + peer.getUID(), e);
            }
        }
        if (sent > 0) {
            Log.d(TAG, "WiFi keepalive SA sent to " + sent + " peer(s)");
        }
    }

    private boolean isWifiAvailable() {
        try {
            String endpoint = CotMapComponent.getEndpoint();
            return endpoint != null && !endpoint.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isNonStreamingEnabled() {
        Context ctx = mapView != null ? mapView.getContext() : null;
        if (ctx == null) {
            return false;
        }
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
            return prefs.getBoolean(PREF_NON_STREAMING, true);
        } catch (Exception e) {
            return true;
        }
    }

    private static List<IndividualContact> collectWifiPeers(String selfUid) {
        List<IndividualContact> peers = new ArrayList<>();
        try {
            Contacts contacts = Contacts.getInstance();
            if (contacts == null) {
                return peers;
            }
            List<Contact> all = contacts.getAllContacts();
            if (all == null) {
                return peers;
            }
            for (Contact contact : all) {
                if (!(contact instanceof IndividualContact)) {
                    continue;
                }
                IndividualContact ic = (IndividualContact) contact;
                if (!isWifiMeshPeer(ic, selfUid)) {
                    continue;
                }
                peers.add(ic);
            }
        } catch (Exception e) {
            Log.w(TAG, "collectWifiPeers failed", e);
        }
        return peers;
    }

    /**
     * Native Wi‑Fi / LAN mesh peers: GeoChat or IP connector present, not plugin-only RF.
     */
    static boolean isWifiMeshPeer(IndividualContact ic, String selfUid) {
        if (ic == null) {
            return false;
        }
        String uid = safeUid(ic.getUID());
        if (uid.isEmpty() || uid.equalsIgnoreCase(selfUid)) {
            return false;
        }
        boolean hasGeoChat = ic.getConnector(GeoChatConnector.CONNECTOR_TYPE) != null;
        boolean hasIp = ic.getConnector(IpConnector.CONNECTOR_TYPE) != null;
        if (!hasGeoChat && !hasIp) {
            return false;
        }
        if (ic.getConnector(PluginConnector.CONNECTOR_TYPE) != null && !hasGeoChat && !hasIp) {
            return false;
        }
        return hasRoutableNetworkHint(ic);
    }

    private static boolean hasRoutableNetworkHint(IndividualContact ic) {
        NetConnectString ncs = resolveNetworkEndpoint(ic);
        if (ncs != null) {
            return true;
        }
        // GeoChat seed (stcp:*:-1) still counts — commo resolves UID from prior SA.
        return ic.getConnector(GeoChatConnector.CONNECTOR_TYPE) != null
                || ic.getConnector(IpConnector.CONNECTOR_TYPE) != null;
    }

    private static NetConnectString resolveNetworkEndpoint(IndividualContact ic) {
        if (ic == null) {
            return null;
        }
        NetConnectString ncs = parseRoutableEndpoint(ic.getConnector(IpConnector.CONNECTOR_TYPE));
        if (ncs != null) {
            return ncs;
        }
        return parseRoutableEndpoint(ic.getConnector(GeoChatConnector.CONNECTOR_TYPE));
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
        return isRoutableEndpoint(ncs) ? ncs : null;
    }

    public static boolean isRoutableEndpoint(NetConnectString ncs) {
        if (ncs == null) {
            return false;
        }
        String host = ncs.getHost();
        if (host == null || host.isEmpty() || "*".equals(host) || "0.0.0.0".equals(host)) {
            return false;
        }
        int port = ncs.getPort();
        return port > 0 && port != Integer.MIN_VALUE;
    }

    private static String safeUid(String uid) {
        return uid != null ? uid.trim() : "";
    }
}
