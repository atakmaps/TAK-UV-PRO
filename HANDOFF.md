# BTECH Relay / ATAK Plugin — Handoff & Architecture (for new agents)

This document is a **high-signal handoff** for a new engineer/agent coming into this repo mid-stream. It focuses on: how ATAK ↔ plugin integration works, how radio packets flow (BLE/KISS/AX.25), and the non-obvious ATAK behaviors discovered during the current iteration.

If you are new to ATAK plugin development, start with **`README.md`** for build/install basics and **`AGENTS.md`** for Cloud/VM setup details.

## What this plugin is now (important framing)

The plugin started as a “bridge” (toggleable relays of PLI/SA and GeoChat). It has evolved into a **contact-centric transport**:

- Plugin creates “sendable” ATAK contacts for radio peers (UIDs look like `ANDROID-<CALLSIGN>`).
- **GeoChat** to those contacts routes through the plugin `PluginConnector` (Intent action) and is transmitted over RF.
- **Contact-targeted CoT** (waypoints, routes, casevac/9-line, drawings, etc.) is intercepted by `CotBridge.PreSendProcessor`, compressed, and relayed over RF to the target radio contact.

This fork does **not** blindly relay all ATAK SA over RF; outbound traffic is centred on **contacts** (chat + targeted CoT) plus beacon/PLI.

This framing explains several implementation choices below (connectors, routing hooks, badge integration).

## Runtime architecture (services + main objects)

### Core objects (by responsibility)

- **`BtechRelayMapComponent`**: plugin lifecycle + wiring. Initializes bluetooth, router, CoT + chat bridges. Starts timers/listeners.
- **`BtConnectionManager`**: Bluetooth SPP link to the radio. Owns connect/reconnect behavior and raw byte IO.
- **KISS layer** (`kiss/`): wraps/unwraps AX.25 frames into KISS frames for TNC-over-serial style links.
- **AX.25 layer** (`ax25/`): parse/build AX.25 frames; APRS parsing for “standard” position payloads.
- **`PacketRouter`**: takes inbound frames/payloads and routes them to subsystems (chat, GPS/PLI, CoT fragments).
- **`BtechRelayPacket`**: compact binary packet formats for “BTECH relay” messages (chat, gps, etc.).
- **`PacketFragmenter`**: fragment/reassemble large payloads (notably large CoT) into multiple radio frames.
- **`CotBridge`** / **`CotBuilder`**: map CoT ⇄ radio. Inject inbound CoT into ATAK, build outbound position CoT, and relay CoT to radio when appropriate.
- **`ChatBridge`**: GeoChat ⇄ radio. Inject inbound radio chat into ATAK’s chat pipeline; intercept ATAK outbound chat to plugin contacts and send over radio.
- **`BtechRelayContactHandler`**: ATAK connector integration, including unread badge (`NotificationCount`) for plugin connector address.
- **`ContactTracker`** / `RadioContact`: maintains in-range/last-seen contacts and their latest state.

### ATAK integration points used (high level)

- **Contacts**:
  - Plugin registers contacts with ATAK so they appear in the native Contacts UI and are “sendable”.
  - Each contact is reachable via a **`PluginConnector`** so ATAK can route “send to contact” actions into the plugin using an Intent action string.
- **Chat**:
  - Outbound: plugin listens to ATAK’s chat send actions and handles “send to contact” bundles, then transmits over radio.
  - Inbound: plugin injects a `b-t-f` GeoChat CoT event so ATAK’s native parser creates the chat message/thread.
- **CoT / map objects**:
  - Position injections and inbound radio-derived CoT go through ATAK’s pipeline for map display.
  - `CotBridge` registers PreSend hooks and related instrumentation from earlier bridge work — useful for debugging and incremental features, **not** a guarantee that “send marker to contact” is a supported product path here.

## Data-plane logic trees

### A) Inbound over RF → visible on ATAK map/chat

```
RF
  ↓
Radio (KISS TNC)
  ↓  (Bluetooth SPP bytes)
BtConnectionManager (bytes)
  ↓
KISS decoder → AX.25 frame(s)
  ↓
PacketRouter
  ├─ if APRS position → map contact update/injection (APRS parser path)
  ├─ if BtechRelayPacket.Chat:
  │     ↓
  │   ChatBridge.injectRadioMessage(...)
  │     ├─ records wire mid in pendingReadAcksByConversation[senderUID]
  │     ↓ (build GeoChat CoT)
  │   CotBridge.injectChatCot(...)
  │     ↓
  │   ATAK GeoChat parser creates/updates thread + message
  │     ↓
  │   BtechRelayContactHandler increments NotificationCount (unread badge)
  │     ↓
  │   PacketRouter sends ACK_KIND_DELIVERED back to sender over RF
  │     (ACK_KIND_READ is sent later, when user opens the conversation — see clearUnreadLocal)
  ├─ if BtechRelayPacket.Gps/PLI:
  │     ↓
  │   CotBridge.injectPositionCot(...)
  │     ↓
  │   ATAK renders marker/contact on map
  └─ if BtechRelayPacket.CotFragment:
        ↓
      PacketFragmenter reassembles full CoT XML
        ↓
      CotBridge injects CoT
        ↓
      ATAK processes it (marker/shape/etc)
```

**Loop prevention (inbound injection)**:
- When the plugin injects inbound CoT, ATAK may later attempt to send it out again (depending on hooks).
- `CotBridge` maintains a short “do not relay outbound” window keyed by injected CoT UID to suppress immediate echo loops.

### B) Outbound ATAK GeoChat → RF (to a plugin contact)

Goal: when user chats with `ANDROID-<CALLSIGN>` contact in native ATAK UI, radio should key and transmit.

```
User types message in ATAK chat UI
  ↓
ATAK decides destination connector(s)
  ↓
For plugin contacts: ChatManager creates Intent whose action == connector "connection string"
  ↓
Plugin registers BroadcastReceiver for ACTION_PLUGIN_CONTACT_GEOCHAT_SEND
  ↓
ChatBridge.handleOutgoingChat / relayPluginGeoChatMessageBundle
  ↓
BtechRelayPacket.createChatPacket (assigns messageId)
  ↓
EncryptionManager (optional)
  ↓
PacketFragmenter (if needed)
  ↓
AX.25 frame(s)
  ↓
KISS encoder
  ↓
BtConnectionManager write bytes → radio
  ↓
RF
```

Important detail: **the connector action matters**.
- ATAK uses `new Intent(connector.getConnectionString())` for plugin contacts.
- Therefore the plugin must register its connector with a connection string that is a **broadcast action** it listens for (currently `com.btechrelay.plugin.action.PLUGIN_CONTACT_GEOCHAT_SEND`).

### C) Outbound contact-targeted CoT → RF

`CotBridge` registers a `PreSendProcessor` with ATAK's `CommsMapComponent`. When ATAK dispatches a CoT event with a `toUIDs` list, the processor checks if any recipient is a known BTECH radio contact (fast path: `btechContactUids` set; fallback: `Contacts.getContactByUuid` + `PluginConnector` check). If matched, the CoT is gzip-compressed and handed to `PacketFragmenter` for RF transmission. Events exceeding 4 KB compressed are dropped with a warning. An inbound-inject skip set prevents echo loops.

## Contacts model (why `ANDROID-` UIDs exist)

ATAK uses `ANDROID-<something>` UIDs for contacts/devices. To make radio peers behave like “real” ATAK contacts (sendable, chat-able), the plugin:

- Creates/registers contacts with **`ANDROID-<CALLSIGN>`** style UIDs.
- Registers aliases so that radio-truncated callsigns (e.g., 3-char) still resolve to the canonical contact UID.

**Alias mapping** matters because AX.25 payloads and radio UI often truncate callsigns; without aliases you’ll get duplicate contacts and split chat threads.

## GeoChat / threading rules (non-obvious)

ATAK’s GeoChat threading and display logic is sensitive to several fields inside the GeoChat CoT. The plugin must build inbound chat CoT carefully so ATAK:
- creates the correct thread,
- displays callsign without `ANDROID-` prefix,
- and does not deduplicate/overwrite messages.

Key principles implemented in `CotBuilder.buildChatCot(...)` and `CotBridge.injectChatCot(...)`:

- **Message uniqueness**: ATAK can deduplicate based on IDs; inbound messages must have a collision-resistant unique suffix in their CoT UID.
- **DM threading**: for DMs, the conversation ID must match what ATAK uses when user opens chat with that contact (often the peer’s `ANDROID-*` UID).
- **Display name**: `__chat chatroom` should be a human callsign (e.g. `VETTE`), not `ANDROID-VETTE`, or the UI title can look wrong.
- **Local device UID**: some DM fields should use the **local** device UID (not the peer) or ATAK parsing can mis-thread.
- **Thread id from RF destination**: if the RF destination “looks like self”, the plugin must force the conversation/thread id to the sender peer, not the destination callsign string.

## Unread badge integration (contacts icon red dot)

ATAK can query a connector feature `NotificationCount` from contacts/connectors. The plugin uses:

- `BtechRelayContactHandler.getFeature(NotificationCount)` to return unread count for the plugin connector address only.
- A deduplicating unread key set per UID so each inbound message increments once.
- A set of listeners to clear unread when ATAK considers the conversation read, including “chat window already open” cases where ATAK doesn’t fire a simple mark-read broadcast.

Practical takeaway: badge behavior involves multiple hooks (broadcasts, contact change listeners, visibility polling). If this breaks, focus on `ChatBridge` + `BtechRelayContactHandler`.

## BLE / AX.25 / packet formats (what goes over the wire)

### Transport stack
- **Bluetooth SPP**: raw serial-like link between Android and radio.
- **KISS**: framing protocol to carry AX.25 over serial.
- **AX.25**: amateur packet framing used for RF packet radio.
- **BtechRelayPacket**: plugin’s compact binary payload inside AX.25 info field (plus optional APRS parser path for standard packets).

### Packet types (conceptually)
- **Chat packet**: includes sender callsign, destination/thread id, message text, and a `messageId`.
- **GPS/PLI packet**: includes lat/lon/alt/speed/course and callsign.
- **CoT fragment packet**: carries chunks of a full CoT XML blob, reassembled on receive.
- **Ping/keepalive**: lightweight presence/hello.

### Encryption
If enabled, payload is encrypted (AES-256-CBC derived from passphrase). All nodes must share the same passphrase; failures drop packets.

## Known issues / design decisions (as of this handoff)

### GeoChat delivery receipts (delivered + read checkmarks)

Inbound `TYPE_CHAT` RF packets trigger two `TYPE_ACK` packets back to the sender:

- **ACK_KIND_DELIVERED** — sent immediately from `PacketRouter` when the chat packet is processed (before ATAK even stores the message).
- **ACK_KIND_READ** — sent when the recipient user opens the conversation. The wire `messageId` is stored per conversation UID in `ChatBridge.pendingReadAcksByConversation` during `injectRadioMessage`. When `clearUnreadLocal(conversationId)` fires (triggered by the contacts-change listener or conversation-open detection), all pending wire mids for that conversation are drained and transmitted as `ACK_KIND_READ`.

On the **sender** side, received ACKs are handled in `ChatBridge`: `outboundWireMidToLocalLineUid` maps wire mid → GeoChat line UID; the receipt CoT is built by `CotBuilder.buildGeoChatReceiptCot` and injected via `CotBridge.injectGeoChatReceipt`. ATAK's `GeoChatService` looks up the message by the **bare UUID suffix** of the GeoChat line UID (not the full `GeoChat.*` string) — the CoT UID must match this or the DB lookup returns null and the checkmark never appears.

Key gotcha: `com.atakmap.chat.markmessageread` is **not** reliably broadcast by ATAK just from opening a conversation — do not rely on it as the primary read-trigger.

### Team color semantics (fixed)
Outbound GPS beacons embed the sender’s ATAK **locationTeam** (same string as native SA). Inbound position CoT uses that value for **`detail/__group`**, so map markers match native networked contacts. The Contacts pane lists **`IndividualContact` linked to the CoT `MapItem`** (`PacketRouter.linkRadioIndividualContactToMapMarker`) so list/tint behavior matches ATAK’s native contacts UI. Older peers that omit the team extension still default missing team to **Cyan** in CoT (not the receiver’s team).

### Timing: ATAK initialization
Some ATAK singletons (e.g., `ChatManagerMapComponent`) may not be ready when the plugin is created. Where necessary, code retries registration after startup.

## “Hello world” test flows (what to run to prove it works)

### Minimal end-to-end field test (two phones, Wi‑Fi off)

1. Pair each phone to its radio, connect plugin (green dot).
2. Ensure both are on RF.
3. From VETTE: open Contacts → select `ANDROID-JUNIOR` (radio contact) → chat → send “hello”.
4. On JUNIOR: verify message appears in native chat UI and badge clears when read.
5. From VETTE: long-press a waypoint → Send → select JUNIOR → confirm the radio keys and JUNIOR's map updates.
6. From VETTE: draw a short route, send to JUNIOR — verify RF relay and map update on the receiving end.

## Where to look first (debugging map)

### If outbound chat doesn’t key radio
- `ChatBridge` receiver for `ACTION_PLUGIN_CONTACT_GEOCHAT_SEND`
- `PluginConnector` connection string configuration (must match receiver action)
- `PacketRouter` not involved; this is outbound path

### If inbound chat appears but threads incorrectly / wrong title
- `CotBridge.injectChatCot` and `CotBuilder.buildChatCot`
- DM logic for `__chat` and `chatgrp` attributes
- local UID caching for GeoChat fields

### If unread badge count is wrong / doesn’t clear
- `BtechRelayContactHandler` unread keying + `NotificationCount`
- `ChatBridge` listeners for mark-read / open-chat / fragment visibility polling
- ensure `Contacts.getInstance().updateTotalUnreadCount()` is called on clear

### If unintended CoT re-transmit / echo appears when extending `CotBridge`
- Loop-suppression map keyed by injected UIDs (`CotBridge`)
- Ensure outbound hook skips events that originated from inbound radio injection (`ANDROID-*`, recently injected UID window)

## Key files (jump list)

- Wiring/lifecycle: `app/src/main/java/com/btechrelay/plugin/BtechRelayMapComponent.java`
- UI dropdown: `app/src/main/java/com/btechrelay/plugin/BtechRelayDropDownReceiver.java`
- Contacts/unread: `app/src/main/java/com/btechrelay/plugin/BtechRelayContactHandler.java`
- Outbound/inbound chat: `app/src/main/java/com/btechrelay/plugin/chat/ChatBridge.java`
- CoT bridge/builder: `app/src/main/java/com/btechrelay/plugin/cot/CotBridge.java`, `app/src/main/java/com/btechrelay/plugin/cot/CotBuilder.java`
- Packet routing: `app/src/main/java/com/btechrelay/plugin/protocol/PacketRouter.java`, `app/src/main/java/com/btechrelay/plugin/protocol/BtechRelayPacket.java`
- Fragmentation: `app/src/main/java/com/btechrelay/plugin/protocol/PacketFragmenter.java`
- BLE: `app/src/main/java/com/btechrelay/plugin/bluetooth/BtConnectionManager.java`

