package com.hitomatito.redmagicooler.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hitomatito.redmagicooler.CoolerDeviceType

/**
 * Pantalla para selección inicial de dispositivo cooler
 * Al seleccionar un dispositivo, automáticamente se guarda la selección,
 * inicia la búsqueda/conexión y regresa a la pantalla principal
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceManagementScreen(
    onNavigateBack: () -> Unit,
    onStartScan: () -> Unit,
    onConnectDevice: (CoolerDeviceType) -> Unit,
    onDisconnectDevice: () -> Unit,
    isConnected: Boolean,
    isConnecting: Boolean,
    selectedDeviceType: CoolerDeviceType?,
    statusMessage: String,
    onDeviceSelectedAndReturn: (CoolerDeviceType) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Seleccionar Dispositivo Cooler") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Estado de conexión actual
            ConnectionStatusCard(
                isConnected = isConnected,
                isConnecting = isConnecting,
                selectedDeviceType = selectedDeviceType,
                statusMessage = statusMessage,
                onConnect = onStartScan,
                onDisconnect = onDisconnectDevice
            )

            // Lista de dispositivos disponibles
            Text(
                text = "Selecciona tu modelo de cooler",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(CoolerDeviceType.entries) { deviceType ->
                    DeviceCard(
                        deviceType = deviceType,
                        isSelected = selectedDeviceType == deviceType,
                        isConnected = isConnected && selectedDeviceType == deviceType,
                        onSelect = { onDeviceSelectedAndReturn(deviceType) }
                    )
                }
            }

            // Espacio adicional
            Spacer(modifier = Modifier.weight(1f))

            // Información de ayuda
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
                        text = "💡 Selecciona tu modelo",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Al seleccionar un modelo, la aplicación automáticamente comenzará a buscar y conectar tu dispositivo cooler. Regresarás a la pantalla principal para ver el progreso.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Tarjeta que muestra el estado de conexión actual
 */
@Composable
private fun ConnectionStatusCard(
    isConnected: Boolean,
    isConnecting: Boolean,
    selectedDeviceType: CoolerDeviceType?,
    statusMessage: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isConnected -> MaterialTheme.colorScheme.primaryContainer
                isConnecting -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = when {
                        isConnected -> MaterialTheme.colorScheme.primary
                        isConnecting -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            isConnected -> "Conectado"
                            isConnecting -> "Conectando..."
                            else -> "Desconectado"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    selectedDeviceType?.let {
                        Text(
                            text = it.deviceName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isConnected) {
                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Desconectar")
                    }
                } else if (!isConnecting) {
                    Button(onClick = onConnect) {
                        Text("Buscar")
                    }
                }
            }

            if (statusMessage.isNotEmpty()) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Tarjeta individual para cada tipo de dispositivo
 */
@Composable
private fun DeviceCard(
    deviceType: CoolerDeviceType,
    isSelected: Boolean,
    isConnected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isConnected -> MaterialTheme.colorScheme.primaryContainer
                isSelected -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected || isConnected) {
            CardDefaults.outlinedCardBorder()
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Icono del dispositivo
            Text(
                text = deviceType.getSuggestedIcon(),
                style = MaterialTheme.typography.headlineMedium
            )

            // Información del dispositivo
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = deviceType.deviceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )

                    // Indicadores de estado
                    if (isConnected) {
                        Text(
                            text = "CONECTADO",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    } else if (isSelected) {
                        Text(
                            text = "SELECCIONADO",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = deviceType.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Generación del dispositivo
                Text(
                    text = "Generación: ${deviceType.generation}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // Icono de acción
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = if (isConnected) "Conectado" else "Conectar",
                tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}