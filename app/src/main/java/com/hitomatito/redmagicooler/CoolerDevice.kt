package com.hitomatito.redmagicooler

import android.bluetooth.BluetoothDevice

/**
 * Representa un dispositivo cooler detectado durante el escaneo BLE
 */
data class CoolerDevice(
    val bluetoothDevice: BluetoothDevice,
    val deviceType: CoolerDeviceType,
    val rssi: Int,
    val scanTime: Long = System.currentTimeMillis()
) {
    /**
     * Dirección MAC del dispositivo
     */
    val address: String
        get() = bluetoothDevice.address
    
    /**
     * Nombre BLE del dispositivo (puede ser null)
     */
    val bleName: String?
        get() = try {
            bluetoothDevice.name
        } catch (_: SecurityException) {
            null
        }
    
    /**
     * Nombre para mostrar en UI
     */
    val displayName: String
        get() = bleName ?: deviceType.deviceName
    
    /**
     * Descripción completa del dispositivo
     */
    val fullDescription: String
        get() = "${deviceType.deviceName} (${deviceType.description})"
    
    /**
     * Indica si la señal es fuerte (más de -70 dBm)
     */
    val hasStrongSignal: Boolean
        get() = rssi > -70
    
    /**
     * Calidad de señal como porcentaje (0-100)
     */
    val signalQuality: Int
        get() {
            // Conversión aproximada de RSSI a porcentaje
            // RSSI típicamente va de -100 (débil) a -30 (fuerte)
            return when {
                rssi >= -50 -> 100
                rssi >= -60 -> 80
                rssi >= -70 -> 60
                rssi >= -80 -> 40
                rssi >= -90 -> 20
                else -> 10
            }
        }
    
    /**
     * Icono de señal para UI
     */
    val signalIcon: String
        get() = when {
            rssi >= -50 -> "📶" // Excelente
            rssi >= -70 -> "📶" // Buena
            rssi >= -85 -> "📡" // Regular
            else -> "📉" // Débil
        }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CoolerDevice) return false
        return address == other.address
    }
    
    override fun hashCode(): Int {
        return address.hashCode()
    }
    
    override fun toString(): String {
        return "CoolerDevice(type=${deviceType.deviceName}, name=$bleName, address=$address, rssi=$rssi dBm)"
    }
}

/**
 * Lista de dispositivos encontrados con funciones de utilidad
 */
class CoolerDeviceList {
    private val devices = mutableMapOf<String, CoolerDevice>()
    
    /**
     * Agrega o actualiza un dispositivo en la lista
     */
    fun addOrUpdate(device: CoolerDevice) {
        devices[device.address] = device
    }
    
    /**
     * Obtiene todos los dispositivos como lista ordenada por señal
     */
    fun getAll(): List<CoolerDevice> {
        return devices.values.sortedByDescending { it.rssi }
    }
    
    /**
     * Obtiene dispositivos filtrados por tipo
     */
    fun getByType(type: CoolerDeviceType): List<CoolerDevice> {
        return devices.values.filter { it.deviceType == type }
            .sortedByDescending { it.rssi }
    }
    
    /**
     * Busca un dispositivo por dirección MAC
     */
    fun getByAddress(address: String): CoolerDevice? {
        return devices[address]
    }
    
    /**
     * Limpia la lista
     */
    fun clear() {
        devices.clear()
    }
    
    /**
     * Número de dispositivos encontrados
     */
    val size: Int
        get() = devices.size
    
    /**
     * Verifica si la lista está vacía
     */
    val isEmpty: Boolean
        get() = devices.isEmpty()
    
    /**
     * Obtiene estadísticas de dispositivos por tipo
     */
    fun getStatsByType(): Map<CoolerDeviceType, Int> {
        return devices.values.groupBy { it.deviceType }
            .mapValues { it.value.size }
    }
}
