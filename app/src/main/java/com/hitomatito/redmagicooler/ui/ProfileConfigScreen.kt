package com.hitomatito.redmagicooler.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hitomatito.redmagicooler.ThermalMonitor
import com.hitomatito.redmagicooler.model.CoolerProfile
import com.hitomatito.redmagicooler.model.LightEffect
import kotlinx.coroutines.delay

/**
 * Pantalla de configuración completa para un perfil de cooler específico
 * Incluye control de velocidad, RGB y opciones del perfil
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileConfigScreen(
    profile: CoolerProfile,
    thermalData: ThermalMonitor.ThermalData,
    onNavigateBack: () -> Unit,
    onNavigateToRGB: () -> Unit,
    onSetFanSpeed: (Int) -> Unit,
    onToggleAutoMode: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onDeleteProfile: () -> Unit,
    onRenameProfile: (String) -> Unit,
    connectionError: String? = null,
    onDismissError: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var fanSpeed by remember(profile.fanSpeed) { mutableIntStateOf(profile.fanSpeed) }
    var isApplying by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(profile.name) }
    val scrollState = rememberScrollState()

    // Aplicación automática con debounce
    LaunchedEffect(fanSpeed, profile.isConnected, profile.isAutoMode) {
        if (profile.isConnected && !profile.isAutoMode) {
            isApplying = true
            delay(500)
            onSetFanSpeed(fanSpeed)
            delay(300)
            isApplying = false
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
                    IconButton(onClick = { showRenameDialog = true }) {
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

            // Monitor térmico
            ThermalCard(
                thermalData = thermalData,
                isAutoMode = profile.isAutoMode,
                isConnecting = false,
                onToggleAutoMode = onToggleAutoMode
            )

            // Control de velocidad (solo si está conectado)
            if (profile.isConnected) {
                SpeedControlCard(
                    fanSpeed = fanSpeed,
                    isAutoMode = profile.isAutoMode,
                    isApplying = isApplying,
                    onSpeedChange = { fanSpeed = it },
                    onSetSpeed = onSetFanSpeed
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

    Card(
        modifier = Modifier.fillMaxWidth(),
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
                                colors = listOf(glowColor, Color.Transparent),
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
    val previewBrush = when {
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

    Card(
        modifier = Modifier.fillMaxWidth(),
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
    thermalData: ThermalMonitor.ThermalData,
    isAutoMode: Boolean,
    isConnecting: Boolean,
    onToggleAutoMode: () -> Unit
) {
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
                        enabled = !isConnecting
                    )
                }
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
 * Control de velocidad manual con presets rápidos.
 */
@Composable
private fun SpeedControlCard(
    fanSpeed: Int,
    isAutoMode: Boolean,
    isApplying: Boolean,
    onSpeedChange: (Int) -> Unit,
    onSetSpeed: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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

                if (isApplying && !isAutoMode) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
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
                    }
                }
            }

            Slider(
                value = fanSpeed.toFloat(),
                onValueChange = { onSpeedChange(it.toInt()) },
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
                            onSetSpeed(preset)
                        },
                        label = { Text("$preset%") },
                        enabled = !isAutoMode,
                        modifier = Modifier.weight(1f)
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

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

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
