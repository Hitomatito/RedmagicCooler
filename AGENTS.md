# AGENTS.md — RedmagicCooler

Unofficial Android client for RedMagic/Nubia magnetic coolers over BLE. Single-module `:app` (Kotlin + Jetpack Compose), no backend, no `INTERNET` permission.

## Environment (read first)

- `java` is not on `PATH`; `./gradlew` fails with `JAVA_HOME is not set`. Export before any Gradle command:
  ```bash
  export JAVA_HOME="$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2"   # Temurin 21.0.7
  ```
- Android SDK: `~/Android/Sdk` (wired in `local.properties`, platform `android-37.0`, build-tools `36.0.0`).
- Toolchain: Gradle 9.9.1 (wrapper), AGP 9.5.1, Kotlin 2.4.21 (`gradle/libs.versions.toml`). The tracked `gradle/gradle-daemon-jvm.properties` pins the daemon JVM to **JetBrains Runtime 25** (auto-provisioned via the foojay resolver plugin in `settings.gradle.kts`, cached under `~/.gradle/jdks/`); the exported `JAVA_HOME` above is still needed for direct `./gradlew` launches. CI instead uses Temurin 21 (`.github/workflows/ci.yml`).
- `.idea/` is partially tracked (code styles, run configurations, inspection profiles are committed; `caches/`, `workspace.xml` etc. are gitignored). Don't commit IDE noise blindly.
- Android Studio may rewrite `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties` on sync (version upgrades). If those are dirty and you didn’t touch them, don’t commit them blindly. The current wrapper bump keeps `retries=0` and `retryBackOffMs=500`; change those only when intentionally updating Gradle.

## Commands

```bash
./gradlew testDebugUnitTest   # unit tests (matches CI)
./gradlew assembleDebug       # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # -> app/build/outputs/apk/release/
./gradlew lintDebug           # AGP lint; verified: 0 errors, 3 warnings (UseCompoundDrawables + HardcodedText x2), all in notification_cooler_collapsed.xml; not run by CI
# Single test: ./gradlew :app:testDebugUnitTest --tests "com.hitomatito.redmagicooler.ExampleUnitTest"
```

- CI (`.github/workflows/ci.yml`) runs `testDebugUnitTest`, `assembleDebug`, `assembleRelease` on JDK 21 with `--no-daemon`. No custom lint/detekt/ktlint/spotless configured — don’t invent those tasks. `gradle.properties` sets `kotlin.code.style=official`.
- `assembleRelease` enables R8 minify + resource shrinking; keep `app/proguard-rules.pro` WorkManager/line-number rules when adding workers.
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
| Thermal sensing + shared process-wide thermal state | `ThermalMonitor.kt`, `ThermalMonitorManager.kt` (singleton; `initialize(context)` once, UI + service share it) |
| Profile persistence (SharedPreferences + JSON, `StateFlow`) | `data/ProfileRepository.kt` (singleton) |
| Pure thermal curve/ramp/interval logic (unit-tested) | `ThermalPolicy.kt` (consumed by `ThermalMonitor.kt`, `CoolerService.kt`, `ThermalPolicyTest.kt`) |
| Scanner/connection helpers | `utils/BleConnectionHelper.kt` |
| Supported cooler models (manufacturer subtype + drawable) | `CoolerDeviceType.kt` (**package root, not `model/`**) |
| BLE UUIDs, `RGBConfig`, `LightEffect` | `model/CoolerModels.kt` (`CoolerBleConstants` lives here) |
| Profile model | `model/CoolerProfile.kt` |
| Device DTO/list | `CoolerDevice.kt` |

**`CoolerBleManager` is the single BLE GATT owner.** `MainActivity` and `CoolerService` are clients: they observe `CoolerBleManager.state`/`events` and issue commands (`startScan`, `enqueueOp` via public command functions, `requestReconnect`, `disconnect`, `setFanSpeed`, `setRGBLight`, `setAutoMode`). Do not create a second `BluetoothGatt` in Activity/Service; extend the manager’s op queue instead.

Automatic mode state is coordinated through `CoolerService` intents (`ACTION_START_AUTO`, `ACTION_STOP_AUTO`, `ACTION_SWITCH_TO_MANUAL`) and the selected profile in `ProfileRepository`. The repository is the single source of truth for `isAutoMode`: the service builds/reloads `activeProfile` from it, and it is the only place that persists auto mode (plus the service's own `cooler_service_prefs` `ACTIVE_PROFILE_*` for cold restore, which is validated against the repository on load). `ACTION_STOP_AUTO` is authoritative: it clears the repository flag, prefs and `activeProfile` before `stopSelf()`, and `onDestroy` never touches the repository — an externally killed service therefore no longer "resurrects" or silently clears flags. `START_NOT_STICKY`: no implicit recreation; app-side reconciliation (`verifyAndCleanServiceState()`) clears stale auto flags on next launch if the service died. Manual-mode UI stays available while disconnected, but is disabled when the profile’s auto mode is on.

## BLE protocol facts (reverse-engineered, no public spec)

- Fan service `d52082ad-e805-9f97-9d4e-1c682d9c9ce6`; characteristics `0x1012` (fan speed), `0x1013` (RGB), `0x1015` (temperature notify), `0x1018` (auto mode). Advertising service `00004a41-...`.
- Detection is manufacturer-data first: company ID `2250` (`0x08CA`), byte `[0] = 5` (fan), `[1] = generation subtype`, optionally combined with strict BLE-name matching. Keep `CoolerDeviceType.fromScanRecord` as the source of truth when adding models.
- RGB command = 4 bytes: `[effectCode, r, g, b]`. Effect codes: `0x01` colorful, `0x02` full-color breath, `0x03` single-color breath, `0x04` always bright.
- `status == 22` (`GATT_CONN_TERMINATE_LOCAL_HOST`) is a known cooler firmware limitation, not an app bug. The engine auto-reconnects with exponential backoff (`MAX_RECONNECT_ATTEMPTS = 5`) and `discoverServices()` has a timeout watchdog plus `maxDiscoveryRetries` retries. README documents the same values.
- GATT lifecycle rules that matter: always `disconnect()` **then** `close()`; closing before the stack confirms the disconnect breaks the next service discovery. Use `BleConnectionHelper.safeCloseGatt` / `safeStopScan`.
- `CoolerBleManager` queues GATT operations one at a time; Android drops bursted writes. Preserve the op queue when adding characteristics.
- `CoolerBleManager.disconnect()` stops the scanner (`stopScan()`) in addition to disconnecting/closing the GATT. Desconexiones intencionales no emiten un `ConnectionFailed` falso ni reconectan (`intentionalDisconnect`). Los callbacks de un GATT obsoleto se ignoran por identidad de instancia (write/read/descriptor, `onConnectionStateChange`, `onServicesDiscovered`, `onCharacteristicChanged`), y el timeout de op solo fuerza avance si el GATT sigue siendo el mismo.

### Fan-speed range

The live mapping is `CoolerBleConstants.percentageToRaw` / `rawToPercentage` → raw **40–80**. The README documents the same range; don't widen it to 40–200.

## Conventions

- **UI strings are hardcoded Spanish** — `res/values/strings.xml` contains only `app_name`, and there is zero `stringResource` usage in the codebase. Inline Spanish literals are the existing convention; don’t route new strings through `strings.xml` unless asked to localize.
- Code comments, KDoc and log messages are in **Spanish**; the README is in **English**. Match the file you edit.
- Commit messages are Conventional Commits, usually **Spanish** subjects: `feat(ui): ...`, `fix(ble): ...`, `chore: subir versión a 1.3.0`, `docs: ...`. Recent commits mix in English. Branches are usually `origin/<issue-number>-<spanish-description>`; PRs target `main` (one `feat/<english-slug>` branch exists).
- `settings.gradle.kts` has `rootProject.name = "RedmagiCooler"` (missing "c") while the application id, package and app label are `RedmagicCooler` / `redmagicooler`. Don’t rename casually — it changes the Gradle module name.
- Release version bumps edit both `versionCode` and `versionName` in `app/build.gradle.kts` (currently 7 / 1.4.0); commit subject convention is `chore: subir versión a X.Y.Z`. Release signing enables v2/v3/v4 schemes conditionally on `keystore.properties`.
- `CoolerService.DEBUG = true` gates verbose logging; the comment says flip it to `false` for production. It's a `private const val`, so flip it by editing the file.
- Compose lint rule: the first optional parameter on a `@Composable` should be `modifier: Modifier = Modifier`.

## Testing reality

- Unit tests: AS templates (`ExampleUnitTest`, `ExampleInstrumentedTest`) plus `ThermalPolicyTest` and `CoolerBleConstantsTest` (pure JVM, plain JUnit4 — no MockK, no Robolectric, no Turbine). Add dependencies to `gradle/libs.versions.toml` if you need more.
- `./gradlew testDebugUnitTest` runs them in CI. `ThermalPolicy` (`ThermalPolicy.kt`) holds the pure thermal curve/ramp/interval logic — put new thermal logic there, not in `ThermalMonitor`, so it stays testable. `getCurrentThermalData()` is read-only (no ramp side effects); `startMonitoring` advances the ramp.
- Code that touches Android framework classes (`Context`, `BluetoothGatt`, `SensorManager`) is currently untestable as written; extracting pure logic is usually the easiest path to coverage.

## Do not

- Add an `INTERNET` permission — the app is local-first by design and the README advertises it.
- Add a `strings.xml` extraction or i18n layer without being asked.
- Enable `SCHEDULE_EXACT_ALARM` requests at runtime: the permission is declared but intentionally filtered out of the runtime prompt list; reconnect alarms fall back to inexact alarms when `canScheduleExactAlarms()` is false.
- Assume `FanAdjustmentWorker` is wired up — it is scaffolding that only logs; scheduled automation is unfinished by design.
- Packaging: debug symbol stripping cannot process `libandroidx.graphics.path.so`; it is intentional kept in `app/build.gradle.kts` (`packaging.jniLibs.keepDebugSymbols`). Don’t remove that keep rule.
