package com.hitomatito.redmagicooler.model

import com.hitomatito.redmagicooler.CoolerDeviceType
import java.util.UUID

/**
 * Representa un perfil completo de un dispositivo cooler guardado
 * Contiene toda la configuración específica del dispositivo
 */
data class CoolerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val deviceType: CoolerDeviceType,
    val macAddress: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastConnectedAt: Long = System.currentTimeMillis(),
    
    // Configuración de velocidad del ventilador
    val fanSpeed: Int = 50,
    val isAutoMode: Boolean = false,
    
    // Configuración RGB
    val rgbConfig: RGBConfig? = null,
    
    // Estado
    val isConnected: Boolean = false,
    val signalStrength: Int = 0 // RSSI
) {
    /**
     * Nombre para mostrar en la UI
     */
    val displayName: String
        get() = name.ifBlank { deviceType.deviceName }
    
    /**
     * Tiempo desde última conexión formateado
     */
    val lastSeenFormatted: String
        get() {
            val diff = System.currentTimeMillis() - lastConnectedAt
            return when {
                diff < 60_000 -> "Hace un momento"
                diff < 3_600_000 -> "Hace ${diff / 60_000} min"
                diff < 86_400_000 -> "Hace ${diff / 3_600_000} h"
                else -> "Hace ${diff / 86_400_000} días"
            }
        }
    
    companion object {
        /**
         * Crea un perfil desde un dispositivo recién conectado
         */
        fun fromConnectedDevice(
            deviceType: CoolerDeviceType,
            macAddress: String,
            deviceName: String? = null,
            rssi: Int = 0
        ): CoolerProfile {
            return CoolerProfile(
                name = deviceName ?: deviceType.deviceName,
                deviceType = deviceType,
                macAddress = macAddress,
                signalStrength = rssi,
                isConnected = true
            )
        }
    }
}
