package com.btechrelay.plugin.chat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotDetail;

import com.btechrelay.plugin.ax25.Ax25Frame;
import com.btechrelay.plugin.bluetooth.BtConnectionManager;
import com.btechrelay.plugin.cot.CotBridge;
import com.btechrelay.plugin.crypto.EncryptionManager;
import com.btechrelay.plugin.protocol.BtechRelayPacket;

/**
 * Bridges ATAK GeoChat messages with the radio link.
 *
 * Inbound (radio → ATAK):
 *   - Receives chat messages from PacketRouter
 *   - Uses CotBridge to inject GeoChat CoT events
 *
 * Outbound (ATAK → radio):
 *   - Listens for GeoChat send intents from ATAK
 *   - Packages as BtechRelay chat packets and sends to radio
 *
 * GeoChat in ATAK uses CoT events with type "b-t-f" (bits-text-free).
 * The actual message text is in detail/remarks inner text.
 */
public class ChatBridge {

    private static final String TAG = "BtechRelay.ChatBridge";

    /** ATAK broadcasts this when a GeoChat message is sent */
    private static final String ACTION_CHAT_SEND =
            "com.atakmap.android.chat.SEND_MESSAGE";

    private final Context pluginContext;
    private final MapView mapView;
    private CotBridge cotBridge;
    private BtConnectionManager btManager;
    private EncryptionManager encryptionManager;
    private String localCallsign = "OPENRL";

    /** Whether to relay outgoing chat to radio */
    private boolean relayOutgoing = true;

    private BroadcastReceiver chatReceiver;

    public ChatBridge(Context pluginContext, MapView mapView) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
    }

    public void setCotBridge(CotBridge cotBridge) {
        this.cotBridge = cotBridge;
    }

    public void setBtManager(BtConnectionManager btManager) {
        this.btManager = btManager;
    }

    public void setEncryptionManager(EncryptionManager em) {
        this.encryptionManager = em;
    }

    public void setLocalCallsign(String callsign) {
        this.localCallsign = callsign;
    }

    public void setRelayOutgoing(boolean relay) {
        this.relayOutgoing = relay;
    }

    /**
     * Inject a message received from the radio into ATAK as GeoChat.
     *
     * @param fromCallsign Sender callsign
     * @param toCallsign   Destination (callsign or room name)
     * @param message      Message text
     */
    public void injectRadioMessage(String fromCallsign, String toCallsign,
                                   String message) {
        if (cotBridge == null) {
            Log.w(TAG, "CotBridge not set — cannot inject chat");
            return;
        }

        // Determine chat room — if destination is a specific callsign,
        // use direct chat. Otherwise use broadcast.
        String chatRoom;
        if (toCallsign == null || toCallsign.isEmpty()
                || "ALL".equalsIgnoreCase(toCallsign)
                || "BLN".equalsIgnoreCase(toCallsign.substring(0,
                Math.min(3, toCallsign.length())))) {
            chatRoom = "All Chat Rooms";
        } else {
            chatRoom = toCallsign.trim();
        }

        Log.d(TAG, "Injecting radio message: "
                + fromCallsign + " → " + chatRoom + ": " + message);

        cotBridge.injectChatCot(fromCallsign, message, chatRoom);
    }

    /**
     * Register broadcast receiver to intercept outgoing ATAK chat
     * and relay to radio.
     */
    public void startOutgoingRelay() {
        chatReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleOutgoingChat(intent);
            }
        };

        // Register for GeoChat send events.
        // Some ATAK builds emit chat sends via SEND_MESSAGE (intent extras),
        // and/or via COT_PLACED (with CoT XML).
        AtakBroadcast.DocumentedIntentFilter filter =
                new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction("com.atakmap.android.maps.COT_PLACED");
        filter.addAction(ACTION_CHAT_SEND);
        AtakBroadcast.getInstance().registerReceiver(chatReceiver, filter);

        Log.d(TAG, "Outgoing chat relay started");
    }

    /**
     * Handle an outgoing chat intent from ATAK.
     */
    private void handleOutgoingChat(Intent intent) {
        if (!relayOutgoing) return;
        if (btManager == null || !btManager.isConnected()) {
            return;
        }
        if (intent == null) return;

        try {
            final String action = intent.getAction();

            // Path A: chat send intent with explicit extras (preferred if present).
            if (ACTION_CHAT_SEND.equals(action)) {
                String message = intent.getStringExtra("message");
                String chatRoom = intent.getStringExtra("chatroom");
                String toUid = intent.getStringExtra("toUID");
                if (toUid == null) toUid = intent.getStringExtra("toUid");
                if (toUid == null) toUid = intent.getStringExtra("uid");
                if (chatRoom == null) chatRoom = intent.getStringExtra("room");

                // Log intent shape for field discovery (keep it short).
                try {
                    android.os.Bundle extras = intent.getExtras();
                    if (extras != null) {
                        StringBuilder keys = new StringBuilder();
                        for (String k : extras.keySet()) {
                            if (keys.length() > 0) keys.append(",");
                            keys.append(k);
                        }
                        Log.d(TAG, "SEND_MESSAGE extras keys: " + keys);
                    }
                } catch (Exception ignored) {
                }

                if (message == null || message.isEmpty()) {
                    // Some builds use "text" instead of "message"
                    message = intent.getStringExtra("text");
                }
                if (chatRoom == null || chatRoom.isEmpty()) {
                    chatRoom = "All Chat Rooms";
                }

                // Only relay when the destination is a plugin-created contact.
                // SEND_MESSAGE extras vary by build; ANDROID-VETTE1 must match callsign VETTE1.
                boolean shouldRelay =
                        cotBridge != null && cotBridge.isBtechOutboundChatDestination(toUid,
                        chatRoom);
                if (!shouldRelay && cotBridge != null) {
                    String[] fallback =
                            {"destUID", "destinationUID", "recipientUID",
                                    "recipient", "destination", "to",
                                    "toCallsign"};
                    for (String k : fallback) {
                        String v = intent.getStringExtra(k);
                        if (cotBridge.isBtechOutboundChatDestination(v, null)) {
                            shouldRelay = true;
                            break;
                        }
                    }
                }
                if (!shouldRelay) return;
                if (message == null || message.isEmpty()) return;

                Log.d(TAG, "Relaying outgoing chat (SEND_MESSAGE) to radio: " + message);
                sendChatOverRadio(localCallsign, chatRoom, message);
                return;
            }

            String cotXml = intent.getStringExtra("xml");
            if (cotXml == null) return;

            CotEvent event = CotEvent.parse(cotXml);
            if (event == null) return;

            // Only relay GeoChat CoT messages when the destination is a plugin-created
            // (radio) contact. This prevents relaying all chat over radio when the user
            // chats with network contacts.
            if (cotBridge != null && !cotBridge.shouldRelayGeoChatToRadio(event)) {
                return;
            }

            // Extract message from remarks
            CotDetail detail = event.getDetail();
            if (detail == null) return;

            String message = null;
            String chatRoom = "All Chat Rooms";

            // Find remarks element for the message text
            CotDetail remarks = detail.getFirstChildByName(0, "remarks");
            if (remarks != null) {
                message = remarks.getInnerText();
            }

            // Find __chat element for room info
            CotDetail chat = detail.getFirstChildByName(0, "__chat");
            if (chat != null) {
                String room = chat.getAttribute("chatroom");
                if (room != null && !room.isEmpty()) {
                    chatRoom = room;
                }
            }

            if (message == null || message.isEmpty()) {
                return;
            }

            Log.d(TAG, "Relaying outgoing chat to radio: " + message);
            sendChatOverRadio(localCallsign, chatRoom, message);

        } catch (Exception e) {
            Log.e(TAG, "Error handling outgoing chat", e);
        }
    }

    /**
     * Send a chat message over the radio link.
     */
    public void sendChatOverRadio(String sender, String room, String message) {
        if (btManager == null || !btManager.isConnected()) {
            Log.w(TAG, "Not connected — cannot send chat");
            return;
        }

        try {
            BtechRelayPacket packet = BtechRelayPacket.createChatPacket(
                    com.btechrelay.plugin.util.CallsignUtil.toRadioCallsign(sender), room, message);

            byte[] packetBytes = packet.encode();
            // Encrypt if enabled
            if (encryptionManager != null && encryptionManager.isEnabled()) {
                packetBytes = encryptionManager.encrypt(packetBytes);
                if (packetBytes == null) {
                    Log.e(TAG, "Encryption failed — aborting chat send");
                    return;
                }
            }
            Ax25Frame frame = Ax25Frame.createBtechRelayFrame(
                    localCallsign, 0, packetBytes);
            byte[] ax25 = frame.encode();

            Log.d(TAG, "Sending chat over radio: " + ax25.length + " bytes");
            btManager.sendKissFrame(ax25);
        } catch (Exception e) {
            Log.e(TAG, "Error sending chat over radio", e);
        }
    }

    /**
     * Clean up resources.
     */
    public void dispose() {
        if (chatReceiver != null) {
            try {
                AtakBroadcast.getInstance()
                        .unregisterReceiver(chatReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering chat receiver", e);
            }
            chatReceiver = null;
        }
        Log.d(TAG, "ChatBridge disposed");
    }
}
