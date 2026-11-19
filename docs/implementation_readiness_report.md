# Stage 9: Implementation Readiness Test Report

**Target Document:** [`docs/plan_refactorizacion_logica_critica.md`](file:///home/james/Proyectos/RedmagicCooler/docs/plan_refactorizacion_logica_critica.md) (Lines 6–80: `DetailedRange`)  
**Reviewers:** Pragmatic Tech Lead (`egm-implementation-readiness`), Codebase Auditor (`egm-codebase-details`), Copy Editor (`egm-style`)  
**Standard:** Implementation Readiness Standard  
**Verdict:** ❌ **NOT READY FOR DIRECT IMPLEMENTATION**

---

## Executive Summary

The implementation plan in its current state cannot be executed as written. If implemented directly, it causes **immediate CI unit test failures** (`./gradlew testDebugUnitTest`), introduces **app-wide compilation errors** across unreferenced UI classes (`MainActivity.kt`), resurrects known **BLE reconnection loops** on Nubia/RedMagic hardware (`status == 22`), and introduces **service-killing race conditions** in Android lifecycle handling.

---

## 🔴 Blocker Findings

### 1. Step 1.2 Breaks CI Unit Tests & Masks Legitimate SoC Hotspots
- **Affected File:** [`ThermalLogic.kt#L148-L156`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/java/com/hitomatito/redmagicooler/ThermalLogic.kt#L148-L156), [`ThermalLogicTest.kt#L279-L291`](file:///home/james/Proyectos/RedmagicCooler/app/src/test/java/com/hitomatito/redmagicooler/ThermalLogicTest.kt#L279-L291)
- **Detailed Defect:**
  1. **Immediate Test Failure:** Existing CI unit test `pico hardware solo considera zonas del SoC` verifies `assertEquals(58f, peak, 0.001f)` with inputs `"cpu" to 50f, "gpu" to 58f`. Sorting descending and returning `getOrNull(1)` returns `50f`, failing the test on CI.
  2. **Severe Hardware Safety Risk:** Modern heterogeneous SoCs under heavy single-thread gaming concentrate heat in the Prime core (75°C–80°C) while secondary cores remain idle at 40°C. Discarding the peak sensor reading blinds the cooler and prevents emergency cooling.
- **Remedy:** Keep `.maxOrNull()`. Implement plausibility range checks (`temp in 0f..115f`) and extend sensor name exclusions (`"battery"`, `"bms"`, `"charger"`, `"modem"`).

### 2. Step 1.3 Breaks EMA Unit Tests & Delays Thermal Ramp-Up
- **Affected File:** [`ThermalLogic.kt#L106-L111`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/java/com/hitomatito/redmagicooler/ThermalLogic.kt#L106-L111), [`ThermalLogicTest.kt#L73-L79`](file:///home/james/Proyectos/RedmagicCooler/app/src/test/java/com/hitomatito/redmagicooler/ThermalLogicTest.kt#L73-L79)
- **Detailed Defect:**
  1. **Immediate Test Failure:** Unit test `primera muestra inicializa EMA` asserts that on the first reading ($S_0 = Y_0$), `maxTemp` and `smoothedControlTemp` equal the raw temperature (50.0°C). Seeding EMA with 40°C yields 43.5°C, failing the test.
  2. **Thermal Response Delay:** If the device is already hot (60°C) when auto mode starts, the formula artificially reports 47°C, stalling the fan for multiple cycles during thermal stress.
- **Remedy:** Drop Step 1.3. Retain standard unbiased initialization `if (initialized) ema(...) else raw`.

### 3. Step 3.1 Breaks Compilation in `MainActivity.kt` & Resurrects GATT Status 22 Reconnection Loop
- **Affected File:** [`CoolerBleManager.kt`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/java/com/hitomatito/redmagicooler/ble/CoolerBleManager.kt), [`MainActivity.kt#L574-L577`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/java/com/hitomatito/redmagicooler/MainActivity.kt#L574-L577), [`CoolerService.kt#L78`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/java/com/hitomatito/redmagicooler/CoolerService.kt#L78)
- **Detailed Defect:**
  1. **Compilation Breakage:** Replacing `MutableStateFlow<ConnectionState>` with `MutableStateFlow<BleState>` breaks all consumers in `MainActivity.kt` and `CoolerService.kt`. `MainActivity.kt` was omitted from the plan's target files.
  2. **Binder Multithreading Collision:** Removing `@Volatile` flags `discoveryTimedOutFlag` and `discoveryTimeoutReconnectPending` breaks the latch against late `onConnectionStateChange(STATE_DISCONNECTED, status = 22)` callbacks emitted on native Binder threads. Removing these flags causes duplicate reconnect cycles and premature backoff exhaustion (documented in [`AGENTS.md`](file:///home/james/Proyectos/RedmagicCooler/AGENTS.md)).
- **Remedy:** Drop Step 3.1. Preserve the stable `ConnectionState` StateFlow and maintain internal volatile latches.

### 4. Step 4 Checklist Item 3 Requires Physically Impossible Verification
- **Affected File:** [`plan_refactorizacion_logica_critica.md#L78`](file:///home/james/Proyectos/RedmagicCooler/docs/plan_refactorizacion_logica_critica.md#L78)
- **Detailed Defect:** Demands verifying that powering off the cooler physically causes the app to "read the real state (0%)" over BLE. A powered-off peripheral cannot transmit radio packets; it triggers link loss/supervision timeout, not a 0% payload.
- **Remedy:** Rephrase criteria to verify disconnection detection and local state reset upon link loss.

---

## 🟡 Major Findings

### 5. Step 2.2 Instance Field `currentStartId` Introduces Service-Killing Race Condition
- **Affected File:** [`CoolerService.kt#L403-L406`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/java/com/hitomatito/redmagicooler/CoolerService.kt#L403-L406)
- **Detailed Defect:** In `switchToManualMode()`, shutdown is delayed by 2000 ms. If stored in a mutable instance variable `currentStartId`, an incoming `ACTION_START_AUTO` during those 2000 ms overwrites `currentStartId`. When the delayed coroutine wakes up, calling `stopSelf(currentStartId)` uses the new ID and terminates the active auto service.
- **Remedy:** Capture `startId` locally in the coroutine closure: `val targetStartId = startId; serviceScope.launch { delay(2000); stopSelf(targetStartId) }`.

### 6. Step 2.3 Redundant Receiver Registration on System Protected Broadcast
- **Affected File:** [`CoolerService.kt#L898`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/java/com/hitomatito/redmagicooler/CoolerService.kt#L898)
- **Detailed Defect:** `Intent.ACTION_BATTERY_CHANGED` is a protected system sticky broadcast. Calling `registerReceiver(null, ...)` queries the cached intent from the system without registering an active receiver. Passing `RECEIVER_NOT_EXPORTED` on null receiver is redundant and can trigger `SecurityException` on some Android 13/14 builds.
- **Remedy:** Retain standard sticky inspection or query `BatteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)`.

### 7. Step 3.2 String-Matching GATT Op Identification & Queue Flooding
- **Affected File:** [`CoolerBleManager.kt#L509-L516`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/java/com/hitomatito/redmagicooler/ble/CoolerBleManager.kt#L509-L516)
- **Detailed Defect:** Checking `label.contains("fan speed")` is fragile. Furthermore, injecting a read characteristic operation immediately after a write timeout over a frozen BLE link adds a cascading 3000ms delay and starves the queue.
- **Remedy:** Do not inject speculative reads on write timeouts. Identify ops by UUID and rely on passive CCCD updates or reconnect backoff.

### 8. Step 1.1 Inconsistent `NaN` Handling Between Target Speed and Signal Level
- **Affected File:** [`ThermalLogic.kt#L68-L90`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/java/com/hitomatito/redmagicooler/ThermalLogic.kt#L68-L90)
- **Detailed Defect:** If `temp` is `NaN`, returning 0 in `targetSpeedFor` while `tempLevelForSignal(value)` defaults to `CRITICAL` produces a contradictory UI/actuator state.
- **Remedy:** Synchronize non-finite handling across both methods.

---

## 🟢 Nit Findings

### 9. Phase 0 Is Completely Redundant
- **Affected File:** [`AndroidManifest.xml#L22, L53`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/AndroidManifest.xml#L22)
- **Detail:** `android:foregroundServiceType="connectedDevice"` and permission `FOREGROUND_SERVICE_CONNECTED_DEVICE` are already declared.

### 10. Step 2.4 Coroutine Wrapping Redundancy
- **Affected File:** [`CoolerService.kt#L579-L592`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/java/com/hitomatito/redmagicooler/CoolerService.kt#L579-L592)
- **Detail:** `readCurrentFanSpeed()` already has an internal bounded retry loop running in a background coroutine.
