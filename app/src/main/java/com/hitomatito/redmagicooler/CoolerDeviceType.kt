package com.hitomatito.redmagicooler

import java.util.UUID

/**
 * Tipos de dispositivos cooler RedMagic/Nubia soportados
 * Cada tipo tiene su UUID de advertising BLE único y nombre comercial
 */
enum class CoolerDeviceType(
    val deviceName: String,
    val advertisingUUID: UUID,
    val generation: Int,
    val description: String,
    val imageRes: Int
) {
    JACKET_1(
        deviceName = "Enfriador Doble Núcleo",
        advertisingUUID = UUID.fromString("70e03463-0478-4596-a826-e0002d27618a"),
        generation = 1,
        description = "Primera generación de enfriadores RedMagic",
        imageRes = R.drawable.cooler_dual_core
    ),
    
    JACKET_2(
        deviceName = "Enfriador Turbo",
        advertisingUUID = UUID.fromString("3823dac1-f7be-4a11-a145-83b2b2f35e5e"),
        generation = 2,
        description = "Segunda generación con mejoras de rendimiento",
        imageRes = R.drawable.cooler_turbo
    ),
    
    JACKET_3(
        deviceName = "Magcooler",
        advertisingUUID = UUID.fromString("85e1fd9d-7ca1-4be3-8131-1edde011a47d"),
        generation = 3,
        description = "Tercera generación con diseño optimizado",
        imageRes = R.drawable.cooler_magcooler
    ),
    
    JACKET_4(
        deviceName = "Heat Sink 4 Pro",
        advertisingUUID = UUID.fromString("1e3f2686-e85f-4c25-8dd3-ff6164d46a16"),
        generation = 4,
        description = "Cuarta generación profesional",
        imageRes = R.drawable.cooler_heat_sink_4_pro
    ),
    
    JACKET_5(
        deviceName = "Heat Sink 5 Pro",
        advertisingUUID = UUID.fromString("b739be84-9cca-44ed-8653-4e4c7f16a9a4"),
        generation = 5,
        description = "Quinta generación con control RGB avanzado",
        imageRes = R.drawable.cooler_heat_sink_5_pro
    ),
    
    JACKET_5_LITE(
        deviceName = "Heat Sink 5",
        advertisingUUID = UUID.fromString("a8f345b9-d726-4707-910a-fd637c2b00a7"),
        generation = 5,
        description = "Quinta generación, versión estándar",
        imageRes = R.drawable.cooler_heat_sink_5
    ),
    
    JACKET_6(
        deviceName = "Heat Sink 6",
        advertisingUUID = UUID.fromString("e39bc7af-ca69-4977-9fe7-ae3ad560f063"),
        generation = 6,
        description = "Sexta generación con eficiencia mejorada",
        imageRes = R.drawable.cooler_heat_sink_6
    ),
    
    JACKET_6_PRO(
        deviceName = "Heat Sink 6 Pro",
        advertisingUUID = UUID.fromString("9c24a801-7666-40c1-9589-26a89425a0b0"),
        generation = 6,
        description = "Versión profesional de sexta generación",
        imageRes = R.drawable.cooler_heat_sink_6_pro
    );

    companion object {
        /**
         * Encuentra el tipo de dispositivo por UUID de advertising
         */
        fun fromAdvertisingUUID(uuid: UUID): CoolerDeviceType? {
            return CoolerDeviceType.entries.find { it.advertisingUUID == uuid }
        }
        
        /**
         * Encuentra el tipo de dispositivo por nombre
         */
        fun fromDeviceName(name: String): CoolerDeviceType? {
            return CoolerDeviceType.entries.find {
                it.deviceName.equals(name, ignoreCase = true) 
            }
        }
        
        /**
         * Obtiene todos los UUIDs para usar en filtros de escaneo
         */
        fun getAllAdvertisingUUIDs(): List<UUID> {
            return CoolerDeviceType.entries.map { it.advertisingUUID }
        }
        
        /**
         * Obtiene todos los nombres de dispositivos para mostrar en UI
         */
        fun getAllDeviceNames(): List<String> {
            return CoolerDeviceType.entries.map { it.deviceName }
        }
    }
    
    /**
     * Verifica si este tipo de dispositivo es compatible con el nombre BLE detectado
     * Validación estricta para evitar falsos positivos
     */
    fun matchesBleName(bleName: String?): Boolean {
        if (bleName == null) return false
        
        // CRÍTICO: Solo aceptar nombres que contengan marcadores de RedMagic/Nubia
        val isRedMagicDevice = bleName.contains("Magcooler", ignoreCase = true) ||
                               bleName.contains("MagCooler", ignoreCase = true) ||
                               bleName.contains("RM ", ignoreCase = true) ||
                               bleName.contains("RedMagic", ignoreCase = true) ||
                               bleName.contains("Red Magic", ignoreCase = true) ||
                               bleName.contains("Nubia", ignoreCase = true)
        
        if (!isRedMagicDevice) {
            return false // No es un dispositivo RedMagic, rechazar inmediatamente
        }
        
        // Ahora verificar patrones específicos por generación
        return when (this) {
            JACKET_1 -> bleName.contains("Jacket", ignoreCase = true) ||
                        bleName.contains("Dual", ignoreCase = true)
            JACKET_2 -> bleName.contains("Turbo", ignoreCase = true)
            JACKET_3 -> bleName.contains("Magcooler 3", ignoreCase = true) ||
                        bleName.contains("MagCooler3", ignoreCase = true)
            JACKET_4 -> bleName.contains("4", ignoreCase = true)
            JACKET_5 -> bleName.contains("5pro", ignoreCase = true) ||
                        (bleName.contains("5", ignoreCase = true) && bleName.contains("Pro", ignoreCase = true))
            JACKET_5_LITE -> bleName.contains("5 Lite", ignoreCase = true) ||
                             bleName.contains("5Lite", ignoreCase = true)
            JACKET_6 -> bleName.contains("6", ignoreCase = true) && !bleName.contains("Pro", ignoreCase = true)
            JACKET_6_PRO -> bleName.contains("6", ignoreCase = true) && bleName.contains("Pro", ignoreCase = true)
        }
    }
    
    /**
     * Icono sugerido para este tipo de dispositivo (para UI futura)
     */
    fun getSuggestedIcon(): String {
        return when (generation) {
            1, 2 -> "🌀" // Generaciones antiguas
            3, 4 -> "❄️" // Generaciones intermedias
            5, 6 -> "🔷" // Generaciones modernas
            else -> "Dispositivo"
        }
    }
}
