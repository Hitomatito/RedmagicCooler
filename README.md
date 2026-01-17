# RedmagiCooler

Una aplicación Android avanzada para controlar el **Red Magic Cooler 5 Pro**, un enfriador magnético externo de alto rendimiento mediante Bluetooth Low Energy (BLE). Desarrollada con Kotlin y Jetpack Compose, ofrece control manual y automático de la velocidad del ventilador, monitoreo térmico en tiempo real y personalización de iluminación RGB.

## � Acerca del Red Magic Cooler 5 Pro

El Red Magic Cooler 5 Pro es un enfriador magnético premium diseñado para dispositivos móviles, que utiliza tecnología avanzada de enfriamiento:

- **Tecnología VC (Vapor Chamber)**: Distribución eficiente del calor
- **Placa TEC de 36x36mm**: Elemento Peltier de alto rendimiento
- **Potencia Pico**: Hasta 36W para enfriamiento rápido y efectivo
- **Ventilador Silencioso**: Diseño de 7 aspas para operación silenciosa
- **Iluminación RGB**: Panel transparente con efectos de luz personalizables
- **Compatibilidad**: iPhone, Android y Nintendo Switch
- **Uso Ideal**: Gaming intenso y aplicaciones que generan alto calor

Esta aplicación está optimizada específicamente para aprovechar todas las capacidades del Red Magic Cooler 5 Pro.

## �🚀 Características Principales

### Control de Ventilador
- **Control Manual**: Ajuste preciso de la velocidad del ventilador silencioso de 7 aspas (0-100%)
- **Modo Automático**: Ajuste inteligente basado en la temperatura del dispositivo
- **Monitoreo Térmico**: Lectura en tiempo real de la temperatura del cooler con tecnología VC (Vapor Chamber)

### Iluminación RGB
- Control completo de colores RGB en el diseño transparente
- Efectos de iluminación personalizables
- Integración con modo automático

### Servicio en Primer Plano
- Monitoreo continuo sin mantener la app abierta
- Notificaciones persistentes del estado
- Reconexión automática en caso de desconexión
- Optimización de batería con métricas de uso

### Conectividad BLE
- Conexión estable con dispositivo Red Magic Cooler 5 Pro
- Manejo inteligente de reconexiones
- Soporte para Android 6.0+ (API 23) hasta Android 15 (API 36)

## 📋 Requisitos del Sistema

- **Android**: Versión 7.0 (API 24) o superior
- **Hardware**: Bluetooth Low Energy (BLE) compatible
- **Dispositivo**: Red Magic Cooler 5 Pro con dirección MAC `24:04:09:00:BB:8D`

### Compatibilidad
- **Dispositivos móviles**: Compatible Android.
- **Uso recomendado**: Gaming intenso y aplicaciones que generan calor elevado
- **Potencia**: Hasta 36W de enfriamiento pico con placa TEC de 36x36mm

### Permisos Requeridos
- **Bluetooth**: Para comunicación con el cooler
- **Ubicación**: Requerido para escaneo BLE en Android 11 y anteriores
- **Notificaciones**: Para servicio en primer plano
- **Alarmas Exactas**: Para programar ajustes automáticos

## 🛠️ Instalación

### Desde Código Fuente
1. **Clona el repositorio**:
   ```bash
   git clone https://github.com/Hitomatito/RedmagicCooler
   cd RedmagiCooler
   ```

2. **Abre en Android Studio**:
   - Importa el proyecto desde la carpeta clonada
   - Asegúrate de tener Android Studio Arctic Fox o superior

3. **Configura el SDK**:
   - SDK mínimo: API 24 (Android 7.0)
   - SDK objetivo: API 36 (Android 15)
   - SDK de compilación: API 36

4. **Compila y ejecuta**:
   - Conecta un dispositivo Android o usa un emulador
   - Ejecuta la app desde Android Studio

### Desde APK
1. Descarga el archivo APK desde la sección de releases
2. Habilita "Instalación de fuentes desconocidas" en ajustes de Android
3. Instala el APK en tu dispositivo

## 📖 Uso

### Primera Configuración
1. **Habilita Bluetooth**: Asegúrate de que Bluetooth esté activado en tu dispositivo
2. **Otorga Permisos**: La app solicitará permisos necesarios al iniciar
3. **Conecta el Cooler**: Asegúrate de que el Redmagic Cooler esté encendido y cerca

### Conexión al Dispositivo
1. Abre la aplicación
2. La app escaneará automáticamente dispositivos BLE cercanos
3. Selecciona "Conectar" cuando aparezca el Redmagic Cooler
4. Espera la confirmación de conexión exitosa

### Control Manual
- **Velocidad del Ventilador**: Usa el slider para ajustar de 0% a 100%
- **Luces RGB**: Navega a la pantalla de control RGB para personalizar colores
- **Modo Raw**: Opción avanzada para control directo de bytes (desarrolladores)

### Modo Automático
1. Activa el "Modo Automático" desde la interfaz principal
2. Inicia el servicio en primer plano
3. La app ajustará automáticamente la velocidad del ventilador basado en:
   - Temperatura actual del dispositivo
   - Umbrales configurables
   - Optimización de batería

### Servicio en Primer Plano
- El servicio permite monitoreo continuo
- Recibe notificaciones del estado del cooler
- Se reinicia automáticamente tras reinicios del sistema
- Monitorea el uso de batería del servicio

## 🔧 Configuración Avanzada

### Umbrales de Temperatura
- **Baja** (< 35°C): Velocidad mínima (20%)
- **Media** (35-45°C): Velocidad moderada (40-60%)
- **Alta** (> 45°C): Velocidad máxima (80-100%)

### Optimizaciones
- **Backoff de Reconexión**: Aumenta progresivamente el tiempo entre intentos
- **Límite de Objetos Muertos**: Previene fugas de memoria
- **Métricas de Batería**: Monitorea el impacto en la duración de batería

## 🐛 Solución de Problemas

### Problemas de Conexión
- **Verifica Bluetooth**: Asegúrate de que esté habilitado
- **Distancia**: Mantén el dispositivo cerca del cooler
- **Permisos**: Otorga todos los permisos solicitados
- **Reinicio**: Reinicia ambos dispositivos si es necesario

### Rendimiento
- **Batería**: El modo automático optimiza el uso de batería
- **Memoria**: Logging condicional reduce uso de memoria en producción
- **CPU**: Operaciones BLE optimizadas para bajo consumo

### Logs de Depuración
- Los logs están disponibles en Logcat con tag "RedmagiCooler"
- En producción, solo se muestran errores y advertencias

## 🏗️ Arquitectura

### Componentes Principales
- **MainActivity**: Interfaz de usuario principal con Jetpack Compose
- **CoolerService**: Servicio en primer plano para control automático
- **ThermalMonitor**: Monitoreo de temperatura del dispositivo
- **BlePermissionManager**: Gestión de permisos BLE
- **FanAdjustmentWorker**: WorkManager para ajustes programados

### Tecnologías Utilizadas
- **Kotlin**: Lenguaje principal
- **Jetpack Compose**: UI moderna y declarativa
- **Bluetooth LE**: Comunicación con dispositivo
- **Coroutines**: Programación asíncrona
- **WorkManager**: Tareas en segundo plano
- **Navigation Compose**: Navegación entre pantallas

## 🤝 Contribución

¡Las contribuciones son bienvenidas! Para contribuir:

1. **Fork** el proyecto
2. **Crea** una rama para tu feature (`git checkout -b feature/AmazingFeature`)
3. **Commit** tus cambios (`git commit -m 'Add some AmazingFeature'`)
4. **Push** a la rama (`git push origin feature/AmazingFeature`)
5. **Abre** un Pull Request

### Guías de Contribución
- Sigue las convenciones de código Kotlin
- Agrega tests para nuevas funcionalidades
- Actualiza la documentación según sea necesario
- Usa commits descriptivos

## 📄 Licencia

Este proyecto está bajo la Licencia MIT. Ver el archivo `LICENSE` para más detalles.

## 👨‍💻 Autor

**Hitomatito** - *Desarrollo inicial*

## 🙏 Agradecimientos

- Comunidad de desarrolladores Android
- Documentación oficial de Android
- Usuarios de Redmagic por el feedback

## 📞 Soporte

Para soporte técnico:
- Abre un issue en GitHub
- Incluye logs de Logcat si es posible
- Describe tu dispositivo Android y versión

---

**Nota**: Esta aplicación es un proyecto de código abierto no oficial diseñado específicamente para el Red Magic Cooler 5 Pro. No está afiliada con Nubia/Redmagic/ZTE.