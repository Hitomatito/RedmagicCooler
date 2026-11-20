package com.hitomatito.redmagicooler

internal enum class ThermalZoneRole {
    CPU,
    GPU,
    SKIN,
    BATTERY,
    CHARGER,
    MODEM,
    SOC,
    OTHER,
}

internal data class ThermalZoneReading(
    val index: Int,
    val type: String,
    val tempC: Float,
)

internal data class ThermalZoneProfile(
    val byIndex: Map<Int, ThermalZoneRole> = emptyMap(),
    val byType: Map<String, ThermalZoneRole> = emptyMap(),
)

internal const val MIN_ZONE_TEMP_C = 5f
internal const val MAX_ZONE_TEMP_C = 115f

internal fun isPlausibleZoneTemp(temp: Float): Boolean =
    temp.isFinite() && temp in MIN_ZONE_TEMP_C..MAX_ZONE_TEMP_C

/**
 * Clasifica el nombre de una zona térmica. El orden importa: primero se descartan
 * tensión/corriente y después batería, carga y módem para que nunca entren en el
 * pico del SoC aunque su nombre contenga "soc".
 */
internal fun classifyZoneRole(type: String): ThermalZoneRole {
    val normalized = type.trim().lowercase()
    if (normalized.isEmpty()) return ThermalZoneRole.OTHER

    if (normalized.contains("vbat") || normalized.contains("ibat") ||
        normalized.contains("bcl")) {
        return ThermalZoneRole.OTHER
    }
    if (normalized.contains("battery") || normalized.contains("batt") ||
        normalized.contains("bms")) {
        return ThermalZoneRole.BATTERY
    }
    if (normalized.contains("charg") || normalized.contains("chg") ||
        normalized.contains("usb") || normalized.contains("typec") ||
        normalized.contains("pd_therm")) {
        return ThermalZoneRole.CHARGER
    }
    if (normalized.contains("modem") || normalized.contains("mdm") ||
        normalized.contains("pa_therm") || normalized.contains("pa-therm") ||
        normalized.contains("mmw") || normalized.contains("wifi") ||
        normalized.contains("conn_therm")) {
        return ThermalZoneRole.MODEM
    }
    if (normalized.contains("skin") || normalized.contains("case") ||
        normalized.contains("shell") || normalized.contains("back") ||
        normalized.contains("cover") || normalized.contains("quiet_therm")) {
        return ThermalZoneRole.SKIN
    }
    if (normalized.contains("gpu") || normalized.contains("kgsl") ||
        normalized.contains("adreno") || normalized.contains("graphics")) {
        return ThermalZoneRole.GPU
    }
    if (normalized.contains("cpu") || normalized.contains("tsens") ||
        normalized.contains("cpuss") || normalized.contains("cluster") ||
        normalized.contains("silver") || normalized.contains("gold") ||
        normalized.contains("prime") || normalized.contains("apc")) {
        return ThermalZoneRole.CPU
    }
    if (normalized.contains("soc")) return ThermalZoneRole.SOC
    return ThermalZoneRole.OTHER
}

internal fun resolveZoneRole(
    index: Int,
    type: String,
    profile: ThermalZoneProfile,
): ThermalZoneRole {
    val normalized = type.trim().lowercase()
    return profile.byIndex[index]
        ?: profile.byType[normalized]
        ?: classifyZoneRole(normalized)
}

internal fun resolveZoneRole(
    reading: ThermalZoneReading,
    profile: ThermalZoneProfile,
): ThermalZoneRole = resolveZoneRole(reading.index, reading.type, profile)

internal fun aggregateZoneRoles(
    readings: List<ThermalZoneReading>,
    profile: ThermalZoneProfile = ThermalZoneProfile(),
): Map<ThermalZoneRole, Float> {
    val result = mutableMapOf<ThermalZoneRole, Float>()
    for (reading in readings) {
        if (!isPlausibleZoneTemp(reading.tempC)) continue
        val role = resolveZoneRole(reading, profile)
        val previous = result[role]
        if (previous == null || reading.tempC > previous) {
            result[role] = reading.tempC
        }
    }
    return result
}

internal fun hardwarePeakForRoles(roles: Map<ThermalZoneRole, Float>): Float = maxOf(
    roles[ThermalZoneRole.CPU] ?: 0f,
    roles[ThermalZoneRole.GPU] ?: 0f,
    roles[ThermalZoneRole.SOC] ?: 0f,
)

internal fun zoneInventoryText(
    zones: Map<Int, String>,
    profile: ThermalZoneProfile,
): String = zones.entries
    .sortedBy { it.key }
    .joinToString(",") { (index, type) ->
        "$index:$type:${resolveZoneRole(index, type, profile).name}"
    }

internal object ThermalZoneDefaults {
    // Recolectado sonda a sonda: fija por modelo el resultado verificado en vivo,
    // aunque hoy coincida con classifyZoneRole. Si el clasificador cambia, estas
    // filas protegen los modelos ya validados.
    private val byModel: Map<String, ThermalZoneProfile> = mapOf(
        "xiaomi|2107113sg|vili" to ThermalZoneProfile(
            byType = mapOf(
                "quiet_therm" to ThermalZoneRole.SKIN,
                "socd" to ThermalZoneRole.SOC,
            ),
        ),
    )

    fun forModel(modelKey: String): ThermalZoneProfile =
        byModel[modelKey.trim().lowercase()] ?: ThermalZoneProfile()
}
