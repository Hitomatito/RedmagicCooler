package com.hitomatito.redmagicooler.utils

import android.bluetooth.BluetoothGatt
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.util.Log

/**
 * Utilidades para manejo seguro de conexiones Bluetooth
 */
object BleConnectionHelper {
    private const val TAG = "BleConnectionHelper"
    
    /**
     * Cierra una conexión GATT de forma segura
     * @param gatt Conexión GATT a cerrar
     * @param tag Tag para logging
     * @return true si se cerró correctamente, false si hubo errores
     */
    fun safeCloseGatt(gatt: BluetoothGatt?, tag: String = TAG): Boolean {
        return try {
            gatt?.let {
                it.disconnect()
                it.close()
                Log.d(tag, "GATT cerrado correctamente")
                true
            } ?: false
        } catch (e: Exception) {
            Log.w(tag, "Error al cerrar GATT: ${e.message}")
            false
        }
    }
    
    /**
     * Detiene un escaneo BLE de forma segura
     * @param scanner Scanner a detener
     * @param callback Callback del escaneo
     * @param tag Tag para logging
     * @return true si se detuvo correctamente, false si hubo errores
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun safeStopScan(scanner: BluetoothLeScanner?, callback: ScanCallback, tag: String = TAG): Boolean {
        return try {
            scanner?.stopScan(callback)
            Log.d(tag, "Escaneo detenido correctamente")
            true
        } catch (e: Exception) {
            Log.w(tag, "Error al detener escaneo: ${e.message}")
            false
        }
    }
    
    /**
     * Refresca el caché de servicios GATT usando reflection
     * 
     * Esto soluciona un bug conocido de Android BLE donde gatt.services
     * puede devolver una lista vacía después de onServicesDiscovered.
     * 
     * @param gatt Conexión GATT a refrescar
     * @param tag Tag para logging
     * @return true si el refresh fue exitoso, false si falló
     */
    fun refreshGattCache(gatt: BluetoothGatt?, tag: String = TAG): Boolean {
        return try {
            gatt?.let {
                val refreshMethod = it.javaClass.getMethod("refresh")
                val result = refreshMethod.invoke(it) as? Boolean ?: false
                if (result) {
                    Log.d(tag, "Cache GATT refrescado exitosamente")
                } else {
                    Log.w(tag, "refresh() devolvio false")
                }
                result
            } ?: false
        } catch (e: Exception) {
            Log.w(tag, "No se pudo refrescar cache GATT (puede no ser necesario): ${e.message}")
            // No es un error crítico, algunos dispositivos no lo necesitan
            false
        }
    }
}
