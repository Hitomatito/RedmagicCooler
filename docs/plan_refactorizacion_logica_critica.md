# Plan de Refactorización de Lógica Crítica: RedmagicCooler

**Objetivo:** Elevar la resiliencia determinista, compatibilidad (Android API 24 a 37) y tolerancia a fallos del núcleo lógico conforme a las restricciones arquitectónicas de `AGENTS.md`.  
**Archivos Objetivo:** `ThermalLogic.kt` y `CoolerService.kt` (con verificación pasiva de `CoolerBleManager.kt` y `AndroidManifest.xml`).

---

## 1. Contexto y Planteamiento del Problema

### Defectos Identificados
1. **Falta de Tipado Explícito FGS en Android 14+ (API 34+):** `CoolerService.onCreate` invoca `startForeground` sin especificar explícitamente `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`, arriesgando `SecurityException` o `ForegroundServiceStartNotAllowedException` en dispositivos modernos al iniciar el modo automático.
2. **Propagación de Valores No Finitos:** Si un sensor OEM corrupto emite `Float.NaN` o valores no finitos, los cálculos de ratio térmico en `ThermalLogic` pueden propagar estados indefinidos al control del ventilador.
3. **Condición de Carrera en Terminación Diferida del Servicio:** En `CoolerService.switchToManualMode()`, el retraso de 2000 ms antes de invocar `stopSelf()` carece de vinculación segura al `startId` de la solicitud, arriesgando el apagado inadvertido de sesiones automáticas reactivadas concurrentemente.

### Restricciones Arquitectónicas (Invariantes de `AGENTS.md`)
- **Único Dueño GATT:** `CoolerBleManager` es el único dueño de la conexión BLE. No duplicar conexiones ni omitir la cola FIFO de operaciones.
- **Tolerancia a Limitaciones de Firmware:** El firmware de los coolers Nubia/RedMagic desconecta abruptamente el enlace con `status == 22` (`GATT_CONN_TERMINATE_LOCAL_HOST`). Las banderas de sincronización `@Volatile` internas y el mecanismo de backoff no deben ser eliminados.
- **Entorno Local-First:** Sin permisos de red (`INTERNET`).
- **Cobertura de Tests Puros en JVM:** `ThermalLogic` debe mantener compatibilidad estricta con la suite de pruebas unitarias existente (`./gradlew testDebugUnitTest`).

---

## Detailed Implementation Plan

### 🛑 Fase 0: Verificación de Precondiciones y Entorno
- **Verificación de AndroidManifest.xml:** Confirmar que `<service android:name=".CoolerService">` declara `android:foregroundServiceType="connectedDevice"` y que `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />` está activo (ya presente en el proyecto).
- **Verificación de Toolchain:** Confirmar que `androidx.core:core-ktx` está disponible (versión 1.19.1 configurada en `gradle/libs.versions.toml`).

---

### 🧠 Fase 1: ThermalLogic.kt (Lógica Pura y Determinista)
Objetivo: Sanitizar entradas numéricas y filtrar zonas térmicas sin alterar la fidelidad de detección en puntos calientes (*hotspots*) del SoC.

#### Paso 1.1: Guardias de Seguridad Numérica
- **Ubicación:** `targetSpeedFor(temp: Float)` y `tempLevelForSignal(value: Float)`.
- **Acción:**
  1. Al inicio de `targetSpeedFor`:
     ```kotlin
     if (temp.isNaN() || temp < 0f) return 0
     if (temp.isInfinite() && temp > 0f) return 100
     ```
  2. En `tempLevelForSignal`:
     ```kotlin
     if (value.isNaN() || value < 0f) return TempLevel.SAFE
     ```
- **Criterio de Validación:** La app nunca propaga `NaN` al ventilador ni clasifica entradas no numéricas erróneamente; un desbordamiento térmico (+$\infty$) activa la máxima refrigeración de seguridad (100%).

#### Paso 1.2: Filtrado de Plausibilidad y Exclusión de Sensores Auxiliares
- **Ubicación:** `hardwarePeak(zones: Map<String, Float>)`.
- **Acción:**
  1. Ampliar el filtro de exclusión de claves normalizadas para descartar sensores no pertenecientes al silicio del procesador: `"skin"`, `"case"`, `"battery"`, `"bms"`, `"charger"`, `"modem"`.
  2. Aplicar un filtro de plausibilidad física para silicio comercial: `temp in 0f..115f`.
  3. Mantener `.maxOrNull() ?: 0f` sobre la colección filtrada.
- **Criterio de Validación:** Mantiene la detección inmediata del núcleo Prime/GPU más caliente bajo cargas monohilo intensivas (preservando el test unitario `pico hardware solo considera zonas del SoC`), mientras descarta lecturas térmicas absurdas o provenientes del sistema de carga/batería.

---

### ⚙️ Fase 2: CoolerService.kt (Ciclo de Vida y Seguridad del Sistema)
Objetivo: Cumplir con los requisitos de ejecución en segundo plano de Android 14+ y proteger el ciclo de vida del servicio contra carreras asíncronas.

#### Paso 2.1: Cumplimiento Estricto de Foreground Service (Android 14+)
- **Ubicación:** `onCreate()`.
- **Acción:** Reemplazar la llamada directa `startForeground(...)` por `ServiceCompat.startForeground`:
  ```kotlin
  ServiceCompat.startForeground(
      this,
      NOTIFICATION_ID,
      notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
  )
  ```
- **Criterio de Validación:** El servicio se inicia en Android 14 (API 34) y superior sin arrojar `SecurityException` ni `ForegroundServiceStartNotAllowedException`.

#### Paso 2.2: Captura Inmutable de `startId` en Terminación Demorada
- **Ubicación:** `onStartCommand` y `switchToManualMode()`.
- **Acción:**
  1. En `switchToManualMode(targetStartId: Int?)`, pasar el `startId` del intent entrante y capturarlo de forma inmutable en el ámbito de la corrutina:
     ```kotlin
     serviceScope.launch {
         delay(2000)
         if (targetStartId != null) {
             stopSelf(targetStartId)
         } else {
             stopSelf()
         }
     }
     ```
  2. Para `ACTION_STOP_AUTO` (apagado inmediato solicitado por el usuario), invocar `stopSelf()` directamente sin argumentos.
- **Criterio de Validación:** Si llega un comando `ACTION_START_AUTO` mientras transcurren los 2000 ms de una transición a manual, el sistema operativo ignora la orden de detención con el `startId` obsoleto, preservando la sesión activa.

---

### 📡 Fase 3: CoolerBleManager.kt (Preservación de Estabilidad BLE)
Objetivo: Mantener la estabilidad del motor BLE sin introducir regresiones en la gestión de hardware RedMagic.

#### Paso 3.1: Preservación de la Máquina de Estados y Latches del Watchdog
- **Acción:** Conservar la máquina de estados actual (`StateFlow<ConnectionState>`) consumida por `MainActivity` y `CoolerService`. Mantener las banderas `@Volatile` de guardia (`discoveryTimedOutFlag`, `discoveryTimeoutReconnectPending`) indispensables para evitar ciclos duplicados de desconexión ante el error de firmware `status == 22`.

---

### ✅ Fase 4: Checklist de Validación Final (Definition of Done)

- [x] **Pruebas Unitarias de Lógica Térmica:** Ejecutar `./gradlew testDebugUnitTest` y confirmar que pasan los 21 tests existentes (los 19 originales más los nuevos de `NaN`/infinitos). ✅ `BUILD SUCCESSFUL` (21/21).
- [x] **Prueba de Inyección de Fallos:** Verificar que una temperatura `Float.NaN` retorna 0% en `targetSpeedFor` y `TempLevel.SAFE` en `tempLevelForSignal`, sin excepciones en tiempo de ejecución. ✅ cubierta por `guardias numéricas en velocidad y nivel de señal`.
- [x] **Prueba de Compatibilidad Android 14+:** Iniciar el servicio automático en un dispositivo o emulador con API 34+ y verificar en Logcat la ausencia de `ForegroundServiceStartNotAllowedException`. ✅ Dispositivo real API 34 (Xiaomi `2107113SG`, MIUI V816): `dumpsys activity services` muestra `isForeground=true types=00000010` (`connectedDevice`) y Logcat no registra `ForegroundServiceStartNotAllowedException` ni `SecurityException`.
- [x] **Prueba de Carrera de Ciclo de Vida:** Alternar rápidamente de modo manual a automático dentro de la ventana de 2 segundos; verificar que el servicio no finaliza de forma imprevista. ✅ `SWITCH_TO_MANUAL` + `START_AUTO` a los 600 ms (instrumentation con la clave debug): `stopSelf(1)` se ejecuta a los 2 s, el servicio sigue en `lastStartId=2` y una comprobación dentro del proceso a los 4 s devuelve `servicio vivo=true`.
- [x] **Prueba de Supervisión BLE:** Apagar la alimentación del cooler; verificar que el stack detecta la pérdida de enlace GATT tras el timeout de supervisión, transiciona a estado desconectado y programa reintentos sin saturar la cola de operaciones. ✅ Con el cooler apagado: `Desconectado (status=8)` → reintentos `1/5` 2 s, `2/5` 4 s, `3/5` 8 s, reconexión y `Servicios descubiertos: 4`; 0 operaciones encoladas durante la ventana desconectada.

> La prueba en dispositivo destapó dos fallos reales de supervisión, ya corregidos en `CoolerBleManager`/`CoolerService`: (1) `ACTION_START_AUTO` reconectaba en paralelo a la reconexión de `onCreate` y cerraba el GATT a mitad de discovery (`Servicios descubiertos: 0`), ahora se respeta con `isBusyWith(macAddress)`; (2) los dos caminos de error de discovery cerraban el GATT sin programar reintento, dejando el modo auto muerto hasta apagar y encender el cooler, ahora llaman a `scheduleReconnect()`.
