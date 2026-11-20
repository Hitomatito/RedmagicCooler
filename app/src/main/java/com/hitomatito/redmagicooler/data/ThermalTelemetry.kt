package com.hitomatito.redmagicooler.data

import android.content.Context
import android.util.Log
import com.hitomatito.redmagicooler.BuildConfig
import com.hitomatito.redmagicooler.ThermalMonitor
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * Telemetría térmica local en JSONL para calibrar el modo automático.
 * No sale del dispositivo y solo se activa en builds debug por defecto.
 */
class ThermalTelemetry(
    context: Context,
    private val enabled: Boolean = BuildConfig.DEBUG,
) {
    companion object {
        private const val TAG = "ThermalTelemetry"
        private const val VERSION = 1
        private const val MAX_BYTES = 1_048_576L
        private const val FILE_NAME = "thermal.jsonl"
        private const val BACKUP_NAME = "thermal.1.jsonl"
    }

    private val directory = File(context.filesDir, "thermal")
    private val file = File(directory, FILE_NAME)
    private val backup = File(directory, BACKUP_NAME)
    private var lastInventory: String? = null

    @Synchronized
    fun logSample(data: ThermalMonitor.ThermalData, appliedSpeed: Int) {
        if (!enabled || data.maxTemp <= 0f) return
        try {
            directory.mkdirs()
            rotateIfNeeded()
            logInventoryIfNeeded(data.zoneInventory)
            append(
                JSONObject().apply {
                    put("kind", "sample")
                    put("v", VERSION)
                    put("app", BuildConfig.VERSION_NAME)
                    put("t", System.currentTimeMillis())
                    put("model", modelKey())
                    put("battery", oneDecimal(data.batteryTemp))
                    put("cpu", oneDecimal(data.cpuTemp))
                    put("gpu", oneDecimal(data.gpuTemp))
                    put("skin", oneDecimal(data.skinTemp))
                    put("hwPeak", oneDecimal(data.hardwarePeakTemp))
                    put("status", data.thermalStatus)
                    put("maxTemp", oneDecimal(data.maxTemp))
                    put("level", data.tempLevel.name)
                    put("charging", data.charging)
                    put("screen", data.screenInteractive)
                    put("recommended", data.recommendedSpeed)
                    put("applied", appliedSpeed)
                    put("source", data.controlSource)
                    put("risk", data.riskConfirmed)
                    put("noise", data.noisePreference)
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error escribiendo telemetría: ${e.message}")
        }
    }

    private fun logInventoryIfNeeded(inventory: String) {
        if (inventory.isEmpty() || inventory == lastInventory) return
        append(
            JSONObject().apply {
                put("kind", "zones")
                put("v", VERSION)
                put("app", BuildConfig.VERSION_NAME)
                put("t", System.currentTimeMillis())
                put("model", modelKey())
                put("zones", inventory)
            },
        )
        lastInventory = inventory
    }

    private fun append(json: JSONObject) {
        file.appendText(json.toString() + "\n")
    }

    private fun rotateIfNeeded() {
        if (file.length() < MAX_BYTES) return
        backup.delete()
        if (!file.renameTo(backup)) {
            file.delete()
        }
        lastInventory = null
    }

    private fun modelKey(): String = ThermalZoneProfileRepository.currentModelKey()

    private fun oneDecimal(value: Float): Double =
        (value * 10f).roundToInt() / 10.0
}
