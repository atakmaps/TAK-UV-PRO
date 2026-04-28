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

Two files must exist at the repo root (both are gitignored — do not commit them):

**`local.properties`**
```
sdk.dir=/opt/android-sdk
```

**`gradle.properties`**
```
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64
```

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

### Notes

- The Gradle wrapper downloads Gradle 8.13 automatically on first run — no separate Gradle install needed.
- JDK 21 is the system default; Gradle must be pointed at JDK 17 via `gradle.properties` (`org.gradle.java.home`) or the build will fail with "Android Gradle plugin requires Java 17".
- `local.properties` and `gradle.properties` are gitignored. They must be re-created after each fresh VM provision.
- The ATAK keystore (`android_keystore`) bundled with the SDK uses well-known public credentials (`tnttnt`/`wintec_mapping`) — these are safe for debug/dev use but not for production releases.
