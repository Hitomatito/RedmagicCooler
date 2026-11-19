# Goldfish Review Resolution Report (`egm-resolve`)

**Target Document:** [`docs/plan_refactorizacion_logica_critica.md`](file:///home/james/Proyectos/RedmagicCooler/docs/plan_refactorizacion_logica_critica.md)  
**Input Reports:** `zoom_out_report.md`, `comprehension_report.md`, `critic_report.md`, `intermediate_zoom_out_report.md`, `implementation_readiness_report.md`  

---

## Findings Classification & Resolution Matrix

| # | Finding Scope | Severity | Status | Resolution / Action |
|---|---|---|---|---|
| 1 | **Phase 1.2:** `hardwarePeak` taking `getOrNull(1)` | 🔴 Blocker | `PROPOSE FIX` | **Reject `getOrNull(1)`.** Keep `.maxOrNull()` to protect Prime core SoC hotspots. Add physical plausibility range filter `temp in 0f..115f` and extend keyword exclusions to `"battery"`, `"bms"`, `"charger"`, `"modem"`. Preserves `ThermalLogicTest`. |
| 2 | **Phase 1.3:** EMA initialization anchored to 40°C | 🔴 Blocker | `PROPOSE FIX` | **Remove Step 1.3 completely.** Retain standard unbiased initialization `if (initialized) ema(...) else raw`. Prevents thermal response latency under hot start and preserves `ThermalLogicTest` assertions. |
| 3 | **Phase 3.1:** `BleState` sealed interface & volatile flag removal | 🔴 Blocker | `PROPOSE FIX` | **Remove Step 3.1 completely.** Retain existing `StateFlow<ConnectionState>` and keep `@Volatile` watchdog flags (`discoveryTimedOutFlag`, `discoveryTimeoutReconnectPending`) to maintain firmware status 22 recovery latches. |
| 4 | **Phase 4 (Checklist):** Read 0% from powered-off cooler | 🔴 Blocker | `PROPOSE FIX` | **Correct checklist item:** Verify link loss / GATT supervision timeout detection, clean transition to reconnecting state, and local speed reset. |
| 5 | **Phase 2.2:** Mutable instance field `currentStartId` | 🟡 Major | `PROPOSE FIX` | **Replace instance field with local closure capture.** Capture `val targetStartId = startId` within coroutine scope in `switchToManualMode` before `delay(2000)`. Keep unparameterized `stopSelf()` for user `ACTION_STOP_AUTO`. |
| 6 | **Phase 2.3:** `RECEIVER_NOT_EXPORTED` on null receiver | 🟡 Major | `PROPOSE FIX` | **Drop Step 2.3.** Null receiver sticky broadcast query does not require flags on API 34. Alternatively use `BatteryManager.getIntProperty(CAPACITY)` without receivers. |
| 7 | **Phase 3.2:** GATT read characteristic injection on write timeout | 🟡 Major | `PROPOSE FIX` | **Remove Step 3.2.** Write timeout indicates link degradation; injecting speculative reads starves the queue. Hardware speed updates are already delivered via passive CCCD notifications (0x1012). |
| 8 | **Phase 1.1:** Numeric guard on positive infinity returning 0% | 🟡 Major | `PROPOSE FIX` | **Refine guard:** Return 0% for `temp.isNaN() || temp < 0f`. Clamp positive infinity (`temp.isInfinite() && temp > 0f`) to 100% cooling. Update `tempLevelForSignal` for NaN consistency. |
| 9 | **Phase 0:** Redundant manifest requirement | 🟢 Nit | `PROPOSE FIX` | **Reframe as pre-flight verification.** Note that `android:foregroundServiceType="connectedDevice"` and permissions are already implemented. |
| 10 | **Phase 2.1:** `ServiceCompat.startForeground` | Valid | `PROPOSE FIX` | **Retain as core fix.** Implement `ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)` in `CoolerService.onCreate`. |
| 11 | **Document Preamble:** Missing Problem Statement & Constraints | 🔴 Blocker | `PROPOSE FIX` | **Restructure document:** Add formal Problem Statement, Functional/Non-Functional Requirements, and Architectural Constraints per `AGENTS.md`. |
