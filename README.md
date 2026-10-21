# RedmagicCooler

[![CI](https://github.com/Hitomatito/RedmagicCooler/actions/workflows/ci.yml/badge.svg)](https://github.com/Hitomatito/RedmagicCooler/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Hitomatito/RedmagicCooler)](https://github.com/Hitomatito/RedmagicCooler/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

Aplicación Android no oficial para controlar los enfriadores magnéticos Red Magic por Bluetooth LE: velocidad del ventilador, temperatura y iluminación RGB.

## Dispositivos compatibles

La app identifica cada modelo por su UUID de advertising BLE.

| Generación | Modelo |
| --- | --- |
| 1 | Enfriador Doble Núcleo |
| 2 | Enfriador Turbo |
| 3 | Magcooler |
| 4 | Heat Sink 4 Pro |
| 5 | Heat Sink 5 Pro · Heat Sink 5 Lite |
| 6 | Heat Sink 6 · Heat Sink 6 Pro |

Probado en el **Heat Sink 5 Pro**. El resto de modelos están soportados por UUID, pero no verificados en hardware.

## Características

- **Control manual** — velocidad del ventilador de 0% a 100%
- **Modo automático** — ajusta la velocidad según la temperatura del dispositivo y umbrales configurables
- **Iluminación RGB** — color y efectos desde la app
- **Servicio en primer plano** — mantiene la conexión y la monitorización sin tener la app abierta
- **Reconexión automática** — backoff progresivo tras pérdidas de conexión
- **Multi-dispositivo** — varios coolers gestionados desde la misma app

## Instalación

### APK

Descarga el APK desde la [última release](https://github.com/Hitomatito/RedmagicCooler/releases/latest) e instálalo permitiendo la instalación desde fuentes desconocidas.

### Desde código fuente

```bash
git clone https://github.com/Hitomatito/RedmagicCooler
cd RedmagicCooler
./gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/`.

Para una build de release necesitas un keystore propio. Crea `keystore.properties` en la raíz (está en `.gitignore`):

```properties
storeFile=/ruta/a/tu/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Y ejecuta `./gradlew assembleRelease`.

## Requisitos

- Android 7.0 (API 24) o superior
- Bluetooth LE
- `targetSdk` 36 (Android 16), `compileSdk` 36

## Uso

1. Activa Bluetooth y concede los permisos que pide la app.
2. Enciende el cooler y mantenlo cerca.
3. La app escanea dispositivos BLE y muestra el modelo detectado.
4. Conecta y ajusta la velocidad o el modo automático desde la pantalla principal.

### Permisos

- **Bluetooth** (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`) — comunicación con el cooler
- **Ubicación** — requerido para escanear BLE en Android 11 y anteriores
- **Notificaciones** — estado del servicio en primer plano
- **Alarmas exactas** — ajustes programados

## Cómo funciona

El protocolo BLE está obtenido por **ingeniería inversa del logcat de la app oficial**: UUIDs de servicio y características, tramas de velocidad, modo automático y comandos RGB.

No existe especificación pública. Si una actualización de firmware cambia los UUIDs o el formato de las tramas, la app puede dejar de funcionar con ese modelo. Los issues con logs de Logcat son la forma más rápida de diagnosticarlo.

## Arquitectura

- `MainActivity` — UI en Jetpack Compose
- `CoolerService` — servicio en primer plano: conexión GATT, comandos y reconexión
- `ThermalMonitor` — temperatura del dispositivo y disparo del modo automático
- `ProfileRepository` — perfiles de configuración guardados
- `BlePermissionManager` — permisos BLE por versión de Android

Stack: Kotlin, Jetpack Compose, Coroutines, Navigation Compose, WorkManager.

## Contribuir

1. Haz fork y crea una rama (`feature/mi-mejora`).
2. Añade tests para la lógica nueva.
3. Abre un pull request describiendo el modelo de cooler en el que lo probaste.

CI compila, ejecuta los tests y verifica que R8 no rompa la build de release.

## Licencia

MIT. Consulta [LICENSE](LICENSE).

---

Proyecto no oficial. No está afiliado a Nubia, RedMagic ni ZTE.
