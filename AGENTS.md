# AGENTS.md — RedmagicCooler

Unofficial Android client for RedMagic/Nubia magnetic coolers over BLE. Single-module Android app
(Kotlin + Jetpack Compose), no backend, no network permission.

## Environment (read first)

`java` is **not** on `PATH` on this machine — `./gradlew` fails with `JAVA_HOME is not set`.
Export it before any Gradle command:

```bash
export JAVA_HOME="$HOME/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2"   # Temurin 21.0.7
```

Android SDK is at `~/Android/Sdk` and already wired into `local.properties`
(platform `android-36`, build-tools `36.0.0`).

Android Studio is running in the background. When it syncs the project it can rewrite
`gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties` on its own (version upgrades).
If those files show up dirty in `git status` and you did not touch them, they are not yours — do not commit
them blindly.

## Commands

```bash
./gradlew testDebugUnitTest   # unit tests (this is what CI runs)
./gradlew assembleDebug       # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # -> app/build/outputs/apk/release/
```

- CI (`.github/workflows/ci.yml`) runs exactly: `testDebugUnitTest`, `assembleDebug`, `assembleRelease` on JDK 21.
  No lint, no detekt/ktlint, no spotless — **there is no formatter or linter configured**. IDE code style is `KOTLIN_OFFICIAL`.
- `assembleRelease` enables **R8 minify + resource shrinking**. `app/proguard-rules.pro` keeps WorkManager
  workers and line numbers; keep that rule if you add a worker.
- Release signing is **conditional** on `keystore.properties` existing at the repo root (gitignored, never commit).
  Without it the release variant builds unsigned.
- `--offline` works for `testDebugUnitTest` / `assembleDebug` once artifacts are cached. `assembleRelease` and
  `lintDebug` pull artifacts that are not in the local cache (`lint-gradle`, release-only Compose), so they
  need network.
- There is no `detekt`/`ktlint`/`spotless` task; do not invent one in commands.

## Architecture

Package root `com.hitomatito.redmagicooler`. `:app` is the only module.

| Area | Files |
| --- | --- |
| Manual-mode BLE host + Compose nav | `MainActivity.kt` (1400+ lines) |
| Auto-mode foreground service (BLE, thermal loop, notification, reconnect) | `CoolerService.kt` |
| Thermal sensing + speed recommendation | `ThermalMonitor.kt` |
| Profile persistence (SharedPreferences + JSON, `StateFlow`) | `data/ProfileRepository.kt` (singleton) |
| Supported cooler models (advertising UUID + drawable) | `CoolerDeviceType.kt` (**package root, not `model/`**) |
| BLE UUIDs, `RGBConfig`, `LightEffect` | `model/CoolerModels.kt` (`CoolerBleConstants` lives here) |
| Profile model | `model/CoolerProfile.kt` |
| Screens | `ui/` (Home, AddDevice, ProfileConfig, RGBControl, DeviceTypeSelector, BluetoothRequired) |
| Theme / typography | `ui/theme/` |

**Two independent BLE GATT clients exist** and duplicate the scan/connect/discovery logic:

- `MainActivity` owns the GATT client in **manual** mode.
- `CoolerService` owns a second GATT client in **automatic** mode.

Turning auto mode on **closes MainActivity's GATT** so the service takes over; turning it off sends
`ACTION_SWITCH_TO_MANUAL`, waits ~3 s, then MainActivity reconnects. Any BLE change usually has to be made
in **both** classes, or the two modes diverge.

## BLE protocol facts (reverse-engineered, no public spec)

- Fan service `d52082ad-e805-9f97-9d4e-1c682d9c9ce6`; characteristics `0x1012` (fan speed), `0x1013` (RGB),
  `0x1015` (temperature notify), `0x1018` (auto mode). Advertising service `00004a41-...`.
- RGB command = 4 bytes: `[effectCode, r, g, b]`. Effect codes: `0x01` colorful, `0x02` full-color breath,
  `0x03` single-color breath, `0x04` always bright.
- Cooler detection: **advertising UUID first**, then strict BLE-name matching (`CoolerDeviceType.matchesBleName`).
  Adding a model = new enum entry with its advertising UUID and a `drawable-nodpi` image.
- `status == 22` (`GATT_CONN_TERMINATE_LOCAL_HOST`) is a **known cooler firmware limitation**, not an app bug:
  the official Nubia app does the same. The app auto-reconnects with backoff (max 3 attempts) and shows a
  "toggle Bluetooth" message. `discoverServices()` has a 10 s watchdog that forces a reconnect.
- GATT lifecycle rules that matter: always `disconnect()` **then** `close()`; closing before the stack confirms
  the disconnect breaks the next service discovery. Use `BleConnectionHelper.safeCloseGatt` / `safeStopScan`.

### Fan-speed range trap

The **live** mapping is `MainActivity.mapPercentToRaw` / `mapRawToPercent` → raw **40–80**.
`CoolerBleConstants.percentageToRaw` / `rawToPercentage` (raw 40–200, the range stated in the README) are
**dead code** — nothing calls them. `CoolerService` also calls `MainActivity.mapRawToPercent` when reading
the fan characteristic. Use the `MainActivity` helpers; do not "fix" the range without hardware verification.

## Conventions

- **UI strings are hardcoded Spanish** — `res/values/strings.xml` contains only `app_name`, and there is zero
  `stringResource` usage in the codebase. Inline Spanish literals are the existing convention; don't route new
  strings through `strings.xml` unless asked to localize.
- Code comments, KDoc and log messages are in **Spanish**; the README is in **English**. Match the file you edit.
- Commit messages are Conventional Commits, usually **Spanish** subjects:
  `feat(ui): ...`, `fix(ble): ...`, `chore: subir versión a 1.3.0`, `docs: ...`. Recent commits mix in English.
- Branches are `origin/<issue-number>-<spanish-description>`; PRs target `main`.
- `settings.gradle.kts` has `rootProject.name = "RedmagiCooler"` (missing "c") while the application id, package
  and app label are `RedmagicCooler` / `redmagicooler`. Don't rename casually — it changes the Gradle module name.
- `CoolerService.DEBUG = true` gates verbose logging; the comment says flip it to `false` for production.

## Testing reality

- The only tests are the AS templates (`ExampleUnitTest`, `ExampleInstrumentedTest`). **There is no real test
  coverage** and no mocking library is wired up (`testImplementation` is plain JUnit4 only — no MockK, no
  Robolectric, no Turbine). Adding them means adding dependencies to `gradle/libs.versions.toml`.
- Code that touches Android framework classes (`Context`, `BluetoothGatt`, `SensorManager`) is currently
  untestable as written; extracting pure logic is usually the easiest path to coverage.

## Do not

- Add an `INTERNET` permission — the app is local-first by design and the README advertises it.
- Add a `strings.xml` extraction or i18n layer without being asked.
- Enable `SCHEDULE_EXACT_ALARM` requests at runtime: the permission is declared but intentionally filtered out of
  the runtime prompt list; reconnect alarms fall back to inexact alarms when `canScheduleExactAlarms()` is false.
- Assume `FanAdjustmentWorker` is wired up — it is scaffolding that only logs; scheduled automation is unfinished
  by design.
