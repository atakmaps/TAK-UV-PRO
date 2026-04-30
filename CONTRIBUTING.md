# Contributing to BTECH Relay

Thanks for your interest in contributing! BTECH Relay is a free, open-source ATAK plugin
for off-grid team communication via BTECH radios.

## Building

### Prerequisites

- **JDK 17** (Eclipse Temurin recommended)
- **Android SDK** (API 35)
- **ATAK-CIV 5.5.1 SDK** — download from [TAK Product Center](https://tak.gov) (free registration)

### Setup

1. Clone the repo
2. Extract the ATAK SDK files into `app/libs/atak-civ/` (see README for details)
3. Build: `./gradlew assembleCivDebug`
4. Install the APK alongside ATAK-CIV on your device

### Code Style

- Java 17 language level
- 4-space indentation
- Android `Log.d/i/w/e` with tag prefix `BTRelay`

## Areas Where Help Is Needed

- Bluetooth connection reliability across different Android versions and devices
- Voice PTT implementation and testing with real radio hardware
- UI improvements and dark mode support
- Testing with GMRS-PRO and UV-50X series radios
- Battery usage optimization for long-duration deployments

## Reporting Issues

Use GitHub Issues. Please include:

- Android version and device model
- ATAK-CIV version
- Radio model and firmware version
- Steps to reproduce
- Logcat output filtered by `BTRelay`

## License

MIT + Commons Clause — see [LICENSE](LICENSE)
