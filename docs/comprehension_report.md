# Stage 6: Comprehension Test Report

**Target Document:** [`docs/plan_refactorizacion_logica_critica.md`](file:///home/james/Proyectos/RedmagicCooler/docs/plan_refactorizacion_logica_critica.md)  
**Reviewer:** Naive Reader (`egm-comprehension`)  
**Scope:** `FeatureRange` (1..5) & `DetailedRange` (6..80)

---

## Feature Specification Paraphrase
The document outlines an architectural and logic refactoring plan for three core files (`ThermalLogic.kt`, `CoolerService.kt`, and `CoolerBleManager.kt`, alongside `AndroidManifest.xml`) of the RedmagicCooler application. Its purpose is to eliminate runtime crashes, race conditions, and corrupted state across Android versions 7 through 14+, establishing a deterministic, production-grade fault-tolerance baseline for hardware thermal management.

## Detailed Implementation Plan Paraphrase
- **Phase 0 (Prerequisites & Environment):** Pre-validates Android 14 requirements by confirming the `connectedDevice` foreground service type in `AndroidManifest.xml` and ensuring `androidx.core:core-ktx` is updated for modern `ServiceCompat` and receiver registration APIs.
- **Phase 1 (ThermalLogic.kt Pure Logic):** Hardens thermal calculations by adding guards against `NaN`, `Infinity`, or negative values in `targetSpeedFor(temp)` (defaulting to 0); filtering out battery, charger, and modem sensors and selecting the second-highest sensor value in `hardwarePeak(...)` to suppress outlier spikes; and seeding initial EMA signal calculations from a safe baseline (40°C) instead of raw initial readings.
- **Phase 2 (CoolerService.kt System Lifecycle):** Migrates foreground service activation to `ServiceCompat.startForeground` with `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`; guards service termination against race conditions by binding `stopSelf` to the captured `startId`; migrates battery status receiver registration to unexported receiver flags; and protects fan speed calibration against coroutine hangs using a 1500 ms timeout with fallback.
- **Phase 3 (CoolerBleManager.kt State Machine & Concurrency):** Consolidates disconnected volatile boolean flags into an exhaustive, type-safe `sealed interface BleState` flow (`Disconnected`, `Scanning`, `Connecting`, `Connected`, `Error`); and adds automatic state reconciliation in the operation queue by enqueuing a fan speed read whenever a speed write operation times out.
- **Phase 4 (Validation Checklist & DoD):** Defines verification gates including unit injection of `Float.NaN`, Android 14 API 34 startup validation, physical cooler disconnection resynchronization, task-kill service recreation (`START_STICKY`), and volatile variable cleanup.

---

## Comprehension Findings

### 1. Inverted Safety Semantics on Numeric Failure
- **Severity**: Major
- **Affected Scope**: Phase 1, Step 1.1 (Lines 16–19) & Phase 4 (Line 76)
- **Discrepancy Description & Rationale**: The specification mandates that if `temp` is `NaN`, `Infinity`, or `< 0f`, `targetSpeedFor` must immediately return `0` ("velocidad segura por defecto"). In thermal management, shutting off the cooler fan (`0%`) when thermal state cannot be read or is invalid is the opposite of a fail-safe action; it risks thermal runaway. A reader without context cannot tell if `0` is intentionally fan-off or if "velocidad segura" was meant to be a conservative cooling speed (e.g., maximum cooling or default baseline like `TEMP_SAFE`). Furthermore, the unit of `0` is ambiguous against the protocol (raw value 40–80 vs percentage 0–100).

### 2. Flawed Outlier Algorithm and Silent Throttling Blind Spot
- **Severity**: Major
- **Affected Scope**: Phase 1, Step 1.2 (Lines 21–27)
- **Discrepancy Description & Rationale**: Step 1.2 specifies sorting sensor readings descending and picking the second-highest value (`getOrNull(1)`), falling back to the single value only if `zones.size == 1`. 
  - If a device has only two valid thermal zones (e.g., CPU/SoC at 85°C and Ambient/Skin at 35°C), picking `getOrNull(1)` unconditionally selects 35°C and ignores real CPU overheating.
  - Sorting duplicate peak values (e.g., `[90f, 90f, 40f]`) will return `90f` unless uniqueness is specified. 
  The plan lacks a deterministic definition or statistical threshold (e.g., max deviation vs median) for what constitutes an "outlier", leaving the implementation vulnerable to masking genuine thermal emergencies.

### 3. Flawed `startId` Lifetime Logic in Service Termination
- **Severity**: Major
- **Affected Scope**: Phase 2, Step 2.2 (Lines 41–47)
- **Discrepancy Description & Rationale**: The plan proposes storing `startId` from `onStartCommand` into an instance variable (`currentStartId`) and later invoking `stopSelf(currentStartId)`. 
  In the Android `Service` lifecycle, `stopSelf(int startId)` terminates the service only if `startId` matches the *latest* delivered start request. If `currentStartId` is stored in a mutable field on every `onStartCommand`, a delayed termination task spawned by request #1 will read the newly overwritten `currentStartId` from request #2 and erroneously kill the service while request #2 is actively running. The specification should state that the specific `startId` must be captured locally by closure in the delayed stop routine, rather than read from an instance property.

### 4. Undefined Acronyms and Unreferenced Constants
- **Severity**: Nit
- **Affected Scope**: Phase 1, Step 1.3 (Line 28 & Line 30)
- **Discrepancy Description & Rationale**: 
  - "EMA" is introduced in the heading without defining it as Exponential Moving Average or referencing its smoothing factor ($\alpha$).
  - `TEMP_SAFE (40f)` is referenced as if it were a pre-existing constant, but the document does not clarify whether it already exists in `ThermalLogic.kt` or must be newly defined.

### 5. Stringly-Typed Op-Queue Label Inspection
- **Severity**: Nit
- **Affected Scope**: Phase 3, Step 3.2 (Lines 69–73)
- **Discrepancy Description & Rationale**: The step requires checking whether the timed-out operation was a speed write by verifying if `WriteCharacteristicOp` has a "label que contenga 'fan speed'". Relying on localized or free-form label substring matching is fragile. The document does not define whether `WriteCharacteristicOp` exposes a characteristic UUID or op-type enum that should be checked instead of string matching.

### 6. Missing Requirement for `START_STICKY` Return
- **Severity**: Nit
- **Affected Scope**: Phase 2 (Lines 33–57) & Phase 4, Checklist Item 4 (Line 79)
- **Discrepancy Description & Rationale**: Checklist item 4 requires verifying that the service recreates via `START_STICKY`. However, Phase 2 never specifies modifying `onStartCommand` to return `START_STICKY`. A reader implementing only Phase 2 would not know they need to adjust the return value of `onStartCommand`.
