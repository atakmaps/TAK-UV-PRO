# AGENTS.md

## Project overview

BTECH Relay is an Android ATAK plugin (APK) that bridges BTECH radios to ATAK over Bluetooth SPP / KISS TNC.
It is built with the Android Gradle Plugin and the ATAK-CIV 5.5.1 SDK.

## Cursor Cloud specific instructions

### Toolchain requirements

| Tool | Required version | Installed path |
|------|-----------------|----------------|
| JDK  | 17              | `/usr/lib/jvm/java-17-openjdk-amd64` |
| Android SDK | API 35, build-tools 35.0.0 | `/opt/android-sdk` |
| ATAK-CIV SDK | 5.5.1.x | `app/libs/atak-civ/` (gitignored) |

These are **not** installed by the update script (they are system/VM-level one-time installs).

### ATAK SDK

The ATAK-CIV SDK is proprietary and **not in the repo** (gitignored). It must be provided externally each time a fresh VM is provisioned. The four required files go into `app/libs/atak-civ/`:

- `main.jar`
- `atak-gradle-takdev.jar`
- `android_keystore`
- `proguard-release-keep.txt`

Obtain the SDK from a trusted source and place/extract the files there before building.

### Local config files (gitignored)

**`local.properties`** must exist at the repo root (gitignored) with at least the Android SDK location:

**`local.properties`**
```
sdk.dir=/opt/android-sdk
```

**`gradle.properties`** is **committed** in this repo (shared `org.gradle.jvmargs`, `android.useAndroidX`). If Gradle does not use JDK 17, add a machine-specific line such as `org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64` to that file or set **`JAVA_HOME`** to JDK 17. (Do not commit secrets; JDK paths are fine to keep local-only by reverting that line before a push, or use `gradle.properties.example` as a reference merge.)

### Environment variables needed for Gradle commands

```bash
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
```

### Build

```bash
# Fast local build (civDebug only)
./gradlew assembleCivDebug -PlocalDev=true

# Full build (all variants — slower)
./gradlew assembleCivDebug
```

APK output: `app/build/outputs/apk/civ/debug/ATAK-Plugin-BTECHRelay-*.apk`

### Lint

```bash
./gradlew lintCivDebug
```

### Tests

There are no unit or instrumentation tests in this repo currently. The only automated checks are lint and the Gradle build itself.

### Install on device

```bash
adb install -r app/build/outputs/apk/civ/debug/ATAK-Plugin-BTECHRelay-*.apk
```

Then open ATAK → Menu → Tools → **BTECH Relay**.

### Device debugging (ADB)

**Serial numbers differ on every phone.** Replace `SERIAL` below with your device id from `adb devices` (the hex string in the left column).

List connected devices:

```bash
adb devices
```

Target one device when multiple phones are plugged in (`-s SERIAL`):

```bash
adb -s SERIAL install -r app/build/outputs/apk/civ/debug/ATAK-Plugin-BTECHRelay-*.apk
```

Restart ATAK only:

```bash
adb -s SERIAL shell am force-stop com.atakmap.app.civ
adb -s SERIAL shell monkey -p com.atakmap.app.civ 1
```

### Plugin debugging (Android Studio)

Plugins (including BTECH Relay) run **inside the ATAK process**, not as a separate app. To hit breakpoints in plugin Java sources:

1. Install the debug plugin APK and start ATAK on the device or emulator as usual.
2. In Android Studio, use **Run → Attach Debugger to Android Process** (or start ATAK via a Studio run config if you have one).
3. Select the **ATAK** process — for **ATAK-CIV** this is normally **`com.atakmap.app.civ`** (use `pm list packages | grep atakmap` if unsure). Official docs sometimes list `com.atakmap.app`; pick whatever matches your build.
4. Enable **Show all processes** if ATAK does not appear in the list.
5. If attach appears to do nothing, set the debugger type to **Java** (vs JDWP / auto).

**Tips:** Attaching to an already-running ATAK is usually faster than launching ATAK from Studio. Native (JNI) debugging is often easier on an **emulator** than on a physical device.

Reboot the phone:

```bash
adb -s SERIAL reboot
```

### Clearing stale ATAK / chat data (field “reset”)

`pm clear` on ATAK does **not** always remove everything users expect. ATAK also stores data under **`/sdcard/ATAK`**. If old chats or other state persist after `pm clear`, remove that folder (and scoped external app data) after a force-stop.

**Typical full local wipe (ATAK CIV package is `com.atakmap.app.civ` — confirm with `pm list packages | grep atakmap` on your device):**

```bash
adb -s SERIAL shell am force-stop com.atakmap.app.civ
adb -s SERIAL shell pm clear --user 0 com.atakmap.app.civ
adb -s SERIAL shell rm -rf /sdcard/Android/data/com.atakmap.app.civ
adb -s SERIAL shell rm -rf /sdcard/ATAK
```

Optional: clear the **plugin** sandbox only (plugin prefs; does **not** remove ATAK’s GeoChat DB):

```bash
adb -s SERIAL shell pm clear --user 0 com.btechrelay.plugin
```

If `pm clear` seems to do nothing, confirm you are clearing the correct **Android user** (work profiles add extra user ids):

```bash
adb -s SERIAL shell pm list users
```

### Logcat (plugin + chat while testing)

```bash
adb -s SERIAL logcat -v time "*:S" BtechRelay.Router:D BtechRelay.ChatBridge:D BtechRelay.CotBridge:D BTRelay.Handler:I ChatManagerMapComponent:D
```

### Notes

- The Gradle wrapper downloads Gradle 8.13 automatically on first run — no separate Gradle install needed.
- JDK 21 is the system default; Gradle must be pointed at JDK 17 via `gradle.properties` (`org.gradle.java.home`) or the build will fail with "Android Gradle plugin requires Java 17".
- `local.properties` is gitignored. `gradle.properties` is **tracked** with non-secret defaults; you may add `org.gradle.java.home` for your VM.
- The ATAK keystore (`android_keystore`) bundled with the SDK uses well-known public credentials (`tnttnt`/`wintec_mapping`) — these are safe for debug/dev use but not for production releases.
