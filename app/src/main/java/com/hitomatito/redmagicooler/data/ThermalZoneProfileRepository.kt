package com.hitomatito.redmagicooler.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.hitomatito.redmagicooler.ThermalZoneProfile
import com.hitomatito.redmagicooler.ThermalZoneRole
import org.json.JSONObject

/**
 * Overrides de mapeo de zonas térmicas por modelo de teléfono.
 * La tabla base vive en [com.hitomatito.redmagicooler.ThermalZoneDefaults]; aquí
 * solo se leen correcciones específicas del modelo, escritas a mano en prefs
 * (JSON con claves byIndex/byType) para depuración en dispositivos problemáticos.
 */
internal class ThermalZoneProfileRepository(context: Context) {

    companion object {
        private const val TAG = "ThermalZoneProfile"
        private const val PREFS_NAME = "thermal_zone_profiles"
        private const val KEY_PREFIX = "profile_"

        fun currentModelKey(): String =
            "${Build.MANUFACTURER}|${Build.MODEL}|${Build.DEVICE}".lowercase()
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(modelKey: String): ThermalZoneProfile {
        val raw = prefs.getString(KEY_PREFIX + modelKey, null) ?: return ThermalZoneProfile()
        return try {
            val json = JSONObject(raw)
            ThermalZoneProfile(
                byIndex = json.optJSONObject("byIndex")?.toIndexMap() ?: emptyMap(),
                byType = json.optJSONObject("byType")?.toTypeMap() ?: emptyMap(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando zonas de $modelKey: ${e.message}")
            ThermalZoneProfile()
        }
    }
}

private fun JSONObject.toIndexMap(): Map<Int, ThermalZoneRole> {
    val result = mutableMapOf<Int, ThermalZoneRole>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val index = key.toIntOrNull() ?: continue
        val role = roleFromName(optString(key)) ?: continue
        result[index] = role
    }
    return result
}

private fun JSONObject.toTypeMap(): Map<String, ThermalZoneRole> {
    val result = mutableMapOf<String, ThermalZoneRole>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val role = roleFromName(optString(key)) ?: continue
        result[key.lowercase()] = role
    }
    return result
}

private fun roleFromName(name: String): ThermalZoneRole? =
    ThermalZoneRole.entries.firstOrNull { it.name == name.uppercase() }
