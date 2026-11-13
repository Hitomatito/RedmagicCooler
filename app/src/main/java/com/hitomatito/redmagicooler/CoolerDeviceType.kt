package com.hitomatito.redmagicooler

import android.bluetooth.le.ScanRecord
import com.hitomatito.redmagicooler.model.CoolerBleConstants

/**
 * Tipos de dispositivos cooler RedMagic/Nubia soportados.
 *
 * Detección basada en el protocolo real de la app oficial (cn.nubia.externdevice):
 * el cooler publicita el service UUID `00004a41-0000-1000-8000-00805f9b34fb` y/o
 * manufacturer data con company ID 2250 (0x08CA) y bytes [mainType=5][subType=gen].
 * Ver CoolerBleConstants y CoolerBleDetector.
 */
enum class CoolerDeviceType(
    val deviceName: String,
    val manufacturerSubTypes: List<Int>,
    val generation: Int,
    val description: String,
    val imageRes: Int
) {
    JACKET_1(
        deviceName = "Enfriador Doble Núcleo",
        manufacturerSubTypes = listOf(1),
        generation = 1,
        description = "Primera generación de enfriadores RedMagic",
        imageRes = R.drawable.cooler_dual_core
    ),

    JACKET_2(
        deviceName = "Enfriador Turbo",
        manufacturerSubTypes = listOf(2),
        generation = 2,
        description = "Segunda generación con mejoras de rendimiento",
        imageRes = R.drawable.cooler_turbo
    ),

    JACKET_3(
        deviceName = "Magcooler",
        manufacturerSubTypes = listOf(3),
        generation = 3,
        description = "Tercera generación con diseño optimizado",
        imageRes = R.drawable.cooler_magcooler
    ),

    JACKET_4(
        deviceName = "Heat Sink 4 Pro",
        manufacturerSubTypes = listOf(4),
        generation = 4,
        description = "Cuarta generación profesional",
        imageRes = R.drawable.cooler_heat_sink_4_pro
    ),

    // Jacket5 y Jacket5 Lite publicitan el mismo subType (5): se distinguen por nombre
    JACKET_5(
        deviceName = "Heat Sink 5 Pro",
        manufacturerSubTypes = listOf(5),
        generation = 5,
        description = "Quinta generación con control RGB avanzado",
        imageRes = R.drawable.cooler_heat_sink_5_pro
    ),

    JACKET_5_LITE(
        deviceName = "Heat Sink 5",
        manufacturerSubTypes = listOf(5),
        generation = 5,
        description = "Quinta generación, versión estándar",
        imageRes = R.drawable.cooler_heat_sink_5
    ),

    // La app oficial publicita (5,4)|(5,6) para Jacket6
    JACKET_6(
        deviceName = "Heat Sink 6",
        manufacturerSubTypes = listOf(4, 6),
        generation = 6,
        description = "Sexta generación con eficiencia mejorada",
        imageRes = R.drawable.cooler_heat_sink_6
    ),

    // Jacket6 Pro y Jacket6 Pro+ publicitan (5,5)|(5,6): se distinguen por nombre
    JACKET_6_PRO(
        deviceName = "Heat Sink 6 Pro",
        manufacturerSubTypes = listOf(5, 6),
        generation = 6,
        description = "Versión profesional de sexta generación",
        imageRes = R.drawable.cooler_heat_sink_6_pro
    ),

    JACKET_6_PRO_PLUS(
        deviceName = "Heat Sink 6 Pro+",
        manufacturerSubTypes = listOf(5, 6),
        generation = 6,
        description = "Versión profesional plus de sexta generación",
        imageRes = R.drawable.cooler_heat_sink_6_pro_plus
    );

    companion object {
        /**
         * Identifica el tipo de cooler a partir del scan record BLE.
         * Es el método de detección principal (protocolo de la app oficial):
         * requiere el service de advertising 00004a41 y/o manufacturer 2250 con mainType 5.
         * @return tipo detectado o null si el dispositivo no parece un cooler RedMagic
         */
        fun fromScanRecord(scanRecord: ScanRecord?): CoolerDeviceType? {
            if (scanRecord == null) return null
            val hasAdService = scanRecord.serviceUuids?.any { parcel ->
                parcel.uuid == CoolerBleConstants.ADVERTISING_SERVICE_UUID
            } == true
            val manufacturer = scanRecord.getManufacturerSpecificData(CoolerBleConstants.MANUFACTURER_COMPANY_ID)
            if (!hasAdService && manufacturer == null) return null

            val subType = manufacturer
                ?.takeIf { it.size >= 2 && (it[0].toInt() and 0xFF) == CoolerBleConstants.MANUFACTURER_MAIN_TYPE_FAN }
                ?.let { it[1].toInt() and 0xFF }

            val byManufacturer = subType?.let { s ->
                entries.filter { s in it.manufacturerSubTypes }
            } ?: emptyList()

            val name = scanRecord.deviceName
            val byName = name?.let { n ->
                entries.filter { it.matchesBleName(n) }
            } ?: emptyList()

            val byBoth = byManufacturer.intersect(byName.toSet())
            return when {
                byBoth.size == 1 -> byBoth.single()
                // Desambiguación por modelo cuando manufacturer y nombre no deciden:
                // subType 4 → Jacket4 (más probable sin evidencia de 6ª gen)
                // subType 5 sin evidencia de "Pro" → Jacket5 Lite (más probable en G5)
                // subType 6 → primera gen 6 (Jacket6)
                byBoth.size > 1 -> byBoth.sortedBy { it.generation }.first()
                byManufacturer.size == 1 -> byManufacturer.single()
                byManufacturer.size > 1 -> byManufacturer.sortedBy { it.generation }.first()
                byName.size == 1 -> byName.single()
                byName.size > 1 -> byName.sortedBy { it.generation }.first()
                else -> null
            }
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
         * Obtiene todos los nombres de dispositivos para mostrar en UI
         */
        fun getAllDeviceNames(): List<String> {
            return CoolerDeviceType.entries.map { it.deviceName }
        }
    }

    /**
     * Verifica si este tipo de dispositivo es compatible con el nombre BLE detectado.
     * Sirve para desambiguar cuando varios modelos comparten subType (p.ej. Jacket5/Lite).
     */
    fun matchesBleName(bleName: String?): Boolean {
        if (bleName == null) return false

        val isRedMagicDevice = bleName.contains("Magcooler", ignoreCase = true) ||
                               bleName.contains("MagCooler", ignoreCase = true) ||
                               bleName.contains("RM ", ignoreCase = true) ||
                               bleName.contains("RedMagic", ignoreCase = true) ||
                               bleName.contains("Red Magic", ignoreCase = true) ||
                               bleName.contains("Nubia", ignoreCase = true)

        if (!isRedMagicDevice) {
            return false
        }

        val n = bleName.lowercase()
        fun hasAny(vararg parts: String) = parts.any { n.contains(it.lowercase()) }

        return when (this) {
            JACKET_1 -> hasAny("dual", "doble", "jacket 1", "jacket1")
            JACKET_2 -> hasAny("turbo", "jacket 2", "jacket2")
            JACKET_3 -> hasAny("magcooler 3", "magcooler3", "jacket 3", "jacket3")
            JACKET_4 -> n.contains("4")
            JACKET_5 -> n.contains("5") && hasAny("pro") && !hasAny("plus", "pro+", "pro plus")
            JACKET_5_LITE -> n.contains("5") && !hasAny("pro")
            JACKET_6 -> n.contains("6") && !hasAny("pro")
            JACKET_6_PRO -> n.contains("6") && hasAny("pro") && !hasAny("plus", "pro+", "pro plus")
            JACKET_6_PRO_PLUS -> n.contains("6") && hasAny("plus", "pro+", "pro plus")
        }
    }

    /**
     * Icono sugerido para este tipo de dispositivo (para UI futura)
     */
    fun getSuggestedIcon(): String {
        return when (generation) {
            1, 2 -> "🌀"
            3, 4 -> "❄️"
            5, 6 -> "🔷"
            else -> "Dispositivo"
        }
    }
}
