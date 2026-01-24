package com.hitomatito.redmagicooler.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hitomatito.redmagicooler.CoolerDeviceType

/**
 * Pantalla para agregar un nuevo dispositivo cooler
 * Permite seleccionar el tipo de cooler e inicia la búsqueda
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDeviceScreen(
    onNavigateBack: () -> Unit,
    onDeviceTypeSelected: (CoolerDeviceType) -> Unit,
    isScanning: Boolean,
    isConnecting: Boolean,
    statusMessage: String,
    onCancelScan: () -> Unit,
    onProfileCreated: (String) -> Unit,
    newlyCreatedProfileId: String?,
    modifier: Modifier = Modifier
) {
    // Detectar cuando se crea un perfil nuevo y navegar automáticamente
    LaunchedEffect(newlyCreatedProfileId) {
        newlyCreatedProfileId?.let { profileId ->
            // Pequeña demora para asegurar que la UI está lista
            kotlinx.coroutines.delay(100)
            onProfileCreated(profileId)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agregar Dispositivo") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
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
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Estado de búsqueda/conexión
            if (isScanning || isConnecting) {
                ScanningStateCard(
                    isScanning = isScanning,
                    isConnecting = isConnecting,
                    statusMessage = statusMessage,
                    onCancel = onCancelScan
                )
                
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Instrucciones
            if (!isScanning && !isConnecting) {
                Text(
                    text = "Selecciona tu modelo de cooler",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Asegúrate de que tu cooler esté encendido y cerca del teléfono antes de seleccionar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // Lista de tipos de dispositivos
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(CoolerDeviceType.entries) { deviceType ->
                    DeviceTypeCard(
                        deviceType = deviceType,
                        enabled = !isScanning && !isConnecting,
                        onSelect = { onDeviceTypeSelected(deviceType) }
                    )
                }
            }
            
            // Información de ayuda
            if (!isScanning && !isConnecting) {
                Spacer(modifier = Modifier.height(16.dp))
                
                HelpCard()
            }
        }
    }
}

/**
 * Tarjeta que muestra el estado de escaneo/conexión
 */
@Composable
private fun ScanningStateCard(
    isScanning: Boolean,
    isConnecting: Boolean,
    statusMessage: String,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Indicador de progreso
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = MaterialTheme.colorScheme.primary
            )
            
            // Estado
            Text(
                text = if (isScanning) "Buscando dispositivo..." else "Conectando...",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            // Mensaje de estado
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center
            )
            
            // Botón cancelar
            Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Cancelar")
            }
        }
    }
}

/**
 * Tarjeta para un tipo de dispositivo
 */
@Composable
private fun DeviceTypeCard(
    deviceType: CoolerDeviceType,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) 
                MaterialTheme.colorScheme.surface 
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Icono
            Text(
                text = deviceType.getSuggestedIcon(),
                style = MaterialTheme.typography.headlineMedium
            )
            
            // Información
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = deviceType.deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) 
                        MaterialTheme.colorScheme.onSurface 
                    else 
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                
                Text(
                    text = deviceType.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) 
                        MaterialTheme.colorScheme.onSurfaceVariant 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                
                Text(
                    text = "Generación ${deviceType.generation}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
            }
        }
    }
}

/**
 * Tarjeta de ayuda
 */
@Composable
private fun HelpCard() {
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
                text = "Consejos",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = "• Enciende tu cooler antes de buscarlo\n" +
                       "• Mantén el cooler cerca del teléfono (< 2m)\n" +
                       "• Si no lo encuentras, intenta apagar y encender el cooler\n" +
                       "• Asegúrate de que el Bluetooth esté activado",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
