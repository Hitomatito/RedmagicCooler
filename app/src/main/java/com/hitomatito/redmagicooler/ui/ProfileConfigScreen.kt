package com.hitomatito.redmagicooler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hitomatito.redmagicooler.ThermalMonitor
import com.hitomatito.redmagicooler.model.CoolerProfile
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
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Estado de conexión
            ConnectionCard(
                profile = profile,
                onConnect = onConnect,
                onDisconnect = onDisconnect
            )

            // Control RGB (disponible si está conectado)
            if (profile.isConnected || profile.isAutoMode) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Control de Iluminación RGB",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        profile.rgbConfig?.let { rgb ->
                            Text(
                                text = "Efecto actual: ${rgb.effect.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        
                        Button(
                            onClick = onNavigateToRGB,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            )
                        ) {
                            Text("Configurar RGB")
                        }
                    }
                }
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
 * Tarjeta de estado de conexión
 */
@Composable
private fun ConnectionCard(
    profile: CoolerProfile,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isInAutoMode = profile.isAutoMode
    val displayStatus = when {
        isInAutoMode && profile.isConnected -> "Modo Automatico Activo"
        isInAutoMode && !profile.isConnected -> "Modo Automatico (Reconectando...)"
        profile.isConnected -> "Conectado (Manual)"
        else -> "Desconectado"
    }
    val statusColor = when {
        isInAutoMode -> MaterialTheme.colorScheme.tertiaryContainer
        profile.isConnected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = statusColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = when {
                            isInAutoMode && profile.isConnected -> "●"
                            isInAutoMode -> "●"
                            profile.isConnected -> "●"
                            else -> "○"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                    Column {
                        Text(
                            text = displayStatus.substringAfter(" "),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (isInAutoMode) {
                            Text(
                                text = "Gestionado por servicio en segundo plano",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = profile.macAddress,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            if (isInAutoMode) {
                // En modo automático, no mostrar botones de conexión manual
                Text(
                    text = "AUTO",
                    style = MaterialTheme.typography.headlineMedium
                )
            } else if (profile.isConnected) {
                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Desconectar")
                }
            } else {
                Button(onClick = onConnect) {
                    Text("Conectar")
                }
            }
        }
    }
}

/**
 * Tarjeta de monitor térmico
 */
@Composable
private fun ThermalCard(
    thermalData: ThermalMonitor.ThermalData,
    isAutoMode: Boolean,
    isConnecting: Boolean,
    onToggleAutoMode: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (thermalData.tempLevel) {
                ThermalMonitor.TempLevel.SAFE -> MaterialTheme.colorScheme.surfaceVariant
                ThermalMonitor.TempLevel.WARM -> MaterialTheme.colorScheme.tertiaryContainer
                ThermalMonitor.TempLevel.HOT -> MaterialTheme.colorScheme.errorContainer
                ThermalMonitor.TempLevel.CRITICAL -> MaterialTheme.colorScheme.error
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Monitor Termico",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = onToggleAutoMode,
                    enabled = !isConnecting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAutoMode) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(if (isAutoMode) "Auto ON" else "Auto OFF")
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Temperatura:", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "${"%.1f".format(thermalData.maxTemp)}°C",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when (thermalData.tempLevel) {
                            ThermalMonitor.TempLevel.SAFE -> "Normal"
                            ThermalMonitor.TempLevel.WARM -> "Calentamiento"
                            ThermalMonitor.TempLevel.HOT -> "Alta"
                            ThermalMonitor.TempLevel.CRITICAL -> "Critica"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Velocidad Sugerida:", style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = "${thermalData.recommendedSpeed}%",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            if (isAutoMode) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Ajuste automatico activo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * Tarjeta de control de velocidad
 */
@Composable
private fun SpeedControlCard(
    fanSpeed: Int,
    isAutoMode: Boolean,
    isApplying: Boolean,
    onSpeedChange: (Int) -> Unit,
    onSetSpeed: (Int) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isAutoMode) "Control Manual (Deshabilitado)" else "Control de Velocidad",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (isApplying && !isAutoMode) {
                    Text("⏳", style = MaterialTheme.typography.titleMedium)
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Display de velocidad
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "$fanSpeed%",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            Slider(
                value = fanSpeed.toFloat(),
                onValueChange = { onSpeedChange(it.toInt()) },
                valueRange = 0f..100f,
                steps = 99,
                enabled = !isAutoMode
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Botones rápidos
            Text(
                text = "Velocidades Preestablecidas",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { 
                        onSpeedChange(0)
                        onSetSpeed(0) 
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isAutoMode
                ) {
                    Text("0%")
                }
                OutlinedButton(
                    onClick = { 
                        onSpeedChange(50)
                        onSetSpeed(50) 
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isAutoMode
                ) {
                    Text("50%")
                }
                OutlinedButton(
                    onClick = { 
                        onSpeedChange(100)
                        onSetSpeed(100) 
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isAutoMode
                ) {
                    Text("100%")
                }
            }
        }
    }
}

/**
 * Tarjeta de información del perfil
 */
@Composable
private fun ProfileInfoCard(profile: CoolerProfile) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "ℹ️ Información del Perfil",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            InfoRow("Dispositivo", profile.deviceType.deviceName)
            InfoRow("Generación", "Gen ${profile.deviceType.generation}")
            InfoRow("MAC", profile.macAddress)
            InfoRow("Última conexión", profile.lastSeenFormatted)
            
            profile.rgbConfig?.let { rgb ->
                InfoRow("Efecto RGB", rgb.effect.name)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
