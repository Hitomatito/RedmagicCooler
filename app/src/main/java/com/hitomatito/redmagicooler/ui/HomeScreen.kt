package com.hitomatito.redmagicooler.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hitomatito.redmagicooler.model.CoolerProfile
import com.hitomatito.redmagicooler.model.ProfileStatus

/**
 * Pantalla principal (Home) que muestra las tarjetas de los perfiles de coolers
 * UI limpia con solo los dispositivos agregados y botón para agregar más
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    profiles: List<CoolerProfile>,
    onProfileClick: (CoolerProfile) -> Unit,
    onAddDevice: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(
                            text = "RedMagic Cooler",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${profiles.size} dispositivo${if (profiles.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddDevice,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Filled.Add, "Agregar dispositivo")
            }
        }
    ) { paddingValues ->
        if (profiles.isEmpty()) {
            // Estado vacío - Invitar al usuario a agregar su primer dispositivo
            EmptyStateContent(
                onAddDevice = onAddDevice,
                modifier = Modifier.padding(paddingValues)
            )
        } else {
            // Lista de perfiles
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = profiles,
                    key = { it.id }
                ) { profile ->
                    ProfileCard(
                        profile = profile,
                        onClick = { onProfileClick(profile) }
                    )
                }
                
                // Espacio extra al final para el FAB
                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

/**
 * Tarjeta de perfil de cooler
 */
@Composable
private fun ProfileCard(
    profile: CoolerProfile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        targetValue = when (profile.statusColor) {
            ProfileStatus.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
            ProfileStatus.CONNECTING -> MaterialTheme.colorScheme.secondaryContainer
            ProfileStatus.DISCONNECTED -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "containerColor"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (profile.isConnected) 4.dp else 1.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icono del dispositivo
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = profile.icon,
                    style = MaterialTheme.typography.headlineLarge
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // Información del perfil
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Nombre del perfil
                Text(
                    text = profile.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                // Tipo de dispositivo
                Text(
                    text = profile.deviceType.deviceName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Estado
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Indicador de conexión
                    Text(
                        text = when (profile.statusColor) {
                            ProfileStatus.CONNECTED -> "●"
                            ProfileStatus.CONNECTING -> "●"
                            ProfileStatus.DISCONNECTED -> "○"
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                    
                    Text(
                        text = profile.statusDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (profile.statusColor) {
                            ProfileStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                            ProfileStatus.CONNECTING -> MaterialTheme.colorScheme.secondary
                            ProfileStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            
            // Indicador RGB si está configurado
            if (profile.rgbConfig != null && profile.isConnected) {
                Text(
                    text = "RGB",
                    style = MaterialTheme.typography.titleLarge
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Icono de configuración
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Configurar",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        
        // Barra de información adicional si está conectado
        if (profile.isConnected) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (profile.isAutoMode) {
                    Text(
                        text = "Modo Automatico",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = "Velocidad: ${profile.fanSpeed}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                if (profile.signalStrength != 0) {
                    val signalText = when {
                        profile.signalStrength >= -50 -> "📶 Excelente"
                        profile.signalStrength >= -70 -> "📶 Buena"
                        else -> "📡 Regular"
                    }
                    Text(
                        text = signalText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // Última conexión si está desconectado
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp)
            ) {
                Text(
                    text = "Última conexión: ${profile.lastSeenFormatted}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * Contenido cuando no hay perfiles
 */
@Composable
private fun EmptyStateContent(
    onAddDevice: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Icono grande
                Text(
                    text = "❄️",
                    style = MaterialTheme.typography.displayLarge
                )
                
                // Título
                Text(
                    text = "Sin dispositivos",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                // Descripción
                Text(
                    text = "Agrega tu primer cooler RedMagic para comenzar a controlar su velocidad y luces RGB.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Botón de agregar
                Button(
                    onClick = onAddDevice,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Agregar Dispositivo")
                }
                
                // Texto de ayuda
                Text(
                    text = "Asegurate de que tu cooler este encendido y cerca del telefono",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}
