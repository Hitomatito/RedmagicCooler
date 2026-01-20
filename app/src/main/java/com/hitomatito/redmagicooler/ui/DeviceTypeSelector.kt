package com.hitomatito.redmagicooler.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.hitomatito.redmagicooler.CoolerDeviceType

/**
 * Diálogo para seleccionar el tipo de dispositivo cooler
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceTypeSelectorDialog(
    onDismiss: () -> Unit,
    onDeviceTypeSelected: (CoolerDeviceType?) -> Unit,
    currentSelection: CoolerDeviceType?
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Título
                Text(
                    text = "Seleccionar Tipo de Cooler",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                // Opción para escanear todos los tipos
                DeviceTypeOption(
                    icon = "🔍",
                    title = "Escanear Todos",
                    description = "Buscar todos los modelos disponibles",
                    isSelected = currentSelection == null,
                    onClick = {
                        onDeviceTypeSelected(null)
                        onDismiss()
                    }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                HorizontalDivider()
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Lista de tipos de dispositivos
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp)
                ) {
                    items(CoolerDeviceType.entries) { deviceType ->
                        DeviceTypeOption(
                            icon = deviceType.getSuggestedIcon(),
                            title = deviceType.deviceName,
                            description = deviceType.description,
                            isSelected = currentSelection == deviceType,
                            onClick = {
                                onDeviceTypeSelected(deviceType)
                                onDismiss()
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Botón cancelar
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text("Cancelar")
                }
            }
        }
    }
}

/**
 * Componente individual para cada opción de tipo de dispositivo
 */
@Composable
private fun DeviceTypeOption(
    icon: String,
    title: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icono
            Text(
                text = icon,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(end = 12.dp)
            )
            
            // Contenido
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) 
                        MaterialTheme.colorScheme.onPrimaryContainer 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) 
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            
            // Indicador de selección
            if (isSelected) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

/**
 * Chip compacto para mostrar el tipo seleccionado actualmente
 */
@Composable
fun SelectedDeviceTypeChip(
    selectedType: CoolerDeviceType?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (selectedType != null) {
                    "${selectedType.getSuggestedIcon()} ${selectedType.deviceName}"
                } else {
                    "🔍 Todos los Modelos"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "▼",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}
