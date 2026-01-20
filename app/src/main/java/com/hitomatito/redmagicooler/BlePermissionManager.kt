package com.hitomatito.redmagicooler

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Manager centralizado para manejar todos los permisos BLE de la aplicación
 */
object BlePermissionManager {
    
    /**
     * Obtiene la lista de permisos BLE requeridos según la versión de Android
     */
    fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        // Agregar permisos BLE para Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        
        // Agregar permiso de notificaciones para Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        // Agregar permiso para alarmas exactas en Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.SCHEDULE_EXACT_ALARM)
        }
        
        return permissions.toTypedArray()
    }
    
    /**
     * Verifica si todos los permisos necesarios están concedidos
     */
    fun hasAllPermissions(context: Context): Boolean {
        return getRequiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Verifica si los permisos BLE críticos están concedidos (excluye permisos opcionales como SCHEDULE_EXACT_ALARM)
     * Útil para servicios en background donde algunos permisos son opcionales
     */
    fun hasCriticalBlePermissions(context: Context): Boolean {
        val critical = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        // Agregar permisos BLE para Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            critical.add(Manifest.permission.BLUETOOTH_CONNECT)
            critical.add(Manifest.permission.BLUETOOTH_SCAN)
            critical.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        
        return critical.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Obtiene la lista de permisos que faltan
     */
    fun getMissingPermissions(context: Context): List<String> {
        return getRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Verifica si el permiso BLUETOOTH_CONNECT está concedido
     * Para versiones anteriores a Android 12, siempre retorna true
     */
    fun hasBluetoothConnectPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // No se requiere en versiones anteriores
        }
    }
    
    /**
     * Verifica si el permiso BLUETOOTH_SCAN está concedido
     * Para versiones anteriores a Android 12, siempre retorna true
     */
    fun hasBluetoothScanPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // No se requiere en versiones anteriores
        }
    }
    
    /**
     * Verifica si los permisos de ubicación están concedidos
     */
    fun hasLocationPermissions(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Verifica si el permiso de notificaciones está concedido
     * Para versiones anteriores a Android 13, siempre retorna true
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // No se requiere en versiones anteriores
        }
    }
    
    /**
     * Verifica si el permiso SCHEDULE_EXACT_ALARM está concedido
     * Para versiones anteriores a Android 12, siempre retorna true
     */
    fun hasScheduleExactAlarmPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.SCHEDULE_EXACT_ALARM
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // No se requiere en versiones anteriores
        }
    }
    
    /**
     * Obtiene un mensaje descriptivo de los permisos faltantes
     */
    fun getMissingPermissionsMessage(context: Context): String {
        val missing = getMissingPermissions(context)
        if (missing.isEmpty()) return "Todos los permisos concedidos"
        
        val permissionNames = missing.map { permission ->
            when (permission) {
                Manifest.permission.ACCESS_FINE_LOCATION -> "Ubicación precisa"
                Manifest.permission.ACCESS_COARSE_LOCATION -> "Ubicación aproximada"
                Manifest.permission.BLUETOOTH_CONNECT -> "Conexión Bluetooth"
                Manifest.permission.BLUETOOTH_SCAN -> "Escaneo Bluetooth"
                Manifest.permission.POST_NOTIFICATIONS -> "Notificaciones"
                Manifest.permission.SCHEDULE_EXACT_ALARM -> "Alarmas exactas"
                else -> permission.substringAfterLast(".")
            }
        }
        
        return "Permisos faltantes: ${permissionNames.joinToString(", ")}"
    }
}
