# Stage 5: Initial Zoom Out Review Report

**Target Document:** [`docs/plan_refactorizacion_logica_critica.md`](file:///home/james/Proyectos/RedmagicCooler/docs/plan_refactorizacion_logica_critica.md)  
**Reviewers:** Skeptical Architect (`egm-zoom-out`) & Inventive Catalyst (`egm-inventive-catalyst`)  
**Context:** [`ThermalLogic.kt`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/java/com/hitomatito/redmagicooler/ThermalLogic.kt), [`CoolerService.kt`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/java/com/hitomatito/redmagicooler/CoolerService.kt), [`CoolerBleManager.kt`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/java/com/hitomatito/redmagicooler/ble/CoolerBleManager.kt), [`AndroidManifest.xml`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/AndroidManifest.xml), [`AGENTS.md`](file:///home/james/Proyectos/RedmagicCooler/AGENTS.md)

---

## Executive Summary

The proposed refactoring plan introduces critical architectural flaws, false compromises (TRIZ contradictions), and over-engineering (YAGNI). Several proposed changes directly degrade physical hardware safety (turning off cooling during thermal extremes and discarding peak SoC temperatures), break existing passing unit tests, introduce service-killing race conditions in Android lifecycle management, and risk cascading deadlocks on the BLE GATT operation queue.

---

## 🔴 Blocker Findings

### 1. Hardware Peak Outlier Rejection via `getOrNull(1)` Masks Real SoC Hotspots
- **Section:** Phase 1, Step 1.2 (`ThermalLogic.kt` -> `hardwarePeak`)
- **Analysis:**
  - The plan proposes sorting sensor values descending and taking the second highest (`getOrNull(1)`).
  - In modern heterogeneous mobile SoCs (big.LITTLE / Prime cores + GPU), high gaming load disproportionately heats the Prime core (e.g. 78°C–85°C) while Little cores and ambient zones sit at 40°C–45°C. Selecting the second value picks 45°C, leaving the fan at low speed and causing severe thermal throttling or hardware thermal shutdown.
  - Furthermore, if a device exposes only 2 valid SoC sensors, selecting index 1 drops directly to the lowest sensor.
- **Recommendation:** Reject `getOrNull(1)`. Retain `.maxOrNull()` with physical plausibility filtering (`temp in 0f..115f`) and extend the name exclusion blacklist (`"battery"`, `"bms"`, `"charger"`, `"modem"`).

### 2. EMA Initialization Anchored to 40°C Breaks Unit Tests & Delays Emergency Cooling
- **Section:** Phase 1, Step 1.3 (`ThermalLogic.kt` -> `smoothSignal`)
- **Analysis:**
  - The plan proposes initializing the EMA with `ema(previous = 40f, raw)` on the first reading instead of `value = raw`.
  - **Breaks Existing Tests:** [`ThermalLogicTest.kt`](file:///home/james/Proyectos/RedmagicCooler/app/src/test/java/com/hitomatito/redmagicooler/ThermalLogicTest.kt) explicitly asserts unbiased first-sample initialization ($S_0 = Y_0$). For a first reading of 50°C, the plan yields 43.5°C, failing the test suite.
  - **Physical Invalidation:** If the user connects the cooler when the phone is already at 65°C, the formula artificially dampens the reported temperature to 48.7°C, delaying full fan ramp-up by minutes under thermal stress. Actuator smoothing is already handled by `decide()` and `SPEED_INCREMENT`.
- **Recommendation:** Reject Step 1.3 entirely. Retain standard unbiased initialization `if (initialized) ema(...) else raw`.

### 3. Instance Variable `currentStartId` Introduces Service-Killing Race Condition
- **Section:** Phase 2, Step 2.2 (`CoolerService.kt` -> `onStartCommand` & `stopSelf`)
- **Analysis:**
  - Storing `startId` in a class mutable variable `currentStartId` causes race conditions across asynchronous commands.
  - If `ACTION_SWITCH_TO_MANUAL` arrives with `startId = 2` and begins a 2000ms delay, and the user re-enters auto mode (`ACTION_START_AUTO` with `startId = 3`), `currentStartId` is overwritten with 3. When the manual delay expires, it executes `stopSelf(3)`, terminating the newly started legitimate auto service.
- **Recommendation:** Do not use a mutable instance variable. Capture `startId` in a local immutable val within the coroutine scope for delayed stops (`stopSelf(localStartId)`), and use unparameterized `stopSelf()` for explicit immediate user stops (`ACTION_STOP_AUTO`).

### 4. Write Timeout Read Injection Causes GATT Queue Cascading Freezes
- **Section:** Phase 3, Step 3.2 (`CoolerBleManager.kt` -> `pumpOpQueue`)
- **Analysis:**
  - Injecting a `ReadCharacteristicOp` immediately after a fan-speed write timeout (3000ms) assumes the link is alive. In Android BLE, write timeouts indicate link freezing, firmware hangs, or silent disconnection. Adding another synchronous read adds an additional 3000ms blocking timeout, starving the queue and preventing disconnect/recovery operations.
- **Recommendation:** Do not inject reads on timeout. Treat repeated write timeouts as link degradation triggering controlled reconnection (`BleConnectionHelper.safeCloseGatt`). Rely on passive CCCD notifications (0x1012) for hardware state synchronization.

### 5. Removal of Volatile Watchdog Flags Breaks Firmware Fault Recovery
- **Section:** Phase 3, Step 3.1 (`CoolerBleManager.kt`)
- **Analysis:**
  - Flags such as `discoveryTimeoutReconnectPending` are essential hardware latches against RedMagic cooler firmware quirks (`status == 22` on connection termination). When the watchdog fires, Android's Bluetooth stack emits late disconnection callbacks. Removing this latch causes duplicate reconnect cycles and premature backoff exhaustion.
- **Recommendation:** Preserve internal synchronization latches. Do not conflate internal GATT engine latches with public UI state.

---

## 🟡 Major Findings

### 6. Numeric Guard Shuts Off Fan During Positive Infinite Heat
- **Section:** Phase 1, Step 1.1 (`ThermalLogic.kt` -> `targetSpeedFor`)
- **Analysis:**
  - Returning 0% speed for `temp.isNaN() || temp.isInfinite()` shuts off the cooler if a sensor reports overflow / positive infinity. The safe physical failure mode for extreme heat or positive infinity is 100% cooling, not 0%.
- **Recommendation:** Check `if (temp.isNaN()) return 0`. If `temp.isInfinite() && temp > 0f`, clamp to 100%.

### 7. Redundant Battery Receiver Registration with Null Receiver
- **Section:** Phase 2, Step 2.3 (`CoolerService.kt` -> `getCurrentBatteryLevel`)
- **Analysis:**
  - The plan proposes `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`. In Android 14, querying sticky broadcasts via `registerReceiver(null, ...)` does not register a receiver and does not require `RECEIVER_NOT_EXPORTED`.
- **Recommendation:** Retain standard sticky broadcast inspection.

### 8. Redundant Calibration Timeout Wrapping
- **Section:** Phase 2, Step 2.4 (`CoolerService.kt` -> `performInitialCalibration`)
- **Analysis:**
  - `readCurrentFanSpeed()` already has an internal bounded retry loop (20 iterations x 100ms = 2.0s max) running in a background coroutine. Wrapping it in `withTimeoutOrNull(1500L)` adds competing cancellation timers.
- **Recommendation:** Adjust internal loop iteration count or rely on `CompletableDeferred` rather than nesting cancellation wrappers.

---

## 🟢 Nits & Redundancies

### 9. Phase 0 Manifest Requirements Already Satisfied
- **Analysis:** `AndroidManifest.xml` lines 22 and 53 already declare `android:foregroundServiceType="connectedDevice"` and `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />`. `androidx.core:core-ktx` is already 1.19.1. Phase 0 is already complete.

### 10. `ConnectionState` to `BleState` Refactoring Violates YAGNI
- **Analysis:** Replacing the existing `StateFlow<ConnectionState>` and `SharedFlow<BleEvent>` with a sealed interface disrupts `MainActivity` and `CoolerService` with zero operational benefit.
