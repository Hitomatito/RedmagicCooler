package com.hitomatito.redmagicooler.model

import java.util.UUID

/**
 * Efectos de luz RGB disponibles para el cooler RedMagic
 */
enum class LightEffect(val code: Byte) {
    COLORFUL(0x01),          // Modo colorido/arcoíris
    BREATH_FULLCOLOR(0x02),  // Respiración con cambio de color completo
    BREATH_SINGLE(0x03),     // Respiración con un solo color
    ALWAYS_BRIGHT(0x04)      // Siempre encendido con color fijo
}

/**
 * Configuración RGB para el cooler
 * @property effect Efecto de luz a aplicar
 * @property red Componente rojo (0-255)
 * @property green Componente verde (0-255)
 * @property blue Componente azul (0-255)
 */
data class RGBConfig(
    val effect: LightEffect,
    val red: Int,
    val green: Int,
    val blue: Int
) {
    init {
        require(red in 0..255) { "Red debe estar entre 0 y 255" }
        require(green in 0..255) { "Green debe estar entre 0 y 255" }
        require(blue in 0..255) { "Blue debe estar entre 0 y 255" }
    }
}

/**
 * UUIDs BLE del cooler RedMagic descubiertos del análisis de la app original (cn.nubia.externdevice)
 */
object CoolerBleConstants {
    // UUID de advertising service - usado para detectar el dispositivo durante el escaneo
    val ADVERTISING_SERVICE_UUID: UUID = UUID.fromString("00004a41-0000-1000-8000-00805f9b34fb")
    
    // UUID del servicio principal del fan/cooler
    val FAN_SERVICE_UUID: UUID = UUID.fromString("d52082ad-e805-9f97-9d4e-1c682d9c9ce6")
    
    // Características del servicio del fan
    val FAN_SPEED_CHARACTERISTIC_UUID: UUID = UUID.fromString("00001012-0000-1000-8000-00805f9b34fb")
    val TEMPERATURE_NOTIFICATION_UUID: UUID = UUID.fromString("00001015-0000-1000-8000-00805f9b34fb")
    val LIGHT_CONTROL_UUID: UUID = UUID.fromString("00001013-0000-1000-8000-00805f9b34fb")
    val AUTO_MODE_CONTROL_UUID: UUID = UUID.fromString("00001018-0000-1000-8000-00805f9b34fb")
    
    // Conversión de velocidad: 0-100% → 40-200 raw value
    const val MIN_RAW_VALUE = 40
    const val MAX_RAW_VALUE = 200
    
    /**
     * Convierte porcentaje (0-100) a valor raw del BLE (40-200)
     */
    fun percentageToRaw(percentage: Int): Int {
        val clamped = percentage.coerceIn(0, 100)
        return MIN_RAW_VALUE + ((MAX_RAW_VALUE - MIN_RAW_VALUE) * clamped / 100)
    }
    
    /**
     * Convierte valor raw del BLE (40-200) a porcentaje (0-100)
     */
    fun rawToPercentage(raw: Int): Int {
        val clamped = raw.coerceIn(MIN_RAW_VALUE, MAX_RAW_VALUE)
        return ((clamped - MIN_RAW_VALUE) * 100) / (MAX_RAW_VALUE - MIN_RAW_VALUE)
    }
    
    // Comandos para control de modo automático
    // Según el logcat de la app original: writeAutoOn y writeAutoOff ambos escriben 0x00
    // La característica 0x1018 controla el modo automático
    const val AUTO_MODE_COMMAND: Byte = 0x00
}
