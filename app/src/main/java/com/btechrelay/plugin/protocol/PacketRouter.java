package com.btechrelay.plugin.protocol;

import android.util.Log;

import com.btechrelay.plugin.ax25.Ax25Frame;
import com.btechrelay.plugin.ax25.AprsParser;
import com.btechrelay.plugin.cot.CotBridge;
import com.btechrelay.plugin.chat.ChatBridge;
import com.btechrelay.plugin.contacts.ContactTracker;
import com.btechrelay.plugin.crypto.EncryptionManager;
import com.btechrelay.plugin.protocol.PacketFragmenter;

/**
 * Routes incoming packets to the appropriate handler based on their type.
 *
 * Incoming data from the radio arrives as raw AX.25 frames. The PacketRouter:
 * 1. Decodes the AX.25 frame to extract callsign and info field
 * 2. Determines if it's an APRS packet or an BtechRelay custom packet
 * 3. Routes to the appropriate handler (CoT bridge, chat bridge, contact tracker)
 *
 * For APRS packets (from standard APRS radios / BTECH native APRS):
 *   - Position reports → ContactTracker + CotBridge
 *   - Messages → ChatBridge
 *
 * For BtechRelay custom packets (from other BtechRelay plugins):
 *   - TYPE_GPS → ContactTracker + CotBridge
 *   - TYPE_CHAT → ChatBridge
 *   - TYPE_COT → CotBridge
 *   - TYPE_PING → ContactTracker
 */
public class PacketRouter {

    private static final String TAG = "BtechRelay.Router";

    private final CotBridge cotBridge;
    private final ChatBridge chatBridge;
    private final ContactTracker contactTracker;
    private final PacketFragmenter.Reassembler reassembler;
    private EncryptionManager encryptionManager;

    /** Listener for packet count updates */
    private PacketCountListener packetCountListener;

    public interface PacketCountListener {
        void onPacketReceived();
    }

    public PacketRouter(CotBridge cotBridge, ChatBridge chatBridge,
                        ContactTracker contactTracker) {
        this.cotBridge = cotBridge;
        this.chatBridge = chatBridge;
        this.contactTracker = contactTracker;
        this.reassembler = new PacketFragmenter.Reassembler();
    }

    public void setPacketCountListener(PacketCountListener listener) {
        this.packetCountListener = listener;
    }

    public void setEncryptionManager(EncryptionManager em) {
        this.encryptionManager = em;
    }

    /**
     * Route an incoming AX.25 frame from the radio.
     * Called by BtConnectionManager on the read thread.
     */
    public void routeIncoming(byte[] ax25Data) {
        Ax25Frame frame = Ax25Frame.decode(ax25Data);
        if (frame == null) {
            Log.w(TAG, "Failed to decode AX.25 frame");
            return;
        }

        String srcCall = frame.getSrcCallsign();
        int srcSsid = frame.getSrcSsid();
        String destCall = frame.getDestCallsign();
        String info = frame.getInfoString();

        Log.d(TAG, "Received from " + srcCall + "-" + srcSsid +
                " → " + destCall + ": " + info.length() + " bytes");

        // Notify listener of received packet
        if (packetCountListener != null) {
            packetCountListener.onPacketReceived();
        }

        // Check if this is an BtechRelay custom packet
        if ("OPENRL".equals(destCall)) {
            byte[] infoField = frame.getInfoField();

            // Decrypt if encryption is enabled
            if (encryptionManager != null && encryptionManager.isEnabled()) {
                byte[] decrypted = encryptionManager.decrypt(infoField);
                if (decrypted != null) {
                    infoField = decrypted;
                }
                // If decryption returns null, try raw (unencrypted packet)
            }

            routeBtechRelayPacket(srcCall, srcSsid, infoField);
            return;
        }

        // Otherwise, try to parse as standard APRS
        routeAprsPacket(srcCall, srcSsid, info);
    }

    /**
     * Route an BtechRelay custom packet.
     */
    private void routeBtechRelayPacket(String callsign, int ssid, byte[] data) {
        BtechRelayPacket packet = BtechRelayPacket.decode(data);
        if (packet == null) {
            Log.w(TAG, "Failed to decode BtechRelay packet");
            return;
        }

        switch (packet.getType()) {
            case BtechRelayPacket.TYPE_GPS:
                BtechRelayPacket.GpsData gps =
                        BtechRelayPacket.decodeGpsPayload(packet.getPayload());
                if (gps != null) {
                    Log.d(TAG, "GPS from " + gps.callsign +
                            ": " + gps.latitude + ", " + gps.longitude);
                    contactTracker.updateContact(gps.callsign, gps.latitude,
                            gps.longitude, gps.altitude, gps.speed,
                            gps.course, gps.battery);
                    
            // === ATAK CONTACT REGISTRATION (CRITICAL FIX) ===
            try {
                com.atakmap.android.contact.Contacts contacts =
                        com.atakmap.android.contact.Contacts.getInstance();

                String normalized = callsign.trim().toUpperCase();
                String uid = "ANDROID-" + normalized;

                com.atakmap.android.contact.IndividualContact c =
                        new com.atakmap.android.contact.IndividualContact(
                                normalized,
                                uid
                        );

                contacts.addContact(c);

            } catch (Exception e) {
                android.util.Log.e("BTRelay.CONTACT", "Contact add failed", e);
            }

            cotBridge.injectPositionCot(gps.callsign, gps.latitude,
                            gps.longitude, gps.altitude, gps.speed,
                            gps.course);
                }
                break;

            case BtechRelayPacket.TYPE_CHAT:
                routeChatPacket(packet.getPayload());
                break;

            case BtechRelayPacket.TYPE_COT:
                cotBridge.injectCompressedCot(packet.getPayload());
                break;

            case BtechRelayPacket.TYPE_PING:
                String pingCall = new String(packet.getPayload(),
                        java.nio.charset.StandardCharsets.US_ASCII).trim();
                Log.d(TAG, "Ping from: " + pingCall);
                contactTracker.handlePing(pingCall);
                break;

            case BtechRelayPacket.TYPE_ACK:
                Log.d(TAG, "ACK received");
                break;

            case BtechRelayPacket.TYPE_COT_FRAGMENT:
                byte[] reassembled = reassembler.addFragment(
                        packet.getPayload());
                if (reassembled != null) {
                    Log.d(TAG, "Fragment reassembly complete: "
                            + reassembled.length + " bytes");
                    cotBridge.injectCompressedCot(reassembled);
                }
                break;

            default:
                Log.w(TAG, "Unknown BtechRelay packet type: " + packet.getType());
        }
    }

    /**
     * Route a standard APRS packet.
     */
    private void routeAprsPacket(String callsign, int ssid, String info) {
        if (info.isEmpty()) return;

        // Try position first
        AprsParser.AprsPosition pos =
                AprsParser.parsePosition(callsign, ssid, info);
        if (pos != null) {
            Log.d(TAG, "APRS position from " + callsign +
                    ": " + pos.latitude + ", " + pos.longitude);
            contactTracker.updateContact(callsign, pos.latitude,
                    pos.longitude, pos.altitude, pos.speed, pos.course, -1);
            cotBridge.injectPositionCot(callsign, pos.latitude,
                    pos.longitude, pos.altitude, pos.speed, pos.course);
            return;
        }

        // Try message
        AprsParser.AprsMessage msg = AprsParser.parseMessage(callsign, info);
        if (msg != null) {
            Log.d(TAG, "APRS message from " + msg.fromCallsign +
                    " to " + msg.toCallsign + ": " + msg.message);
            chatBridge.injectRadioMessage(msg.fromCallsign,
                    msg.toCallsign, msg.message);
            return;
        }

        Log.d(TAG, "Unhandled APRS packet from " + callsign + ": " + info);
    }

    /**
     * Decode and route a chat message from BtechRelay packet payload.
     */
    private void routeChatPacket(byte[] payload) {
        if (payload == null || payload.length < 16) return;

        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(payload);
        buf.order(java.nio.ByteOrder.BIG_ENDIAN);

        byte[] senderBytes = new byte[6];
        byte[] roomBytes = new byte[6];
        buf.get(senderBytes);
        buf.get(roomBytes);
        int messageId = buf.getInt();

        byte[] msgBytes = new byte[buf.remaining()];
        buf.get(msgBytes);

        String sender = new String(senderBytes,
                java.nio.charset.StandardCharsets.US_ASCII).trim();
        String room = new String(roomBytes,
                java.nio.charset.StandardCharsets.US_ASCII).trim();
        String message = new String(msgBytes,
                java.nio.charset.StandardCharsets.UTF_8);

        Log.d(TAG, "Chat from " + sender + " [" + room + "]: " + message);
        chatBridge.injectRadioMessage(sender, room, message);
    }
}
