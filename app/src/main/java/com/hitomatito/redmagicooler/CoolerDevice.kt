package com.hitomatito.redmagicooler

import android.bluetooth.BluetoothDevice

/**
 * Representa un dispositivo cooler detectado durante el escaneo BLE
 */
data class CoolerDevice(
    val bluetoothDevice: BluetoothDevice,
    val deviceType: CoolerDeviceType,
    val rssi: Int
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
