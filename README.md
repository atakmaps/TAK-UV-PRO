# BTECH Relay — Open-Source BTECH Radio ↔ ATAK Bridge Plugin

A free, open-source ATAK plugin that connects BTECH radios to the Android Team Awareness Kit (ATAK) over Bluetooth. Team members with radios can share positions, chat, and situational awareness data entirely off-grid — no cell service or internet required.

## Features

| Feature | Status | Description |
|---------|--------|-------------|
| **Position Sharing (PLI)** | ✅ Working | Your ATAK position is beaconed over radio at a configurable interval. Incoming positions appear as contacts on the map. |
| **GeoChat over RF (contact-centric)** | ✅ Working | Chat to radio peers using ATAK’s native Contacts/GeoChat UI (plugin contacts route via RF transport). |
| **GeoChat delivery receipts (checkmarks)** | ✅ Working | ATAK’s native single-checkmark (delivered) and double-checkmark (read) appear on the sender’s chat window. The receiving device sends a delivered ACK when the message arrives over RF, and a read ACK when the user opens the conversation. |
| **Retry on no ACK + delivery failure alert** | ✅ Working | If the sender receives no delivered ACK within the configured interval (default 2 min), the message is retransmitted up to the configured maximum (default 3 attempts). If all retries are exhausted, a persistent alert dialog appears on the sender’s screen and must be acknowledged by tapping OK before it dismisses. Retry interval and max retries are adjustable in plugin Settings. |
| **Contact-targeted CoT over RF (waypoints, routes, casevac, etc.)** | ✅ Working | Any CoT item sendable to a contact in ATAK — waypoints, routes, casevac/9-line, drawings, enemy/friendly markers — is intercepted, compressed, and relayed over RF to the target radio contact. Items exceeding 4 KB compressed are skipped with a log warning. |
| **SA Relay (opt-in)** | ✅ Working | When enabled in Settings, any received network CoT (team positions, waypoints, routes) is automatically broadcast over RF to all radio users on frequency. Per-UID throttle (30 s) prevents channel flooding. Enable only when directed by a team leader. |
| **AES-256 Encryption** | ✅ Working | Optional passphrase-based AES-256-CBC encryption for all radio traffic. All nodes must share the same passphrase. |
| **Contact Tracking** | ✅ Working | Radios in range are tracked as contacts with callsign, last-seen time, and position. Contacts that go silent are aged out. |
| **Bluetooth Auto-Reconnect** | ✅ Working | Three-strategy SPP connection with exponential backoff reconnect (up to 5 attempts). |
| **Send Ping** | ✅ Working | Lightweight keepalive — lets other nodes know you're active even without GPS. |

## How It Works

```
┌─────────────────────────────────────┐
│           ATAK Application          │
│  ┌───────────────────────────────┐  │
│  │      BTECH Relay Plugin        │  │
│  │                                │  │
│  │  Bluetooth ─► KISS TNC ─►    │  │
│  │  AX.25 frames ─► Packet      │  │
│  │  Router ─► CoT / Chat /      │  │
│  │  GPS / Encryption             │  │
│  └───────────────────────────────┘  │
└──────────────┬──────────────────────┘
               │ Bluetooth SPP (Data)
               ▼
┌─────────────────────────────────────┐
│       BTECH Radio (KISS TNC)        │
└──────────────┬──────────────────────┘
               │ RF (VHF/UHF)
               ▼
┌─────────────────────────────────────┐
│     Other Radios + EUDs in Range    │
└─────────────────────────────────────┘
```

The plugin talks to the radio over Bluetooth SPP using the KISS TNC protocol. Data is encapsulated in AX.25 frames with a compact binary format. On the ATAK side, incoming packets become CoT events, map contacts, and chat messages; outgoing ATAK data is serialized, optionally encrypted, and transmitted over radio.

## Requirements

### To Use the Plugin
- **ATAK-CIV 5.5.1** (or compatible version) installed on your Android device
- **BTECH UV-PRO** radio (UV-PRO, GMRS-PRO, or UV-50X series with KISS TNC support)
- **Bluetooth** pairing between the Android device and radio

### To Build from Source
- **JDK 17** — [Eclipse Temurin](https://adoptium.net/) recommended. Other JDK 17 distributions work too.
- **Android SDK** with API level 35 — install via [Android Studio](https://developer.android.com/studio) or the [command-line tools](https://developer.android.com/studio#command-line-tools-only)
- **ATAK-CIV 5.5.1 SDK** — available from the [atak-civ GitHub repo](https://github.com/TAK-Product-Center/atak-civ)
- **Git** — to clone the repo

> **Note:** You do _not_ need to install Gradle. The included Gradle wrapper (`gradlew` / `gradlew.bat`) downloads the correct version automatically.

## Quick Install (Pre-built APK)

If you just want to install the plugin without building it:

1. Download the latest APK from the [Releases](../../releases) page.
2. Transfer it to your Android device.
3. Install with: `adb install -r BTECHRelay-*.apk`
4. Open ATAK → Menu → Tools → **BTECH Relay**.

## Building from Source

### 1. Download the ATAK-CIV SDK

Go to the [atak-civ GitHub repo](https://github.com/TAK-Product-Center/atak-civ), download the **ATAK-CIV 5.5.1 SDK**, and extract these files into `app/libs/atak-civ/`:

```
app/libs/atak-civ/
├── main.jar
├── atak-gradle-takdev.jar
├── android_keystore
└── proguard-release-keep.txt
```

> The SDK zip contains more files — you only need these four.

### 2. Configure `gradle.properties`

The build needs to know where JDK 17 is. A template is included:

```bash
cp gradle.properties.example gradle.properties
```

Open `gradle.properties` and update the `org.gradle.java.home` path to match your JDK 17 installation:

| OS | Typical JDK 17 Path |
|----|---------------------|
| **Windows** | `C:\\Program Files\\Eclipse Adoptium\\jdk-17.x.x-hotspot` |
| **macOS** | `/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home` |
| **Linux** | `/usr/lib/jvm/temurin-17-jdk-amd64` |

> **Tip:** If your system `JAVA_HOME` already points to JDK 17, you can delete the `org.gradle.java.home` line entirely.

### 3. Build the APK

```bash
# Clone the repo
git clone https://github.com/atakmaps/BTECH-Relay.git
cd BTECH-Relay

# Linux/macOS
./gradlew assembleCivDebug

# Windows (Command Prompt or PowerShell)
gradlew.bat assembleCivDebug
```

The APK will be at:
```
app/build/outputs/apk/civ/debug/ATAK-Plugin-BTECHRelay-*.apk
```

### 4. Install

```bash
adb install -r app/build/outputs/apk/civ/debug/ATAK-Plugin-BTECHRelay-*.apk
```

Then open ATAK → Menu → Tools → **BTECH Relay**.

### Troubleshooting

| Problem | Fix |
|---------|-----|
| `Android Gradle plugin requires Java 17` | Your `gradle.properties` is missing or `org.gradle.java.home` points to the wrong JDK. See step 2. |
| `Could not find main.jar` | The ATAK SDK files aren't in `app/libs/atak-civ/`. See step 1. |
| `AAPT: error: resource not found` | Run `./gradlew clean` and rebuild. |
| Build succeeds but plugin doesn't appear in ATAK | Make sure you're running **ATAK-CIV 5.5.1** — the plugin is compiled against this specific version. |

## Usage

1. **Pair your radio** with your Android device via Bluetooth settings.
2. Open the **BTECH Relay** plugin in ATAK.
3. Tap **Scan** to find your radio, then tap it to connect.
4. The status dot turns green when connected.

### Plugin Controls

| Control | What It Does |
|---------|-------------|
| **AES-256 Switch** | Enable encryption (set a passphrase first) |
| **Send Beacon** | Immediately broadcast your current position |
| **Send Ping** | Send a lightweight keepalive with your callsign |
| **Settings** | Configure beacon interval, SA Relay, and other plugin options (team color is controlled by ATAK core settings) |

### Contact-centric routing (important)

Radio peers are represented as **native ATAK Contacts** (UIDs look like `ANDROID-<CALLSIGN>`). Use the ATAK Contacts UI to:

- open GeoChat with a radio contact (messages route over RF via the plugin).

Waypoints, routes, casevac/9-line, drawings, and other CoT items can all be sent to a radio contact using the native ATAK "Send to Contact" UI. The plugin intercepts the outbound CoT, compresses it, fragments it if needed, and transmits it over RF.

### SA Relay

Enable **SA Relay** in Settings to automatically broadcast received network SA (team positions, waypoints, routes) over RF to all radio users on frequency. This is designed for a single designated relay node — **do not enable unless instructed by your team leader.** A per-contact 30-second throttle prevents channel flooding.

For deeper implementation details and a full “new agent” handoff (logic trees, key files, known ATAK gotchas), see `HANDOFF.md`.

### Encryption

When enabled, all outgoing packets are encrypted with AES-256-CBC using a key derived from your passphrase (PBKDF2). **All radios in your group must use the same passphrase.** If a packet fails to decrypt on the receiving end, it is silently dropped.

## Project Structure

```
app/src/main/java/com/btechrelay/plugin/
├── BtechRelayLifecycle.java       # Plugin entry point
├── BtechRelayTool.java            # Tool registration
├── BtechRelayMapComponent.java    # Core component — wires everything together
├── BtechRelayDropDownReceiver.java # UI panel
├── bluetooth/
│   └── BtConnectionManager.java  # Bluetooth SPP + KISS TNC connection
├── kiss/
│   ├── KissConstants.java        # KISS protocol constants
│   ├── KissFrameEncoder.java     # Encode AX.25 → KISS frames
│   └── KissFrameDecoder.java     # Decode KISS frames → AX.25
├── ax25/
│   ├── Ax25Frame.java            # AX.25 frame builder/parser
│   └── AprsParser.java           # APRS position parser
├── protocol/
│   ├── BtechRelayPacket.java      # Binary packet format
│   ├── PacketRouter.java         # Routes incoming packets to subsystems
│   └── PacketFragmenter.java     # Fragment/reassemble large packets
├── cot/
│   ├── CotBridge.java            # CoT ↔ radio relay
│   └── CotBuilder.java           # Build CoT events from radio data
├── chat/
│   └── ChatBridge.java           # GeoChat ↔ radio relay
├── crypto/
│   └── EncryptionManager.java    # AES-256-CBC encryption
├── contacts/
│   ├── ContactTracker.java       # Track radios in range
│   └── RadioContact.java         # Contact data model
├── voice/
│   └── (legacy PTT scaffolding; not shipped as a feature in this fork)
└── ui/
    └── SettingsFragment.java     # Preference constants and helpers
```

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions and guidelines.

## License

MIT + Commons Clause — free to use, modify, and distribute, but commercial sale rights are reserved. See [LICENSE](LICENSE).
