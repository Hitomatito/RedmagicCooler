package com.hitomatito.redmagicooler

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Worker para ajustes automáticos del fan usando WorkManager
 * Se ejecuta periódicamente para optimizar el scheduling del sistema
 */
class FanAdjustmentWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "FanAdjustmentWorker"
    }

    override suspend fun doWork(): Result {
        return try {
            Log.d(TAG, "Ejecutando ajuste automático del fan")

            // Aquí podríamos integrar lógica de monitoreo térmico y ajuste del fan
            // Por ahora, solo logueamos para probar
            val thermalMonitor = ThermalMonitor(context)
            val thermalData = thermalMonitor.getCurrentThermalData()

            Log.d(TAG, "Temperatura actual: ${thermalData.maxTemp}°C, Nivel: ${thermalData.tempLevel}, Velocidad recomendada: ${thermalData.recommendedSpeed}%")

            // En futuras iteraciones, podríamos enviar intent al servicio para ajustar el fan
            // si BLE está conectado

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error en FanAdjustmentWorker: ${e.message}", e)
            Result.retry()
        }
    }
}