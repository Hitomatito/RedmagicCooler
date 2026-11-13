package com.hitomatito.redmagicooler

import android.content.Context
import android.annotation.SuppressLint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitor térmico único compartido por la interfaz y CoolerService.
 * Evita que UI y servicio mantengan dos estados y dos ramificaciones de emergencia.
 */
object ThermalMonitorManager {
    private val _thermalData = MutableStateFlow(ThermalMonitor.ThermalData())
    val thermalData: StateFlow<ThermalMonitor.ThermalData> = _thermalData.asStateFlow()

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // El monitor es propiedad del proceso y siempre usa applicationContext.
    @SuppressLint("StaticFieldLeak")
    private var monitor: ThermalMonitor? = null

    /** Inicializa el monitor una sola vez en el proceso; seguro llamar varias veces. */
    fun initialize(context: Context) {
        if (monitor != null) return

        // El proceso propietario del estado térmico usa el contexto de aplicación.
        val appContext = context.applicationContext
        val sharedMonitor = ThermalMonitor(appContext)
        monitor = sharedMonitor
        sharedMonitor.startMonitoring(monitorScope) { data ->
            _thermalData.value = data
        }
    }

    /** Devuelve la última lectura o una instantánea nueva si el monitor está inicializado. */
    fun currentFreshData(): ThermalMonitor.ThermalData =
        monitor?.getCurrentThermalData() ?: ThermalMonitor.ThermalData()
}
