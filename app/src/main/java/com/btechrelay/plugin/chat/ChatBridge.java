package com.btechrelay.plugin.chat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.fragment.app.Fragment;

import com.atakmap.android.chat.ChatManagerMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotDetail;

import com.btechrelay.plugin.ax25.Ax25Frame;
import com.btechrelay.plugin.bluetooth.BtConnectionManager;
import com.btechrelay.plugin.cot.CotBridge;
import com.btechrelay.plugin.crypto.EncryptionManager;
import com.btechrelay.plugin.protocol.BtechRelayPacket;
import com.btechrelay.plugin.BtechRelayContactHandler;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** ATAK broadcasts some GeoChat sends with this intent (extras vary). */
    private static final String ACTION_CHAT_SEND =
            "com.atakmap.android.chat.SEND_MESSAGE";

    /**
     * Broadcast action for outbound GeoChat to a contact whose delivery path uses
     * {@link com.atakmap.android.contact.PluginConnector}. ATAK invokes
     * {@code new Intent(connector.getConnectionString())...putExtra(\"MESSAGE\", bundle)}
     * (see ChatManagerMapComponent.sendMessageToDests) — so the action must be predictable
     * for our listener, not an opaque placeholder string.
     */
    public static final String ACTION_PLUGIN_CONTACT_GEOCHAT_SEND =
            "com.btechrelay.plugin.action.PLUGIN_CONTACT_GEOCHAT_SEND";

    private final Context pluginContext;
    private final MapView mapView;
    private CotBridge cotBridge;
    private BtConnectionManager btManager;
    private EncryptionManager encryptionManager;
    private String localCallsign = "OPENRL";

    /** Whether to relay outgoing chat to radio */
    private boolean relayOutgoing = true;

    private BroadcastReceiver chatReceiver;
    private BroadcastReceiver chatMarkReadReceiver;
    private BroadcastReceiver chatOpenReceiver;
    private BroadcastReceiver chatClosedReceiver;

    /**
     * Track the currently-open GeoChat conversation (if any). If the conversation is open,
     * inbound messages should not increment the contacts badge because the user is already
     * viewing the chat.
     */
    private volatile String openConversationId;

    /**
     * ATAK marks lines read in {@code ConversationFragment} via {@code markAllRead} →
     * {@code Contact.setUnreadCount} without always broadcasting {@code markmessageread}.
     * When native unread drops from &gt; 0 to 0, clear our plugin badge for that ANDROID-* uid.
     */
    private final ConcurrentHashMap<String, Integer> lastAtakUnreadByUid =
            new ConcurrentHashMap<>();
    private Contacts.OnContactsChangedListener contactsUnreadSyncListener;

    /** Runs after ATAK delivers chat to the UI (fragment may not exist yet on first callback). */
    private ChatManagerMapComponent.ChatMessageListener atakChatMessageListener;

    private volatile boolean disposed;

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
     * @param fromCallsign         Sender callsign (may be AX.25-truncated)
     * @param toCallsign           Destination (callsign or room name) from the wire
     * @param message              Message body
     * @param radioPacketMessageId TYPE_CHAT payload id ({@code putInt}); 0 if unknown (APRS path).
     */
    public void injectRadioMessage(String fromCallsign, String toCallsign,
                                   String message, int radioPacketMessageId) {
        if (cotBridge == null) {
            Log.w(TAG, "CotBridge not set — cannot inject chat");
            return;
        }
        if (message == null || message.isEmpty()) return;

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

        // Direct DM from a known peer: GeoChat threads by conversationId must match opening
        // chat from Contacts (ANDROID-<callsign>). Using RF destination (e.g. VETTE1) as
        // chatroom put messages under "VETTE1" while UI opens "ANDROID-JUNIOR" — blank thread.
        if (!"All Chat Rooms".equalsIgnoreCase(chatRoom)) {
            String peerUid = cotBridge.resolveBtechUidForId(fromCallsign);
            if (peerUid != null && !peerUid.isEmpty()) {
                Log.d(TAG, "Inbound DM: thread id " + chatRoom + " → " + peerUid
                        + " (match contact chat)");
                chatRoom = peerUid;
            }
        }

        Log.d(TAG, "Injecting radio message (mid=" + radioPacketMessageId + "): "
                + fromCallsign + " → " + chatRoom + ": " + message);

        // Maintain a plugin unread counter for Contacts icon badge.
        // (Native GeoChat unread tracking is not reliably reflected for plugin contacts on all builds.)
        if (chatRoom != null && chatRoom.startsWith("ANDROID-")) {
            String open = openConversationId;
            if (open != null && open.equals(chatRoom)) {
                // Conversation is open; treat as already-seen.
                BtechRelayContactHandler.clearUnread(chatRoom);
            } else {
                BtechRelayContactHandler.incrementUnreadOnce(chatRoom, radioPacketMessageId, message);
            }
        }

        cotBridge.injectChatCot(fromCallsign, message, chatRoom,
                radioPacketMessageId);
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
        filter.addAction(ACTION_PLUGIN_CONTACT_GEOCHAT_SEND);
        AtakBroadcast.getInstance().registerReceiver(chatReceiver, filter);

        // Track currently open GeoChat conversation to suppress unread badge increments when
        // the user is actively viewing that conversation.
        try {
            chatOpenReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    if (!"com.atakmap.android.OPEN_GEOCHAT".equals(intent.getAction())) return;
                    // ATAK (ChatManagerMapComponent) puts conversationId inside the
                    // parcelable "message" bundle, not as top-level intent extras — without
                    // this, opening GeoChat from the main chat menu never set openConversationId
                    // and the Contacts badge stayed stuck until Contacts pane opened chat.
                    String convo = null;
                    android.os.Bundle msgBundle = getOpenGeoChatMessageBundle(intent);
                    if (msgBundle != null) {
                        convo = msgBundle.getString("conversationId");
                        if (convo == null || convo.isEmpty()) {
                            convo = msgBundle.getString("chatroom");
                        }
                    }
                    if (convo == null || convo.isEmpty()) {
                        convo = intent.getStringExtra("conversationId");
                    }
                    if (convo == null || convo.isEmpty()) {
                        convo = intent.getStringExtra("chatroom");
                    }
                    if (convo == null || convo.isEmpty()) {
                        convo = intent.getStringExtra("id");
                    }
                    if (convo != null && !convo.isEmpty()) {
                        openConversationId = convo;
                        if (convo.startsWith("ANDROID-")) {
                            BtechRelayContactHandler.clearUnread(convo);
                            scheduleClearUnreadWhenGeoChatFragmentVisible(convo);
                        }
                    }
                }
            };
            AtakBroadcast.DocumentedIntentFilter openF =
                    new AtakBroadcast.DocumentedIntentFilter();
            openF.addAction("com.atakmap.android.OPEN_GEOCHAT");
            AtakBroadcast.getInstance().registerReceiver(chatOpenReceiver, openF);
        } catch (Exception ignored) {
        }

        try {
            chatClosedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    String a = intent.getAction();
                    if (!"com.atakmap.chat.chatroom_closed".equals(a)
                            && !"CHAT_ROOM_DROPDOWN_CLOSED".equals(a)) {
                        return;
                    }
                    openConversationId = null;
                }
            };
            AtakBroadcast.DocumentedIntentFilter closedF =
                    new AtakBroadcast.DocumentedIntentFilter();
            closedF.addAction("com.atakmap.chat.chatroom_closed");
            closedF.addAction("CHAT_ROOM_DROPDOWN_CLOSED");
            AtakBroadcast.getInstance().registerReceiver(chatClosedReceiver, closedF);
        } catch (Exception ignored) {
        }

        // Clear plugin badge when ATAK marks a message read (chat menu path, not only Contacts).
        try {
            chatMarkReadReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    if (!"com.atakmap.chat.markmessageread".equals(intent.getAction())) return;
                    android.os.Bundle b = intent.getBundleExtra("chat_bundle");
                    if (b == null) return;
                    String convo = b.getString("conversationId");
                    if (convo == null || convo.isEmpty()) return;
                    if (convo.startsWith("ANDROID-")) {
                        BtechRelayContactHandler.clearUnread(convo);
                    }
                }
            };
            AtakBroadcast.DocumentedIntentFilter markRead =
                    new AtakBroadcast.DocumentedIntentFilter();
            markRead.addAction("com.atakmap.chat.markmessageread");
            AtakBroadcast.getInstance().registerReceiver(chatMarkReadReceiver, markRead);
        } catch (Exception ignored) {
        }

        try {
            contactsUnreadSyncListener = new Contacts.OnContactsChangedListener() {
                @Override
                public void onContactsSizeChange(Contacts contacts) {
                }

                @Override
                public void onContactChanged(String contactUid) {
                    if (contactUid == null || !contactUid.startsWith("ANDROID-")) {
                        return;
                    }
                    try {
                        Contact c = Contacts.getInstance().getContactByUuid(contactUid);
                        if (c == null) {
                            return;
                        }
                        int now = c.getUnreadCount();
                        Integer prev = lastAtakUnreadByUid.put(contactUid, now);
                        if (prev != null && prev > 0 && now == 0) {
                            Log.d(TAG, "ATAK native unread cleared for " + contactUid
                                    + " — clearing plugin Contacts badge");
                            BtechRelayContactHandler.clearUnread(contactUid);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "contacts unread sync", e);
                    }
                }
            };
            Contacts.getInstance().addListener(contactsUnreadSyncListener);
        } catch (Exception e) {
            Log.w(TAG, "Could not register Contacts unread sync listener", e);
        }

        registerAtakChatMessageListenerWhenReady(0);

        Log.d(TAG, "Outgoing chat relay started");
    }

    private void registerAtakChatMessageListenerWhenReady(final int attempt) {
        if (disposed || atakChatMessageListener != null) {
            return;
        }
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (disposed || atakChatMessageListener != null) {
                    return;
                }
                try {
                    ChatManagerMapComponent cmmc = ChatManagerMapComponent.getInstance();
                    if (cmmc != null) {
                        atakChatMessageListener =
                                new ChatManagerMapComponent.ChatMessageListener() {
                                    @Override
                                    public void chatMessageReceived(android.os.Bundle bundle) {
                                        maybeClearPluginUnreadWhenGeoChatUiShows(bundle);
                                    }
                                };
                        cmmc.addChatMessageListener(atakChatMessageListener);
                        Log.d(TAG, "Registered ChatManagerMapComponent.ChatMessageListener");
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "ChatManagerMapComponent listener registration", e);
                }
                if (!disposed && attempt < 12 && mapView != null) {
                    mapView.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            registerAtakChatMessageListenerWhenReady(attempt + 1);
                        }
                    }, 500L);
                }
            }
        };
        if (mapView != null) {
            mapView.post(r);
        } else {
            r.run();
        }
    }

    /**
     * When ATAK has finished routing a chat line, clear our Contacts badge if that
     * conversation's {@link com.atakmap.android.chat.ConversationFragment} is on-screen
     * (main GeoChat path — {@code Contact.getUnreadCount} often stays 0 for plugin UIDs).
     */
    private void maybeClearPluginUnreadWhenGeoChatUiShows(android.os.Bundle messageBundle) {
        if (disposed || messageBundle == null) return;
        String convo = messageBundle.getString("conversationId");
        if (convo == null || !convo.startsWith("ANDROID-")) {
            return;
        }
        postClearUnreadIfFragmentVisible(convo, 0);
        postClearUnreadIfFragmentVisible(convo, 120);
        postClearUnreadIfFragmentVisible(convo, 400);
    }

    private void postClearUnreadIfFragmentVisible(final String conversationId, long delayMs) {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (disposed) {
                    return;
                }
                try {
                    if (isGeoChatConversationFragmentVisible(conversationId)) {
                        BtechRelayContactHandler.clearUnread(conversationId);
                        Log.d(TAG, "Plugin unread cleared (GeoChat fragment visible) " + conversationId);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "clear unread fragment check", e);
                }
            }
        };
        if (mapView != null) {
            mapView.postDelayed(r, delayMs);
        }
    }

    /** After OPEN_GEOCHAT, fragment creation can lag; poll briefly until it is resumed. */
    private void scheduleClearUnreadWhenGeoChatFragmentVisible(String conversationId) {
        postClearUnreadIfFragmentVisible(conversationId, 0);
        postClearUnreadIfFragmentVisible(conversationId, 80);
        postClearUnreadIfFragmentVisible(conversationId, 250);
        postClearUnreadIfFragmentVisible(conversationId, 700);
    }

    /**
     * ATAK keeps {@code ChatManagerMapComponent.fragmentMap} (conversationId → fragment).
     * Not a public API — reflect once per check, catch failures across ATAK versions.
     */
    private boolean isGeoChatConversationFragmentVisible(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return false;
        }
        try {
            Field f = ChatManagerMapComponent.class.getDeclaredField("fragmentMap");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> fm = (Map<String, Object>) f.get(null);
            if (fm == null) {
                return false;
            }
            Object o = fm.get(conversationId);
            if (!(o instanceof Fragment)) {
                return false;
            }
            Fragment fr = (Fragment) o;
            return fr.isResumed() && fr.isVisible();
        } catch (Throwable t) {
            return false;
        }
    }

    private static android.os.Bundle getMessageBundleExtra(Intent intent) {
        if (intent == null) return null;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                return intent.getParcelableExtra("MESSAGE",
                        android.os.Bundle.class);
            }
            return intent.getParcelableExtra("MESSAGE");
        } catch (Exception e) {
            return null;
        }
    }

    /** Bundle from {@code com.atakmap.android.OPEN_GEOCHAT} (key {@code "message"}). */
    private static android.os.Bundle getOpenGeoChatMessageBundle(Intent intent) {
        if (intent == null) return null;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                return intent.getParcelableExtra("message", android.os.Bundle.class);
            }
            android.os.Parcelable p = intent.getParcelableExtra("message");
            return p instanceof android.os.Bundle ? (android.os.Bundle) p : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Outbound GeoChat for plugin-connector contacts: {@code MESSAGE} bundle from
     * ChatManager → GeoChatService (conversationId / message keys).
     *
     * @return true if handled (relay attempted for a matching BTECH destination)
     */
    boolean relayPluginGeoChatMessageBundle(android.os.Bundle b) {
        if (cotBridge == null || b == null) return false;

        String conversationId = b.getString("conversationId");
        String msg = b.getString("message");
        if (msg == null || msg.isEmpty()) {
            return false;
        }

        if (!cotBridge.isBtechOutboundChatDestination(conversationId, null)) {
            return false;
        }

        String room = "All Chat Rooms";
        if (conversationId != null) {
            String cid = conversationId.trim();
            if (!cid.isEmpty() && !"All Chat Rooms".equalsIgnoreCase(cid)) {
                if (cid.startsWith("ANDROID-")) {
                    room = cid.substring("ANDROID-".length());
                } else {
                    room = cid;
                }
            }
        }

        Log.d(TAG, "Relay outgoing plugin-contact GeoChat to radio room=" + room);
        sendChatOverRadio(localCallsign, room, msg);
        return true;
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

            // Path plugin-contact GeoChat (ChatManager sends Intent(action=connectionString, MESSAGE=bundle)).
            if (ACTION_PLUGIN_CONTACT_GEOCHAT_SEND.equals(action)) {
                android.os.Bundle messageBundle = getMessageBundleExtra(intent);
                if (messageBundle != null) {
                    if (relayPluginGeoChatMessageBundle(messageBundle)) {
                        return;
                    }
                }
            }

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
        disposed = true;
        if (chatReceiver != null) {
            try {
                AtakBroadcast.getInstance()
                        .unregisterReceiver(chatReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering chat receiver", e);
            }
            chatReceiver = null;
        }
        if (chatMarkReadReceiver != null) {
            try {
                AtakBroadcast.getInstance()
                        .unregisterReceiver(chatMarkReadReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering mark-read receiver", e);
            }
            chatMarkReadReceiver = null;
        }
        if (chatOpenReceiver != null) {
            try {
                AtakBroadcast.getInstance()
                        .unregisterReceiver(chatOpenReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering open-chat receiver", e);
            }
            chatOpenReceiver = null;
        }
        if (chatClosedReceiver != null) {
            try {
                AtakBroadcast.getInstance()
                        .unregisterReceiver(chatClosedReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering chat-closed receiver", e);
            }
            chatClosedReceiver = null;
        }
        if (contactsUnreadSyncListener != null) {
            try {
                Contacts.getInstance().removeListener(contactsUnreadSyncListener);
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering Contacts listener", e);
            }
            contactsUnreadSyncListener = null;
        }
        if (atakChatMessageListener != null) {
            try {
                ChatManagerMapComponent cmmc = ChatManagerMapComponent.getInstance();
                if (cmmc != null) {
                    cmmc.removeChatMessageListener(atakChatMessageListener);
                }
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering ChatManager listener", e);
            }
            atakChatMessageListener = null;
        }
        lastAtakUnreadByUid.clear();
        Log.d(TAG, "ChatBridge disposed");
    }
}
