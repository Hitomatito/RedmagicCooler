# AGENTS.md — RedmagicCooler

Unofficial Android client for RedMagic/Nubia magnetic coolers over BLE. Single-module `:app` (Kotlin + Jetpack Compose), no backend, no `INTERNET` permission.

## Environment (read first)

- `java` is not on `PATH`; `./gradlew` fails with `JAVA_HOME is not set`. Export before any Gradle command:
  ```bash
  export JAVA_HOME="$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2"   # Temurin 21.0.7
  ```
- Android SDK: `~/Android/Sdk` (wired in `local.properties`, platform `android-37.0`, build-tools `36.0.0`).
- Toolchain: Gradle 9.9.1 (wrapper), AGP 9.5.1, Kotlin 2.4.21 (`gradle/libs.versions.toml`, README badge updated to match). The tracked `gradle/gradle-daemon-jvm.properties` pins the daemon JVM to **JDK 25** (JetBrains, auto-provisioned via the foojay resolver plugin in `settings.gradle.kts`); the exported `JAVA_HOME` above (JDK 21) is still needed for direct `./gradlew` launches. CI sets up JDK 21.
- Dependencies are declared only in `gradle/libs.versions.toml`. `settings.gradle.kts` sets `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, so adding a `repositories {}` block to a module build file fails configuration — add repos centrally in `settings.gradle.kts`.
- `.idea/` is partially tracked (code styles, run configurations, inspection profiles are committed; `caches/`, `workspace.xml` etc. are gitignored). Don't commit IDE noise blindly.
- Android Studio may rewrite `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties` on sync (version upgrades). If those are dirty and you didn’t touch them, don’t commit them blindly. The current wrapper bump keeps `retries=0` and `retryBackOffMs=500`; change those only when intentionally updating Gradle.

## Commands

```bash
./gradlew testDebugUnitTest   # unit tests (matches CI)
./gradlew assembleDebug       # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # -> app/build/outputs/apk/release/
./gradlew lintDebug           # AGP lint; currently clean, not run by CI
# Single test: ./gradlew :app:testDebugUnitTest --tests "com.hitomatito.redmagicooler.ThermalLogicTest"
# Telemetría térmica (solo build debug): adb exec-out run-as com.hitomatito.redmagicooler cat files/thermal/thermal.jsonl
```

- CI (`.github/workflows/ci.yml`) runs `testDebugUnitTest`, `assembleDebug`, `assembleRelease` on JDK 21 with `--no-daemon` (push/PR to `main`, plus `workflow_dispatch`) — the same tasks the README documents. No custom lint/detekt/ktlint/spotless configured — don’t invent those tasks. `gradle.properties` sets `kotlin.code.style=official`.
- Bumping the Android SDK means editing three places in sync: `app/build.gradle.kts` (`compileSdk { version = release(N) }` and `targetSdk`), CI's `sdkmanager` line, and the requirements table in `README.md`.
- App version lives in `app/build.gradle.kts` `defaultConfig`: `versionCode = 9`, `versionName = "1.4.2"` (bump both for `chore: subir versión` commits).
- `assembleRelease` enables R8 minify + resource shrinking (v2/v3/v4 signing when `keystore.properties` exists); keep `app/proguard-rules.pro` WorkManager/line-number rules when adding workers.
- Release signing is conditional on root `keystore.properties` (gitignored, never commit); without it release builds unsigned.
- `--offline` works for `testDebugUnitTest`, `assembleDebug`, `lintDebug` and `assembleRelease` once cached; `assembleRelease` may need network for uncached artifacts.

## Architecture

Package root `com.hitomatito.redmagicooler`. `:app` is the only module.

| Area | Files |
| --- | --- |
| BLE engine, single GATT owner | `ble/CoolerBleManager.kt` |
| Centralized runtime permissions | `BlePermissionManager.kt` |
| Compose UI + navigation | `MainActivity.kt`, `ui/` |
| Auto-mode foreground service (client of BLE engine) | `CoolerService.kt` |
| Thermal sensing + shared process-wide thermal state | `ThermalMonitor.kt` (Android wrapper: role-based zone reads, sensors, loop, lock), `ThermalLogic.kt` (pure Kotlin: weighted fusion of comfort skin/battery, SoC CPU/GPU rescaled, Android thermal status 0-6, charging/screen context, noise-preference curve tuning, EMA/trend, ramp/hysteresis — no Android/clock), `ThermalMonitorManager.kt` (singleton; `CoolerService` initializes it only while auto mode runs — the UI does not keep sensors active; the active profile's `noisePreference` 0=rendimiento/100=silencio flows to `ThermalLogic` via `setNoisePreference`) |
| Per-model thermal zones + debug telemetry | `ThermalZoneMapping.kt` (pure `ThermalZoneRole` classifier/aggregation, defaults for `xiaomi|2107113sg|vili`), `data/ThermalZoneProfileRepository.kt` (load-only per-model overrides from prefs `thermal_zone_profiles` — written by hand for debugging, no UI/save API), `data/ThermalTelemetry.kt` (JSONL in `filesDir/thermal/`, debug-only, 1 MiB rotation) |
| Profile persistence (SharedPreferences + JSON, `StateFlow`) | `data/ProfileRepository.kt` (singleton; manual per-field `profileToJson`/`jsonToProfile` — new `CoolerProfile` fields must be added in **both** or they are silently lost across save/load) |
| Global prefs / first-run gate | `data/AppPreferences.kt` (`app_prefs` / `onboarding_done`) |
| Scanner/connection helpers | `utils/BleConnectionHelper.kt` |
| Supported cooler models (manufacturer subtype + drawable) | `CoolerDeviceType.kt` (**package root, not `model/`**) |
| BLE UUIDs, `RGBConfig`, `LightEffect` | `model/CoolerModels.kt` (`CoolerBleConstants` lives here) |
| Profile model | `model/CoolerProfile.kt` |
| Device DTO/list | `CoolerDevice.kt` |

**`CoolerBleManager` is the single BLE GATT owner.** `MainActivity` and `CoolerService` are clients: they observe `CoolerBleManager.state`/`events` and issue commands through its public API (`startScan`, `connect`/`connectByMac`, `setFanSpeed`, `setRGBLight`, `setAutoMode`, `readFanSpeed`, `applyPendingRGB`, `requestReconnect` — `enqueueOp` itself is private). Do not create a second `BluetoothGatt` in Activity/Service; extend the manager’s op queue instead.

Automatic mode state is coordinated through `CoolerService` intents (`ACTION_START_AUTO`, `ACTION_STOP_AUTO`, `ACTION_SWITCH_TO_MANUAL`, `ACTION_SET_DEVICE_TYPE`, `ACTION_USE_CONNECTED_DEVICE`) and the selected profile in `ProfileRepository`. There is no static service handle (`getInstance()`/`instance` were removed as dead code): MainActivity drives RGB straight through `CoolerBleManager` even in auto mode. No separate persisted active-profile id is kept (the legacy `active_profile` key is actively removed on startup). Manual-mode UI stays available while disconnected, but is disabled when the profile’s auto mode is on.

A duplicated profile is created with a **blank `macAddress`** and binds to the real MAC on first connect (`ProfileRepository.updateMacAddress`); auto mode is refused until then (`MainActivity` shows a toast). `AppPreferences.isOnboardingDone` gates the permission-onboarding route at startup.

## BLE protocol facts (reverse-engineered, no public spec)

- Fan service `d52082ad-e805-9f97-9d4e-1c682d9c9ce6`; characteristics (names verified against the official app `cn.nubia.externdevice` V3.4.0, decompiled Nov 2026): `0x1011` (hall/magnet sensor; commands `01`/`02`/`03`), `0x1012` (fan speed, raw 40–80), `0x1013` (RGB), `0x1014` (**cooler temperature** per the official app's `queryTemperature` = plain `readCharacteristic(0x1014)`), `0x1015` (NOT a temperature notify — it is a **multiplexed status frame channel**: payload `[mode][value...]`, mode 1=hall, 2=fan, 3=light, **4=temperature**, 5=TEC output load; the legacy "temperature notify" attribution was the official app's own write-echo traffic), `0x1016` (logger, ASCII), `0x1018` (auto channel: the official app's `writeAutoOn` and `writeAutoOff` both write `00`, so it is not a mode toggle; arbitrary bytes = phone-temp payload; write `01` = enable variant).
- The cooler does not push `0x1015`/`0x1014` notifications just because the CCCD is written; the official app subscribes `0x1015` (STATUS task) and reads `0x1014` on demand. This engine only subscribes to `0x1015` and logs `data[0]` as `BleEvent.TemperatureRaw`; it does not poll the cooler.
- **Cooler temperature is currently not used.** Probes on a real Heat Sink 5 Pro did not produce a usable reading (candidate zones returned static payloads and no `0x1015` notifications arrived), and raw→°C is uncalibrated. The UI and auto mode rely on the phone's `ThermalMonitor`.
- All official-app writes use `WRITE_TYPE_NO_RESPONSE`.
- **Official auto mode (the manufacturer's own controller, shared curve across jacket gens 3–6):** read phone battery temp (tenths °C) → piecewise lerp to raw 40–80 → write single byte to `0x1012`, no smoothing/ramps: `<33 °C→40`, `33–35→40..50`, `35–38→50..60`, `38–40→60..70`, `40–40.5→70..80`, `>40.5→80`. The foreground service `JacketTempControlService` just forwards battery-temp broadcasts to this curve.
- Detection accepts advertising service `00004a41-...` **or** manufacturer data (company ID `2250` / `0x08CA`, byte `[0] = 5` fan, byte `[1] = generation subtype`). Several models share subtypes (4, 5 and 6 — e.g. `JACKET_6` advertises `[4, 6]`), so BLE-name matching (`matchesBleName`) disambiguates: subType 5 without "Pro" in the BLE name falls to `JACKET_5_LITE` (display name **"Heat Sink 5"**, renamed from "Heat Sink 5 Lite"); other name ties take the lowest generation. Keep `CoolerDeviceType.fromScanRecord` as the source of truth when adding models.
- RGB command = 4 bytes: `[effectCode, r, g, b]`. Effect codes: `0x01` colorful, `0x02` full-color breath, `0x03` single-color breath, `0x04` always bright.
- `status == 22` (`GATT_CONN_TERMINATE_LOCAL_HOST`) is a known cooler firmware limitation, not an app bug. The engine auto-reconnects with backoff and `discoverServices()` has a watchdog that forces a retry/reconnect.
- GATT lifecycle rules that matter: always `disconnect()` **then** `close()`; closing before the stack confirms the disconnect breaks the next service discovery. Use `BleConnectionHelper.safeCloseGatt` / `safeStopScan`.
- One commander at a time: before `connect`/`connectByMac`/`requestReconnect`, callers must skip the call when `CoolerBleManager.isBusyWith(mac)` is true. Reconnecting while the link is up but `discoverServices()` is still pending tears down the GATT and surfaces as `Servicios descubiertos: 0`.
- Both discovery failure paths (`onServicesDiscovered` with a non-success status, and a missing fan characteristic) must `scheduleReconnect()`; closing the GATT there without a retry leaves auto mode dead until the cooler is power-cycled.
- `CoolerBleManager` queues GATT operations one at a time; Android drops bursted writes. Preserve the op queue when adding characteristics.

### Fan-speed range

The live mapping is `CoolerBleConstants.percentageToRaw` / `rawToPercentage` → raw **40–80**. The README documents the same range; don't widen it to 40–200.

## Conventions

- **UI strings are hardcoded Spanish** — `res/values/strings.xml` contains only `app_name`, and there is zero `stringResource` usage in the codebase. Inline Spanish literals are the existing convention; don’t route new strings through `strings.xml` unless asked to localize.
- Code comments, KDoc and log messages are in **Spanish**; the README is in **English**. Match the file you edit.
- Compose animations use `rememberReduceMotion()` from `ui/MotionUtils.kt` to respect the system reduced-motion setting; follow it for new animations.
- Commit messages are Conventional Commits, usually **Spanish** subjects: `feat(ui): ...`, `fix(ble): ...`, `chore: subir versión a 1.4.0`, `docs: ...`. Branches are usually `origin/<issue-number>-<spanish-description>`; PRs target `main`.
- `settings.gradle.kts` has `rootProject.name = "RedmagiCooler"` (missing "c") while the application id, package and app label are `RedmagicCooler` / `redmagicooler`. Don’t rename casually — it changes the Gradle module name.
- `CoolerService.DEBUG` follows `BuildConfig.DEBUG` (verbose in debug, off in release); R8 also strips `Log.d`/`Log.i` in release via `app/proguard-rules.pro`. No manual flip needed.

## Testing reality

- Real coverage exists only for thermal logic: `ThermalLogicTest.kt` and `ThermalZoneMappingTest.kt` (pure JVM — no Android classes, no new deps). Everything else is still the AS templates (`ExampleUnitTest`, `ExampleInstrumentedTest`). No mocking library is wired up (`testImplementation` is plain JUnit4 only — no MockK, no Robolectric, no Turbine); adding Android-dependent tests means adding deps to `gradle/libs.versions.toml`.
- Code that touches Android framework classes (`Context`, `BluetoothGatt`, `SensorManager`) is untestable as written; extracting pure logic (as done with `ThermalLogic.kt`) is the established path to coverage.

## Do not

- Add an `INTERNET` permission — the app is local-first by design and the README advertises it.
- Add a `strings.xml` extraction or i18n layer without being asked.
- Enable `SCHEDULE_EXACT_ALARM` requests at runtime: the permission is declared but intentionally filtered out of the runtime prompt list; reconnect alarms fall back to inexact alarms when `canScheduleExactAlarms()` is false.
- Assume `FanAdjustmentWorker` is wired up — it is scaffolding that only logs; scheduled automation is unfinished by design.
- Packaging: debug symbol stripping cannot process `libandroidx.graphics.path.so`; it is intentional kept in `app/build.gradle.kts` (`packaging.jniLibs.keepDebugSymbols`). Don’t remove that keep rule.
