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
     * Icono sugerido basado en el tipo de dispositivo
     */
    val icon: String
        get() = deviceType.getSuggestedIcon()
    
    /**
     * Descripción del estado actual
     */
    val statusDescription: String
        get() = when {
            isConnected && isAutoMode -> "Conectado • Modo Auto"
            isConnected -> "Conectado • ${fanSpeed}%"
            else -> "Desconectado"
        }
    
    /**
     * Color de estado para la UI
     */
    val statusColor: ProfileStatus
        get() = when {
            isConnected -> ProfileStatus.CONNECTED
            else -> ProfileStatus.DISCONNECTED
        }
    
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

/**
 * Estados posibles de un perfil
 */
enum class ProfileStatus {
    CONNECTED,
    DISCONNECTED,
    CONNECTING
}

/**
 * Datos serializables del perfil para persistencia
 */
data class CoolerProfileData(
    val id: String,
    val name: String,
    val deviceTypeName: String,
    val macAddress: String,
    val createdAt: Long,
    val lastConnectedAt: Long,
    val fanSpeed: Int,
    val isAutoMode: Boolean,
    val rgbEffectCode: Int?,
    val rgbRed: Int?,
    val rgbGreen: Int?,
    val rgbBlue: Int?
) {
    /**
     * Convierte los datos serializados de vuelta a CoolerProfile
     */
    fun toProfile(): CoolerProfile? {
        val deviceType = try {
            CoolerDeviceType.valueOf(deviceTypeName)
        } catch (_: IllegalArgumentException) {
            return null
        }
        
        val rgbConfig = if (rgbEffectCode != null && rgbRed != null && rgbGreen != null && rgbBlue != null) {
            val effect = LightEffect.entries.find { it.code.toInt() == rgbEffectCode }
            if (effect != null) {
                RGBConfig(effect, rgbRed, rgbGreen, rgbBlue)
            } else null
        } else null
        
        return CoolerProfile(
            id = id,
            name = name,
            deviceType = deviceType,
            macAddress = macAddress,
            createdAt = createdAt,
            lastConnectedAt = lastConnectedAt,
            fanSpeed = fanSpeed,
            isAutoMode = isAutoMode,
            rgbConfig = rgbConfig
        )
    }
    
    companion object {
        /**
         * Crea datos serializables desde un CoolerProfile
         */
        fun fromProfile(profile: CoolerProfile): CoolerProfileData {
            return CoolerProfileData(
                id = profile.id,
                name = profile.name,
                deviceTypeName = profile.deviceType.name,
                macAddress = profile.macAddress,
                createdAt = profile.createdAt,
                lastConnectedAt = profile.lastConnectedAt,
                fanSpeed = profile.fanSpeed,
                isAutoMode = profile.isAutoMode,
                rgbEffectCode = profile.rgbConfig?.effect?.code?.toInt(),
                rgbRed = profile.rgbConfig?.red,
                rgbGreen = profile.rgbConfig?.green,
                rgbBlue = profile.rgbConfig?.blue
            )
        }
    }
}
