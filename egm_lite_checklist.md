# EGM-Lite Execution Checklist: Fase 2 (`CoolerService.kt`)

**Plan de Referencia:** [`docs/plan_refactorizacion_logica_critica.md`](file:///home/james/Proyectos/RedmagicCooler/docs/plan_refactorizacion_logica_critica.md)  
**Alcance Acotado:** [`CoolerService.kt`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/java/com/hitomatito/redmagicooler/CoolerService.kt)  
**Estado:** ✅ Fase 2 completa (2.1 y 2.2 implementados; 2.3 y 2.4 descartados por revisión)

---

### Tareas de Implementación

- [x] **Paso 2.1 (Foreground Service Android 14+):**
  - En [`CoolerService.kt#L109`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/java/com/hitomatito/redmagicooler/CoolerService.kt#L109): Importar `androidx.core.app.ServiceCompat` y `android.content.pm.ServiceInfo`.
  - Reemplazar `startForeground(...)` por:
    ```kotlin
    ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,
        createNotification("Iniciando...", currentSpeed, currentTemp),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    )
    ```
- [x] **Paso 2.2 (Captura de `startId` sin Estado Mutable):**
  - En [`CoolerService.kt#L288`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/java/com/hitomatito/redmagicooler/CoolerService.kt#L288): en `onStartCommand`, delegar `ACTION_SWITCH_TO_MANUAL` pasando `switchToManualMode(startId)`.
  - En [`CoolerService.kt#L397`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/java/com/hitomatito/redmagicooler/CoolerService.kt#L397): firma `private fun switchToManualMode(targetStartId: Int? = null)`.
  - En ambos bloques de detención diferida con `delay(2000)` (rama de error GATT y rama de finalización normal), `stopSelf()` condicionado:
    ```kotlin
    if (targetStartId != null) stopSelf(targetStartId) else stopSelf()
    ```
  - En `ACTION_STOP_AUTO` ([`CoolerService.kt#L284`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/java/com/hitomatito/redmagicooler/CoolerService.kt#L284)) se mantiene `stopSelf()` sin argumentos: detención inmediata pedida por el usuario.
- [x] **Paso 2.3 (Receptor de Batería con Flag Unexported):** ❌ **DESCARTADO** — resolución #6 de [`docs/goldfish_review_report.md`](file:///home/james/Proyectos/RedmagicCooler/docs/goldfish_review_report.md): `registerReceiver(null, ...)` sobre un sticky broadcast protegido no registra un receptor real ni exige `RECEIVER_NOT_EXPORTED`; puede lanzar `SecurityException` en algunos builds de Android 13/14. Se conserva [`CoolerService.kt#L909`](file:///home/james/Proyectos/RedmagicCooler/app/src/main/java/com/hitomatito/redmagicooler/CoolerService.kt#L909) sin cambios (alternativa futura: `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)`).
- [x] **Paso 2.4 (Calibración con Timeout y Reset Seguro):** ❌ **DESCARTADO** — [`docs/intermediate_zoom_out_report.md`](file:///home/james/Proyectos/RedmagicCooler/docs/intermediate_zoom_out_report.md) (Fase 2.4 redundante) e informe de readiness (#10): `readCurrentFanSpeed()` ya tiene un retry interno acotado (20 × 100 ms); envolverlo en `withTimeoutOrNull(1500L)` añade timers de cancelación compitiendo. El `finally` propuesto para `currentSpeedFromRead = -1` no lo exige el plan revisado (Fase 2 solo contiene 2.1 y 2.2); el reset normal tras la espera se conserva como está.
- [x] **Verificación de Compilación y Tests:**
  - `./gradlew testDebugUnitTest` → ✅ 21/21 tests pasan.
  - `./gradlew assembleDebug` → ✅ compila sin errores.

---

### Exclusiones Acordadas Pre-Review
- `ThermalLogic.kt`, `ThermalLogicTest.kt` y `CoolerBleManager.kt` permanecen fuera de alcance en esta iteración *(ThermalLogic.kt/ThermalLogicTest.kt se tocaron aparte bajo Fase 1 del plan, con tests nuevos)*.
- No se agrega ningún campo mutable de instancia `currentStartId` a `CoolerService`.

---

### 🔍 Assumption Lifecycle Legend:
- `[⚠️ UNVERIFIED]`: Siloed external contract. Requires indisputable evidence to verify.
- `[⏳ ACK]`: Acknowledged assumption. Factual evidence unavailable; risk accepted and opaque-box empirical test strictly scheduled.
