package com.hitomatito.redmagicooler

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Manager centralizado de permisos de la app.
 *
 * Los permisos se dividen en dos grupos:
 * - Permisos de runtime: se conceden con el launcher estándar de la app y se
 *   comprueban con checkSelfPermission (ubicación, Bluetooth, notificaciones).
 * - SCHEDULE_EXACT_ALARM: es un "special app access", NO se puede conceder con
 *   el launcher de permisos (solo desde Ajustes > Alarmas y recordatorios), por
 *   eso no forma parte de la lista de runtime y se comprueba aparte con
 *   AlarmManager.canScheduleExactAlarms().
 *
 * Para pedir permisos no hay que usar el launcher de la Activity directamente:
 * basta con invocar [requestPermissions] o [requestCriticalBlePermissions] y
 * procesar el resultado en el callback. La Activity solo debe registrarse una
 * vez con [register].
 */
object BlePermissionManager {

    private const val TAG = "BlePermissionManager"

    /**
     * Launcher de permisos registrado por la Activity. Lo guarda el manager para
     * que cualquier punto de la app pueda disparar la solicitud.
     */
    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null

    /**
     * Activity propietaria del launcher. Se usa para descartar resultados si la
     * Activity ya fue destruida y para no retener memoria innecesariamente.
     */
    private var owner: ComponentActivity? = null

    /**
     * Callback pendiente del último solicitud de permisos.
     */
    private var pendingCallback: ((PermissionResult) -> Unit)? = null

    /**
     * Solicitud encolada: si llega una petición mientras el diálogo está
     * abierto, se guarda para lanzarla en cuanto termine el actual.
     */
    private var queuedCallback: ((PermissionResult) -> Unit)? = null
    private var queuedTargets: List<String> = emptyList()

    /**
     * Indica si hay una solicitud de permisos en curso (diálogo abierto).
     * Permite a los call sites no disparar diálogos duplicados.
     */
    val isRequestInFlight: Boolean
        get() = pendingCallback != null

    /**
     * Resultado de una solicitud de permisos.
     *
     * @param granted Todos los permisos solicitados fueron concedidos
     * @param denied Permisos de esta solicitud que el usuario denegó
     * @param missing Permisos de runtime que siguen sin conceder (de la app completa)
     * @param message Mensaje en español listo para mostrar al usuario
     */
    data class PermissionResult(
        val granted: Boolean,
        val denied: List<String>,
        val missing: List<String>,
        val message: String
    )

    /**
     * Permisos de ubicación. En Android 12+ el escaneo BLE los sigue requiriendo
     * porque el manifest NO declara usesPermissionFlags="neverForLocation" en
     * BLUETOOTH_SCAN (ver AndroidManifest.xml).
     */
    private val LOCATION_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    /**
     * Permisos Bluetooth de Android 12+ (API 31+)
     */
    private val BLUETOOTH_PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE
    )

    // ---------------------------------------------------------------------
    // Solicitud centralizada de permisos
    // ---------------------------------------------------------------------

    /**
     * Registra la Activity que puede mostrar el diálogo de permisos.
     *
     * Debe invocarse una vez por instancia de Activity y antes de que la
     * Activity llegue a STARTED (lo ideal es desde el inicializador de la
     * Activity), porque registerForActivityRecord falla si la Activity ya está
     * en marcha.
     *
     * @param activity Activity que hospeda el launcher
     */
    fun register(activity: ComponentActivity) {
        if (owner === activity && permissionLauncher != null) {
            return
        }
        owner = activity
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val callback = pendingCallback
            val nextCallback = queuedCallback
            val nextTargets = queuedTargets
            pendingCallback = null
            queuedCallback = null
            queuedTargets = emptyList()

            val current = owner
            if (current == null || current.isFinishing || current.isDestroyed) {
                Log.w(TAG, "Resultado de permisos descartado: la Activity ya no está activa")
                return@registerForActivityResult
            }
            val denied = result.filterValues { !it }.keys.toList()
            val missing = getMissingPermissions(current)
            Log.d(
                TAG,
                "Permisos: ${result.size - denied.size}/${result.size} concedidos, " +
                    "${missing.size} pendientes en total"
            )
            callback?.invoke(
                PermissionResult(
                    granted = denied.isEmpty(),
                    denied = denied,
                    missing = missing,
                    message = buildMessage(missing, current)
                )
            )

            // Lanzar la solicitud que quedó encolada, si la hubo.
            if (nextCallback != null) {
                request(current, nextTargets, nextCallback)
            }
        }
    }

    /**
     * Libera el launcher. Llamar desde onDestroy de la Activity para no
     * retenerla.
     */
    fun unregister(activity: ComponentActivity) {
        if (owner === activity) {
            permissionLauncher = null
            pendingCallback = null
            queuedCallback = null
            queuedTargets = emptyList()
            owner = null
        }
    }

    /**
     * Pide los permisos de runtime que falten (ubicación, Bluetooth y
     * notificaciones).
     *
     * Si no falta ninguno, el callback se invoca de inmediato con granted=true.
     *
     * @param activity Activity desde la que se muestra el diálogo
     * @param onResult Callback con el resultado de la solicitud
     */
    fun requestPermissions(activity: Activity, onResult: (PermissionResult) -> Unit) {
        request(activity, getRequiredPermissions().toList(), onResult)
    }

    /**
     * Pide solo los permisos imprescindibles para escanear y controlar el cooler
     * (ubicación y Bluetooth). No pide notificaciones ni alarmas exactas, que son
     * opcionales.
     *
     * @param activity Activity desde la que se muestra el diálogo
     * @param onResult Callback con el resultado de la solicitud
     */
    fun requestCriticalBlePermissions(
        activity: Activity,
        onResult: (PermissionResult) -> Unit
    ) {
        request(activity, getCriticalBlePermissions(), onResult)
    }

    private fun request(
        activity: Activity,
        targets: List<String>,
        onResult: (PermissionResult) -> Unit
    ) {
        val missing = targets.filterNot { isGranted(activity, it) }
        if (missing.isEmpty()) {
            onResult(
                PermissionResult(
                    granted = true,
                    denied = emptyList(),
                    missing = emptyList(),
                    message = "Todos los permisos concedidos"
                )
            )
            return
        }

        val launcher = permissionLauncher
        if (launcher == null) {
            Log.e(
                TAG,
                "No hay launcher registrado. Llama a BlePermissionManager.register(activity) " +
                    "desde el inicializador de la Activity."
            )
            onResult(
                PermissionResult(
                    granted = false,
                    denied = missing,
                    missing = missing,
                    message = buildMessage(missing, activity)
                )
            )
            return
        }

        // Si ya hay un diálogo abierto, encolar para pedir el resto después.
        if (pendingCallback != null) {
            queuedTargets = (queuedTargets + missing).distinct()
            queuedCallback = onResult
            Log.w(TAG, "Solicitud en curso, se encolan: ${missing.joinToString()}")
            return
        }

        pendingCallback = onResult
        Log.d(TAG, "Solicitando permisos: ${missing.joinToString()}")
        launcher.launch(missing.toTypedArray())
    }

    // ---------------------------------------------------------------------
    // Consulta de permisos
    // ---------------------------------------------------------------------

    /**
     * Permisos que se pueden solicitar en runtime con el launcher estándar.
     * SCHEDULE_EXACT_ALARM queda fuera a propósito: no es un permiso de runtime.
     */
    fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        permissions.addAll(LOCATION_PERMISSIONS)

        // Permisos BLE para Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(BLUETOOTH_PERMISSIONS)
        }

        // Permiso de notificaciones para Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions.toTypedArray()
    }

    /**
     * Permisos imprescindibles para escanear y controlar el cooler.
     * Excluye los opcionales (notificaciones y alarmas exactas) para que el
     * servicio en background pueda trabajar sin ellos.
     */
    fun getCriticalBlePermissions(): List<String> {
        val critical = mutableListOf<String>()
        critical.addAll(LOCATION_PERMISSIONS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            critical.addAll(BLUETOOTH_PERMISSIONS)
        }

        return critical
    }

    /**
     * Verifica si todos los permisos de runtime están concedidos
     */
    fun hasAllPermissions(context: Context): Boolean {
        return getRequiredPermissions().all { isGranted(context, it) }
    }

    /**
     * Verifica si los permisos BLE críticos están concedidos (excluye permisos opcionales como notificaciones)
     * Útil para servicios en background donde algunos permisos son opcionales
     */
    fun hasCriticalBlePermissions(context: Context): Boolean {
        return getCriticalBlePermissions().all { isGranted(context, it) }
    }

    /**
     * Obtiene la lista de permisos de runtime que faltan
     */
    fun getMissingPermissions(context: Context): List<String> {
        return getRequiredPermissions().filterNot { isGranted(context, it) }
    }

    /**
     * Verifica si el permiso BLUETOOTH_CONNECT está concedido
     * Para versiones anteriores a Android 12, siempre retorna true
     */
    fun hasBluetoothConnectPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)
    }

    /**
     * Verifica si el permiso BLUETOOTH_SCAN está concedido
     * Para versiones anteriores a Android 12, siempre retorna true
     */
    fun hasBluetoothScanPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            isGranted(context, Manifest.permission.BLUETOOTH_SCAN)
    }

    /**
     * Verifica si el permiso de notificaciones está concedido
     * Para versiones anteriores a Android 13, siempre retorna true
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Verifica si se pueden programar alarmas exactas.
     * SCHEDULE_EXACT_ALARM no es un permiso de runtime: se concede en Ajustes, por
     * lo que la fuente de verdad es AlarmManager.canScheduleExactAlarms().
     * Para versiones anteriores a Android 12, siempre retorna true
     */
    fun hasScheduleExactAlarmPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return false
        return alarmManager.canScheduleExactAlarms()
    }

    /**
     * Obtiene un mensaje descriptivo de los permisos faltantes
     */
    fun getMissingPermissionsMessage(context: Context): String {
        return buildMessage(getMissingPermissions(context), context)
    }

    // ---------------------------------------------------------------------
    // Internos
    // ---------------------------------------------------------------------

    private fun buildMessage(missing: List<String>, context: Context): String {
        val names = missing.map { describePermission(it) }.toMutableList()

        // Las alarmas exactas no se pueden pedir en runtime: se avisa aparte para
        // que el usuario sepa dónde activarlas.
        if (!hasScheduleExactAlarmPermission(context) &&
            Manifest.permission.SCHEDULE_EXACT_ALARM !in missing
        ) {
            names.add("Alarmas exactas (opcional, Ajustes > Alarmas y recordatorios)")
        }

        if (names.isEmpty()) return "Todos los permisos concedidos"

        return "Permisos faltantes: ${names.joinToString(", ")}"
    }

    private fun describePermission(permission: String): String {
        return when (permission) {
            Manifest.permission.ACCESS_FINE_LOCATION -> "Ubicación precisa"
            Manifest.permission.ACCESS_COARSE_LOCATION -> "Ubicación aproximada"
            Manifest.permission.BLUETOOTH_CONNECT -> "Conexión Bluetooth"
            Manifest.permission.BLUETOOTH_SCAN -> "Escaneo Bluetooth"
            Manifest.permission.BLUETOOTH_ADVERTISE -> "Bluetooth (anuncio)"
            Manifest.permission.POST_NOTIFICATIONS -> "Notificaciones"
            Manifest.permission.SCHEDULE_EXACT_ALARM -> "Alarmas exactas"
            else -> permission.substringAfterLast(".")
        }
    }

    private fun isGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
