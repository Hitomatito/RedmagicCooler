# AGENTS.md

Unofficial Android app controlling RedMagic/Nubia magnetic coolers over BLE. Single Gradle module (`:app`), package `com.hitomatito.redmagicooler`.

## Commands

- Build debug APK: `./gradlew assembleDebug` (outputs to `app/build/outputs/apk/debug/`)
- Unit tests: CI runs `./gradlew testDebugUnitTest --no-daemon --console=plain` (README says `./gradlew test`; both work)
- Release APK: `./gradlew assembleRelease` (runs R8 + resource shrinking)
- No lint, ktlint, detekt, or spotless configured — do not invent lint commands
- CI (`.github/workflows/ci.yml`) = unit tests + assembleDebug + assembleRelease on push/PR to `main`; nothing else is enforced

## Environment

- Requires JDK 21, Android SDK Platform 36, Build-Tools 36.0.0, Gradle 9.4.1 (wrapper). `ANDROID_HOME` must point at an SDK with those packages installed.
- `local.properties` is gitignored and absent by default; the SDK path comes from `ANDROID_HOME`/`sdk.dir`.

## Signing / secrets

- `keystore.properties` (repo root) holds the release keystore credentials. It is gitignored and **must never be committed**. A local one exists on the maintainer's machine; do not read, print, or modify it, and do not create one with real values.
- Without `keystore.properties`, the release buildType has `signingConfig = null` and produces an unsigned APK; the build still succeeds.

## Conventions & quirks

- Kotlin 2.3.21, AGP 9.2.1, Compose (BOM 2026.05.00), single `:app` module. Plugins come from the version catalog (`gradle/libs.versions.toml`); the app module applies only `com.android.application` and `org.jetbrains.kotlin.plugin.compose` — do **not** add `org.jetbrains.kotlin.android` (AGP 9 built-in Kotlin).
- In-app UI strings and code comments are Spanish; user-facing text should stay Spanish. README is English.
- `MainActivity.kt` (~1400 lines) and `CoolerService.kt` (~1600 lines) are intentionally large — navigation + BLE orchestration live in `MainActivity`; the foreground service owns the GATT connection, auto thermal mode and reconnect backoff. Look there first, not in `ui/`.
- `CoolerService.kt` contains `private const val DEBUG = true` with a note to set it false for production. Leave the toggle as-is unless the task is a release cut; don't "fix" it silently.
- BLE protocol is reverse-engineered (no public spec). Constants live in `model/CoolerModels.kt` (`CoolerBleConstants`) and model UUIDs in `CoolerDeviceType.kt`. Fan speed is a raw byte 40–80 mapped from the UI 0–100%. Only the Heat Sink 5 Pro is hardware-tested; treat other models as unverified.
- App declares no `INTERNET` permission and is local-first (profiles in SharedPreferences/JSON via `ProfileRepository`). Do not add network dependencies.
- WorkManager (`FanAdjustmentWorker`) and scheduled automation are unfinished scaffolding, not wired-up features.
- `proguard-rules.pro` keeps `androidx.work.ListenableWorker` subclasses and line numbers — keep those rules when editing release config.
- `settings.gradle.kts` sets `rootProject.name = "RedmagiCooler"` (sic) — harmless, don't rename casually.
- `android.nonTransitiveRClass=true`; reference module R classes explicitly, not transitively.
- Tests: `app/src/test` holds only a placeholder `ExampleUnitTest`; `androidTest` has only the template `ExampleInstrumentedTest`. There is no real unit-test suite or fixtures — verify changes with `assembleDebug` and, for protocol logic, reasoning/manual review.

## Commits

- Rough conventional-commit style with Spanish or English subjects is used (e.g. `feat(ui): ...`, `chore: subir versión a 1.3.0`). Version bumps mean editing `versionCode`/`versionName` in `app/build.gradle.kts`.
