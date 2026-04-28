package com.btechrelay.plugin.cot;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.cot.event.CotEvent;

import com.btechrelay.plugin.ax25.Ax25Frame;
import com.btechrelay.plugin.bluetooth.BtConnectionManager;
import com.btechrelay.plugin.crypto.EncryptionManager;
import com.btechrelay.plugin.protocol.BtechRelayPacket;
import com.btechrelay.plugin.protocol.PacketFragmenter;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges between ATAK CoT events and the radio link.
 *
 * Inbound (radio → ATAK):
 *   - Receives decoded data from PacketRouter
 *   - Builds CotEvent objects using CotBuilder
 *   - Dispatches them into ATAK's CoT processing pipeline
 *
 * Outbound (ATAK → radio):
 *   - Listens for local CoT events from ATAK
 *   - Compresses and fragments if needed
 *   - Sends via radio link
 */
public class CotBridge {

    private static final String TAG = "BtechRelay.CotBridge";

    private final Context pluginContext;
    private final MapView mapView;
    private BtConnectionManager btManager;
    private String localCallsign = "OPENRL";
    private String teamColor = "Cyan";
    private EncryptionManager encryptionManager;
    private CommsMapComponent.PreSendProcessor preSendProcessor;

    /** Whether to relay all outgoing SA to radio (can flood the channel) */
    private boolean relayOutgoingSa = false;

    /**
     * UIDs that the plugin considers radio-transport contacts.
     * Populated when the plugin creates/registers contacts from radio packets.
     */
    private final Set<String> btechContactUids = ConcurrentHashMap.newKeySet();

    /** Minimum interval between outgoing SA relays (ms) */
    private static final long SA_RELAY_INTERVAL_MS = 30_000;
    private long lastSaRelay = 0;

    public CotBridge(Context pluginContext, MapView mapView) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
    }

    public void setBtManager(BtConnectionManager btManager) {
        this.btManager = btManager;
    }

    public void setLocalCallsign(String callsign) {
        this.localCallsign = callsign;
    }

    public void setRelayOutgoingSa(boolean relay) {
        this.relayOutgoingSa = relay;
    }

    public void setTeamColor(String color) {
        this.teamColor = color;
    }

    public void setEncryptionManager(EncryptionManager em) {
        this.encryptionManager = em;
    }

    /**
     * Register a contact UID as a BTECH Relay radio endpoint.
     * This is used to route ATAK "send to contact" actions to the radio link
     * without globally relaying all CoT.
     */
    public void registerBtechContactUid(String uid) {
        if (uid == null) return;
        btechContactUids.add(uid);
    }

    /**
     * Decide whether an ATAK outbound event (from broadcast/intent) should be
     * relayed to the radio based on its destination UIDs.
     *
     * Many ATAK broadcasts include a `toUIDs` extra. If that list intersects
     * with our plugin-created contact UIDs, we treat it as a radio-targeted send.
     */
    public boolean shouldRelayToRadio(Intent intent) {
        if (intent == null) return false;
        try {
            java.util.ArrayList<String> toUIDs =
                    intent.getStringArrayListExtra("toUIDs");
            if (toUIDs == null || toUIDs.isEmpty()) return false;
            for (String uid : toUIDs) {
                if (uid != null && btechContactUids.contains(uid)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Inject a position CoT event into ATAK from radio GPS data.
     */
    public void injectPositionCot(String callsign, double lat, double lon,
                                  double alt, double speed, double course) {
        try {
            CotEvent event = CotBuilder.buildPositionCot(
                    callsign, lat, lon, alt, speed, course, teamColor);

            if (event != null && event.isValid()) {
                Log.d(TAG, "Injecting position CoT for " + callsign);
                dispatchCotEvent(event);
            } else {
                Log.w(TAG, "Invalid position CoT for " + callsign);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error injecting position CoT", e);
        }
    }

    /**
     * Inject a compressed CoT XML received from another BtechRelay node.
     */
    public void injectCompressedCot(byte[] compressed) {
        try {
            String xml = CotBuilder.decompressCot(compressed);
            if (xml == null || xml.isEmpty()) {
                Log.w(TAG, "Failed to decompress CoT");
                return;
            }

            CotEvent event = CotEvent.parse(xml);
            if (event != null && event.isValid()) {
                Log.d(TAG, "Injecting decompressed CoT: " + event.getUID());
                dispatchCotEvent(event);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error injecting compressed CoT", e);
        }
    }

    /**
     * Inject a chat CoT event into ATAK.
     */
    public void injectChatCot(String senderCallsign, String message,
                              String chatRoom) {
        try {
            String senderUid = "ANDROID-" + senderCallsign.trim().toUpperCase()
                    .toUpperCase();
            CotEvent event = CotBuilder.buildChatCot(
                    senderUid, senderCallsign, message, chatRoom);

            if (event != null && event.isValid()) {
                Log.d(TAG, "Injecting chat CoT from " + senderCallsign);
                dispatchCotEvent(event);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error injecting chat CoT", e);
        }
    }

    /**
     * Send a CoT event out over the radio link.
     * The CoT XML is gzipped and sent as an BtechRelay packet.
     */
    public void sendCotOverRadio(CotEvent event) {
        if (btManager == null || !btManager.isConnected()) {
            Log.w(TAG, "Not connected to radio — cannot send CoT");
            return;
        }

        try {
            String xml = event.toString();
            byte[] compressed = CotBuilder.compressCot(xml);
            if (compressed == null) {
                Log.e(TAG, "Failed to compress CoT for radio");
                return;
            }

            Log.d(TAG, "Sending CoT over radio: " + xml.length()
                    + " bytes XML → " + compressed.length + " bytes compressed");

            // Fragment if needed
            List<BtechRelayPacket> packets = PacketFragmenter.fragment(
                    BtechRelayPacket.TYPE_COT, compressed);

            for (BtechRelayPacket packet : packets) {
                byte[] packetBytes = packet.encode();
                // Encrypt entire packet bytes if enabled
                if (encryptionManager != null && encryptionManager.isEnabled()) {
                    packetBytes = encryptionManager.encrypt(packetBytes);
                    if (packetBytes == null) {
                        Log.e(TAG, "Encryption failed — aborting CoT send");
                        return;
                    }
                }
                Ax25Frame frame = Ax25Frame.createBtechRelayFrame(
                        localCallsign, 0, packetBytes);
                byte[] ax25 = frame.encode();
                btManager.sendKissFrame(ax25);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending CoT over radio", e);
        }
    }

    /**
     * Send local GPS position over radio as compact GPS packet.
     */
    public void sendPositionOverRadio(double lat, double lon, double alt,
                                      float speed, float course, int battery) {
        if (btManager == null || !btManager.isConnected()) return;

        try {
            BtechRelayPacket packet = BtechRelayPacket.createGpsPacket(
                    com.btechrelay.plugin.util.CallsignUtil.toRadioCallsign(localCallsign), localCallsign, lat, lon, (float) alt,
                    speed, course, battery);

            byte[] packetBytes = packet.encode();

            // Encrypt entire packet bytes if enabled
            if (encryptionManager != null && encryptionManager.isEnabled()) {
                packetBytes = encryptionManager.encrypt(packetBytes);
                if (packetBytes == null) {
                    Log.e(TAG, "Encryption failed — aborting GPS send");
                    return;
                }
            }

            Ax25Frame frame = Ax25Frame.createBtechRelayFrame(
                    localCallsign, 0, packetBytes);
            byte[] ax25 = frame.encode();

            Log.d(TAG, "Sending GPS beacon: " + ax25.length + " bytes");
            btManager.sendKissFrame(ax25);
        } catch (Exception e) {
            Log.e(TAG, "Error sending position over radio", e);
        }
    }

    /**
     * Dispatch a CotEvent into ATAK's internal processing.
     */
    private void dispatchCotEvent(CotEvent event) {
        // Use ATAK's internal CotMapComponent to dispatch
        try {
            CotMapComponent.getInternalDispatcher().dispatch(event);
            Log.d(TAG, "Dispatched CoT event: " + event.getUID());

            try {
                com.atakmap.android.maps.MapItem item =
                        com.atakmap.android.maps.MapView.getMapView()
                                .getRootGroup()
                                .deepFindUID(event.getUID());

                if (item != null) {
                    Log.d(TAG, "MARKER_DEBUG uid=" + item.getUID()
                            + " title=" + item.getTitle()
                            + " type=" + item.getType()
                            + " callsign=" + item.getMetaString("callsign", "NULL")
                            + " team=" + item.getMetaString("team", "NULL")
                            + " labels_on=" + item.hasMetaValue("labels_on")
                            + " hideLabel=" + item.hasMetaValue("hideLabel"));

                    if (item instanceof com.atakmap.android.maps.Marker) {
                        com.atakmap.android.maps.Marker m =
                                (com.atakmap.android.maps.Marker) item;
                        Log.d(TAG, "MARKER_DEBUG marker_class=true");
                    }
                } else {
                    Log.d(TAG, "MARKER_DEBUG item not found uid=" + event.getUID());
                }
            } catch (Exception dbg) {
                Log.e(TAG, "MARKER_DEBUG failed", dbg);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to dispatch via CotMapComponent, "
                    + "trying broadcast", e);
            // Fallback: send as broadcast intent
            try {
                Intent intent = new Intent("com.atakmap.android.maps.COT_PLACED");
                intent.putExtra("xml", event.toString());
                AtakBroadcast.getInstance().sendBroadcast(intent);
            } catch (Exception e2) {
                Log.e(TAG, "Failed to broadcast CoT event", e2);
            }
        }
    }

    /**
     * Start listening for outgoing CoT events to relay to radio.
     * Uses ATAK's PreSendProcessor to intercept all outgoing CoT.
     */
    public void startOutgoingRelay() {
        Log.d(TAG, "Outgoing CoT relay: "
                + (relayOutgoingSa ? "enabled" : "disabled"));

        preSendProcessor = (event, toUIDs) -> {
            if (btManager == null || !btManager.isConnected()) return;
            if (event == null) return;

            // Contact-targeted send: only relay when ATAK is sending to a
            // plugin-registered radio contact.
            boolean targetsBtechContact = false;
            if (toUIDs != null && toUIDs.length > 0) {
                for (String uid : toUIDs) {
                    if (uid != null && btechContactUids.contains(uid)) {
                        targetsBtechContact = true;
                        break;
                    }
                }
            }

            String type = event.getType();
            if (type == null) return;

            if (targetsBtechContact) {
                Log.d(TAG, "Relaying contact-targeted CoT to radio: type=" + type
                        + " uid=" + event.getUID()
                        + " toUIDs=" + toUIDs);
                new Thread(() -> sendCotOverRadio(event)).start();
                return;
            }

            // Optional broadcast SA relay (rate-limited) — does NOT depend on
            // a specific destination contact.
            if (!relayOutgoingSa) return;

            long now = System.currentTimeMillis();
            if (now - lastSaRelay < SA_RELAY_INTERVAL_MS) return;

            boolean shouldRelay = type.startsWith("a-f-")   // friendly SA
                    || type.startsWith("b-r-f-h")           // CASEVAC/medevac
                    || type.startsWith("b-m-p")             // markers/points
                    || type.equals("b-t-f")                 // geochat
                    || type.startsWith("u-");               // user-defined
            if (!shouldRelay) return;

            lastSaRelay = now;
            Log.d(TAG, "Relaying broadcast CoT to radio: type=" + type
                    + " uid=" + event.getUID());
            new Thread(() -> sendCotOverRadio(event)).start();
        };

        try {
            CommsMapComponent.getInstance().registerPreSendProcessor(
                    preSendProcessor);
            Log.d(TAG, "Registered PreSendProcessor for outgoing CoT relay");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register PreSendProcessor", e);
        }
    }

    /**
     * Stop and clean up.
     */
    public void dispose() {
        // Unregister PreSendProcessor — no unregister API, just null it out
        preSendProcessor = null;
        Log.d(TAG, "CotBridge disposed");
    }
}
