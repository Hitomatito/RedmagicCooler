package com.hitomatito.redmagicooler.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hitomatito.redmagicooler.ThermalLogic
import com.hitomatito.redmagicooler.ThermalMonitor
import com.hitomatito.redmagicooler.model.CoolerProfile
import com.hitomatito.redmagicooler.model.LightEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * Pantalla de configuración completa para un perfil de cooler específico
 * Incluye control de velocidad, RGB y opciones del perfil
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileConfigScreen(
    profile: CoolerProfile,
    thermalDataFlow: StateFlow<ThermalMonitor.ThermalData>,
    actualSpeed: Int = -1,
    isConnecting: Boolean = false,
    onNavigateBack: () -> Unit,
    onNavigateToRGB: () -> Unit,
    onSetFanSpeed: (Int) -> Unit,
    onToggleAutoMode: () -> Unit,
    onNoisePreferenceChange: (Int) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDeleteProfile: () -> Unit,
    onRenameProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
    connectionError: String? = null,
    onDismissError: () -> Unit = {}
) {
    var fanSpeed by remember(profile.fanSpeed) { mutableIntStateOf(profile.fanSpeed) }
    // Contador de solicitudes explícitas (chip/toque en pista): garantiza que
    // re-pedir la misma velocidad vuelva a escribir en el cooler aunque el
    // debounce no se reinicie porque fanSpeed no cambió.
    var speedRequest by remember { mutableIntStateOf(0) }
    var isDragging by remember { mutableStateOf(false) }
    var isApplying by remember { mutableStateOf(false) }
    // Velocidad elegida por el usuario todavía no confirmada por el cooler.
    // Mientras no se confirme, una lectura tardía no debe pisar el valor del slider.
    var pendingUserSpeed by remember { mutableStateOf<Int?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(profile.name) }
    val scrollState = rememberScrollState()

    // Reflejar la velocidad que reporta el cooler solo cuando coincide con el
    // último valor enviado (o no hay ninguno pendiente). Las lecturas tardías
    // con otro valor se ignoran para no pisar lo que el usuario acaba de elegir.
    LaunchedEffect(actualSpeed) {
        if (profile.isConnected && actualSpeed >= 0 && !isDragging) {
            if (pendingUserSpeed == null || pendingUserSpeed == actualSpeed) {
                pendingUserSpeed = null
                fanSpeed = actualSpeed
            }
        }
    }

    // Red de seguridad: si el cooler nunca confirma (o el gesto volvió al mismo
    // valor y no reinició el debounce), liberar el flag para no ignorar lecturas
    // futuras indefinidamente.
    LaunchedEffect(pendingUserSpeed) {
        val pending = pendingUserSpeed ?: return@LaunchedEffect
        delay(1500)
        if (pendingUserSpeed == pending) pendingUserSpeed = null
    }

    // Aplicación automática con debounce
    LaunchedEffect(fanSpeed, speedRequest, profile.isConnected, profile.isAutoMode) {
        if (profile.isConnected && !profile.isAutoMode) {
            isApplying = true
            try {
                delay(500)
                onSetFanSpeed(fanSpeed)
                // No liberar pendingUserSpeed aquí: debe mantenerse hasta que la
                // lectura del cooler confirme el valor o actúe la red de seguridad.
                delay(300)
            } finally {
                isApplying = false
            }
        } else {
            isApplying = false
            pendingUserSpeed = null
        }
    }

    // Diálogo de confirmación para eliminar
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Eliminar perfil") },
            text = { 
                Text("¿Estás seguro de que deseas eliminar el perfil '${profile.displayName}'? Esta acción no se puede deshacer.") 
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        onDeleteProfile()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    // Diálogo para renombrar
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Renombrar perfil") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Nombre del perfil") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            onRenameProfile(newName)
                            showRenameDialog = false
                        }
                    }
                ) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(profile.displayName)
                        Text(
                            text = profile.deviceType.deviceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            newName = profile.name
                            showRenameDialog = true
                        }
                    ) {
                        Icon(Icons.Filled.Edit, "Renombrar")
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Filled.Delete, 
                            "Eliminar",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Aviso persistente: el cooler deja de responder tras reconectar
            if (connectionError != null) {
                ConnectionErrorBanner(
                    message = connectionError,
                    onDismiss = onDismissError
                )
            }

            // Imagen del modelo y estado de conexión
            ProfileHeroCard(
                profile = profile,
                onConnect = onConnect,
                onDisconnect = onDisconnect
            )

            // Control RGB (disponible si está conectado)
            if (profile.isConnected || profile.isAutoMode) {
                RgbCard(
                    profile = profile,
                    onNavigateToRGB = onNavigateToRGB
                )
            }

            // Monitor térmico: el flujo se lee dentro de la tarjeta para que
            // cada lectura no recomponga el resto de la pantalla.
            ThermalCard(
                thermalDataFlow = thermalDataFlow,
                isAutoMode = profile.isAutoMode,
                isConnecting = isConnecting,
                isMacBound = profile.macAddress.isNotBlank(),
                onToggleAutoMode = onToggleAutoMode
            )

            NoisePreferenceCard(
                value = profile.noisePreference,
                onCommit = { onNoisePreferenceChange(it) },
            )

            // Control de velocidad (solo si está conectado)
            if (profile.isConnected) {
                SpeedControlCard(
                    fanSpeed = fanSpeed,
                    isAutoMode = profile.isAutoMode,
                    isApplying = isApplying,
                    onSpeedChange = {
                        pendingUserSpeed = it
                        fanSpeed = it
                    },
                    onApplySpeed = { speedRequest++ },
                    onDraggingChange = { isDragging = it }
                )
            }

            // Información del perfil
            ProfileInfoCard(profile = profile)
        }
    }
}

/**
 * Tarjeta principal del perfil: imagen grande del cooler y estado de conexión.
 */
@Composable
private fun ProfileHeroCard(
    profile: CoolerProfile,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isInAutoMode = profile.isAutoMode
    val connected = profile.isConnected
    val glowColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    val glowColors = remember(glowColor) { listOf(glowColor, Color.Transparent) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(232.dp)
                    .drawBehind {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = glowColors,
                                center = center,
                                radius = size.minDimension * 0.75f
                            )
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(profile.deviceType.imageRes),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp, vertical = 16.dp)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (connected || isInAutoMode) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            }
                        )
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = when {
                            isInAutoMode && connected -> "Modo automático"
                            isInAutoMode -> "Modo automático · reconectando"
                            connected -> "Conectado"
                            else -> "Desconectado"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isInAutoMode) "Servicio en segundo plano" else profile.macAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                when {
                    isInAutoMode -> Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            text = "AUTO",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }

                    connected -> OutlinedButton(onClick = onDisconnect) {
                        Text("Desconectar")
                    }

                    else -> Button(onClick = onConnect) {
                        Text("Conectar")
                    }
                }
            }
        }
    }
}

/**
 * Tarjeta de iluminación RGB con vista previa del color actual.
 */
@Composable
private fun RgbCard(
    profile: CoolerProfile,
    onNavigateToRGB: () -> Unit
) {
    val rgb = profile.rgbConfig
    val previewColor = if (rgb != null) {
        Color(rgb.red, rgb.green, rgb.blue)
    } else {
        MaterialTheme.colorScheme.primary
    }
    val previewBrush = remember(rgb?.effect, previewColor) {
        when {
            rgb == null -> SolidColor(previewColor)
            rgb.effect == LightEffect.COLORFUL || rgb.effect == LightEffect.BREATH_FULLCOLOR ->
                Brush.sweepGradient(
                    listOf(
                        Color(0xFFE53935),
                        Color(0xFFFB8C00),
                        Color(0xFFFDD835),
                        Color(0xFF43A047),
                        Color(0xFF1E88E5),
                        Color(0xFF8E24AA),
                        Color(0xFFE53935)
                    )
                )

            else -> SolidColor(previewColor)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (rgb != null) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(previewBrush)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "RGB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Iluminación RGB",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = rgb?.let { effectName(it.effect) } ?: "Sin configurar",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FilledTonalButton(onClick = onNavigateToRGB) {
                Text("Configurar")
            }
        }
    }
}

/**
 * Monitor térmico con interruptor de modo automático.
 */
@Composable
private fun ThermalCard(
    thermalDataFlow: StateFlow<ThermalMonitor.ThermalData>,
    isAutoMode: Boolean,
    isConnecting: Boolean,
    isMacBound: Boolean,
    onToggleAutoMode: () -> Unit
) {
    val thermalData by thermalDataFlow.collectAsState()
    val hasTemperature = thermalData.maxTemp > 0f

    val levelLabel = when (thermalData.tempLevel) {
        ThermalMonitor.TempLevel.SAFE -> "Normal"
        ThermalMonitor.TempLevel.WARM -> "Templado"
        ThermalMonitor.TempLevel.HOT -> "Alta"
        ThermalMonitor.TempLevel.CRITICAL -> "Crítica"
    }

    val chipColor = when (thermalData.tempLevel) {
        ThermalMonitor.TempLevel.SAFE -> MaterialTheme.colorScheme.secondaryContainer
        ThermalMonitor.TempLevel.WARM -> MaterialTheme.colorScheme.tertiaryContainer
        ThermalMonitor.TempLevel.HOT -> MaterialTheme.colorScheme.errorContainer
        ThermalMonitor.TempLevel.CRITICAL -> MaterialTheme.colorScheme.error
    }
    val chipContent = when (thermalData.tempLevel) {
        ThermalMonitor.TempLevel.SAFE -> MaterialTheme.colorScheme.onSecondaryContainer
        ThermalMonitor.TempLevel.WARM -> MaterialTheme.colorScheme.onTertiaryContainer
        ThermalMonitor.TempLevel.HOT -> MaterialTheme.colorScheme.onErrorContainer
        ThermalMonitor.TempLevel.CRITICAL -> MaterialTheme.colorScheme.onError
    }
    val tempColor = when (thermalData.tempLevel) {
        ThermalMonitor.TempLevel.SAFE -> MaterialTheme.colorScheme.onSurface
        ThermalMonitor.TempLevel.WARM -> MaterialTheme.colorScheme.tertiary
        ThermalMonitor.TempLevel.HOT,
        ThermalMonitor.TempLevel.CRITICAL -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Temperatura",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Monitor térmico",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Auto",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isAutoMode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Switch(
                        checked = isAutoMode,
                        onCheckedChange = { onToggleAutoMode() },
                        enabled = (isMacBound || isAutoMode) && !isConnecting
                    )
                }
            }

            if (!isMacBound) {
                Text(
                    text = "Conecta el cooler para vincular este perfil antes de activar el modo automático",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (hasTemperature) {
                    Text(
                        text = "${"%.1f".format(thermalData.maxTemp)}°C",
                        style = MaterialTheme.typography.displaySmall,
                        color = tempColor
                    )
                    Surface(
                        shape = CircleShape,
                        color = chipColor
                    ) {
                        Text(
                            text = levelLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = chipContent,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    Text(
                        text = "Sin datos",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Velocidad sugerida",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (hasTemperature) "${thermalData.recommendedSpeed}%" else "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (isAutoMode) {
                Text(
                    text = "El servicio ajusta la velocidad automáticamente",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

        }
    }
}

/**
 * Ajuste del equilibrio entre ruido y refrigeración en modo automático.
 * Solo presets: cada zona tiene sus propias constantes de curva y rampa.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoisePreferenceCard(
    value: Int,
    onCommit: (Int) -> Unit,
) {
    val label = when {
        value <= ThermalLogic.NOISE_PERFORMANCE_THRESHOLD -> "Rendimiento"
        value >= ThermalLogic.NOISE_QUIET_THRESHOLD -> "Silencio"
        else -> "Equilibrado"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Preferencia de ruido",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Equilibrio entre silencio y refrigeración en modo automático",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(25 to "Rendimiento", 50 to "Equilibrado", 75 to "Silencio").forEach { (preset, name) ->
                    FilterChip(
                        selected = label == name,
                        onClick = { onCommit(preset) },
                        label = { Text(name) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
        }
    }
}

/**
 * Control de velocidad manual con presets rápidos.
 */
@Composable
private fun SpeedControlCard(
    fanSpeed: Int,
    isAutoMode: Boolean,
    isApplying: Boolean,
    onSpeedChange: (Int) -> Unit,
    onApplySpeed: () -> Unit,
    onDraggingChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Velocidad",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isAutoMode) {
                            "Desactivado en modo automático"
                        } else {
                            "Desliza para ajustar"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "$fanSpeed",
                        style = MaterialTheme.typography.displaySmall,
                        color = if (isAutoMode) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    Text(
                        text = "%",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (isAutoMode) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    if (isApplying && !isAutoMode) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(16.dp)
                                .padding(start = 8.dp, bottom = 4.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            Slider(
                modifier = Modifier.sliderTapSeek(0f..100f, enabled = !isAutoMode) { v ->
                    onSpeedChange(v.toInt())
                    onApplySpeed()
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
                value = fanSpeed.toFloat(),
                onValueChange = {
                    onDraggingChange(true)
                    onSpeedChange(it.toInt())
                },
                onValueChangeFinished = {
                    onDraggingChange(false)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
                valueRange = 0f..100f,
                enabled = !isAutoMode
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0, 50, 100).forEach { preset ->
                    FilterChip(
                        selected = fanSpeed == preset,
                        onClick = {
                            onSpeedChange(preset)
                            onApplySpeed()
                        },
                        label = { Text("$preset%") },
                        enabled = !isAutoMode,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                    )
                }
            }
        }
    }
}

/**
 * Información técnica del perfil.
 */
@Composable
private fun ProfileInfoCard(profile: CoolerProfile) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Información",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            InfoRow("Dispositivo", profile.deviceType.deviceName)
            InfoRow("Generación", "Gen ${profile.deviceType.generation}")
            InfoRow("Dirección MAC", profile.macAddress)
            InfoRow("Última conexión", profile.lastSeenFormatted)

            profile.rgbConfig?.let { rgb ->
                InfoRow("Efecto RGB", effectName(rgb.effect))
            }
        }
    }
}

private fun effectName(effect: LightEffect): String = when (effect) {
    LightEffect.COLORFUL -> "Multicolor"
    LightEffect.BREATH_FULLCOLOR -> "Respiración multicolor"
    LightEffect.BREATH_SINGLE -> "Respiración de un color"
    LightEffect.ALWAYS_BRIGHT -> "Color fijo"
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Aviso persistente de conexión. A diferencia de un snackbar, no desaparece solo:
 * el mensaje de reconexión tiene que quedar visible hasta que el usuario lo lea.
 */
@Composable
private fun ConnectionErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Entendido",
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

/**
 * El Slider de Material 3 solo responde al arrastre: un toque en la pista no
 * mueve la manija, y además consume el UP, lo que inutiliza un
 * detectTapGestures normal. Este lazo observa toda la secuencia sin consumir:
 * si el dedo no superó el touch slop, el toque fija el valor en la X pulsada.
 * El padding lateral aproxima la geometría de la pista del slider.
 */
private fun Modifier.sliderTapSeek(
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    onSeek: (Float) -> Unit,
): Modifier = pointerInput(enabled, valueRange.start, valueRange.endInclusive) {
    if (!enabled) return@pointerInput
    val sidePad = 20.dp.toPx()
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var moved = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val dx = change.position.x - down.position.x
            val dy = change.position.y - down.position.y
            if (dx * dx + dy * dy > slop * slop) moved = true
            if (!change.pressed) break
        }
        if (!moved) {
            val usable = (size.width - 2f * sidePad).coerceAtLeast(1f)
            val fraction = ((down.position.x - sidePad) / usable).coerceIn(0f, 1f)
            val span = valueRange.endInclusive - valueRange.start
            onSeek(valueRange.start + fraction * span)
        }
    }
}
