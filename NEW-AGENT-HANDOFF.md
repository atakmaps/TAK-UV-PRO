# Handoff: new agent / developer (BTECH Relay)

Use this when spinning up a **new AI agent** or on-boarding a developer so they do not re-discover pipeline, crypto, and SA Relay pitfalls.

---

## 1. Repo and product

- **GitHub:** `atakmaps/BTECH-Relay` (or your canonical remote).
- **Plugin version:** `PLUGIN_VERSION` in root `build.gradle`; **ATAK target** via `ATAK_VERSION` in the same file.
- **ATAK flavor:** ATAK-CIV **5.5.1** is the stated target; for Android Studio attach-debug, the process is usually **`com.atakmap.app.civ`** (not always `com.atakmap.app`).
- **Where the plugin appears in ATAK:** Menu → Tools → **BTECH Relay**.

---

## 2. Local dev toolchain (not in repo)

Read **`AGENTS.md`** — it lists JDK 17, Android SDK paths, and that the **ATAK SDK** must be installed under **`app/libs/atak-civ/`** (`main.jar`, `atak-gradle-takdev.jar`, keystore, proguard keep text). That tree is **gitignored**.

Also require **`local.properties`** and **`gradle.properties`** at repo root (gitignored): `sdk.dir`, `org.gradle.java.home`, AndroidX flags, etc.

---

## 3. Official TAK pipeline (“make it pass”)

- **Source zip:** Do **not** bundle `app/libs/atak-civ/`. The builder pulls the ATAK API from **takrepo**. **`app/build.gradle`** only applies `compileOnly files(main.jar)` **when that file exists**, so CI does not need a local `main.jar`.
- **Fortify / SCA:** Treat the latest **workbook PDF** as source of truth after each run. Crypto was moved to **AES-GCM + high-iteration PBKDF2** with reduced sensitive logging and buffer handling; new findings may still appear.
- **OWASP Dependency-Check:** May flag **transitive** dependencies. Confirm whether those coordinates are actually **in the shipped APK** before chasing CVEs.
- **Network:** `artifacts.tak.gov` is often unreachable from a normal home network (VPN / NAC). A **failed curl / malformed XML** pre-check is **not automatically** a defect in this repo.
- **Repro artifacts:** Keep the **pipeline results zip** (`build.log`, Fortify PDF, dependency HTML) tied to the **exact git SHA** you submitted.

---

## 4. Crypto / interoperability (post–1.5.3)

- Encrypted RF uses **envelope v3 (AES-GCM)** with **PBKDF2-HMAC-SHA256** and **per-payload salt**.
- **All radios on an encrypted net need a compatible plugin build** (see `README.md` / `HANDOFF.md`). Older **AES-CBC** payloads are **not** supported by current GCM logic.

---

## 5. SA Relay (next phase — explicit state)

Avoid vague “implement SA.” Hand off **concrete** intent:

- **Goal (when re-enabled):** Opt-in **SA Relay**: certain **inbound network** CoT (e.g. PLI / points / routes — match the agreed regex / filters) is **re-broadcast over RF**, with **throttling**, **size limits**, and **loop prevention**.
- **Primary hook:** **`CommsLogger.logReceive`** (or the equivalent path in `CotBridge`). It only runs for traffic that actually hits that logger; **mesh vs server** paths differ — if `logReceive` never fires on a device, **relay will never key the radio** (this bit the project during field trials).
- **Code archaeology:** Search `sa_relay`, `SA_RELAY`, `maybeSaRelay`, `PREF_SA_RELAY`. Logic may exist while **UI / preferences** were **turned off** during a deferred phase — read **`CotBridge`**, **`SettingsFragment`**, **`BtechRelayDropDownReceiver`** before re-adding UI.
- **Product requirements to preserve:** Operator warning (**e.g. do not enable unless instructed by team leader**), **no forced encryption** for training scenarios unless policy changes, **packet size guard**, type filter including **routes (`b-m-r`)** if still in scope.
- **Test plan to specify:** Minimum two devices and one SA path where **`logReceive` is known to fire** (TAK Server vs mesh — pick a **standard** and document it). Verify **PTT / keying**, **no regressions** to native contacts / mesh behavior.

---

## 6. Paste at the start of a new agent session

- **Branch + commit SHA** (or “working tree dirty” + short diff summary).
- **Current goal:** e.g. “pass next TAK submission”, “finish SA Relay UI + validation”, “fix regression X”.
- **Attachments:** Latest **pipeline zip**, link to **GitHub release** or PR, any **device logs** with filters from `AGENTS.md`.
- **Constraints:** e.g. “do not break GCM v3”, “keep contact-targeted CoT over RF working”, “do not commit SDK / secrets”.

---

## 7. Debugging (TAK documentation summary)

Plugins run **inside the ATAK process**. Use **Attach Debugger to Android Process** on **ATAK** (CIV: typically `com.atakmap.app.civ`), then breakpoints in plugin Java apply. Prefer attaching to an already-running ATAK for speed; use **Show all processes** and **Java** debugger if attach misbehaves. More detail in **`AGENTS.md`**.
