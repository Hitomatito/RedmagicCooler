package com.hitomatito.redmagicooler.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hitomatito.redmagicooler.CoolerDeviceType
import com.hitomatito.redmagicooler.model.CoolerProfile
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pantalla principal: lista de coolers añadidos y acceso para añadir más.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    profiles: List<CoolerProfile>,
    onProfileClick: (CoolerProfile) -> Unit,
    onAddDevice: () -> Unit,
    onDuplicateProfile: (CoolerProfile) -> Unit,
    onMoveProfile: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
    connectionError: String? = null,
    onDismissError: () -> Unit = {}
) {
    LaunchedEffect(connectionError) {
        if (connectionError != null) {
            delay(8000L.milliseconds)
            onDismissError()
        }
    }

    val connectedCount = profiles.count { it.isConnected }
    val reduceMotion = rememberReduceMotion()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "RedMagic Cooler",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = when {
                                profiles.isEmpty() -> "Sin dispositivos"
                                connectedCount > 0 -> "${profiles.size} dispositivo${if (profiles.size != 1) "s" else ""} · $connectedCount conectado${if (connectedCount != 1) "s" else ""}"
                                else -> "${profiles.size} dispositivo${if (profiles.size != 1) "s" else ""}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            if (profiles.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = onAddDevice,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Añadir") }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (profiles.isEmpty()) {
                EmptyStateContent(
                    onAddDevice = onAddDevice,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(
                        items = profiles,
                        key = { _, profile -> profile.id }
                    ) { index, profile ->
                        ProfileCard(
                            profile = profile,
                            index = index,
                            total = profiles.size,
                            onDuplicate = { onDuplicateProfile(profile) },
                            onMove = { delta -> onMoveProfile(profile.id, delta) },
                            onClick = { onProfileClick(profile) }
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = connectionError != null,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp),
                enter = if (reduceMotion) fadeIn() else slideInVertically { -it } + fadeIn(),
                exit = if (reduceMotion) fadeOut() else slideOutVertically { -it } + fadeOut()
            ) {
                ErrorBanner(
                    message = connectionError ?: "",
                    onDismiss = onDismissError
                )
            }
        }
    }
}

/**
 * Tarjeta de un cooler: estado, velocidad y acceso al detalle.
 */
@Composable
private fun ProfileCard(
    profile: CoolerProfile,
    index: Int,
    total: Int,
    onDuplicate: () -> Unit,
    onMove: (Int) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connected = profile.isConnected
    var menuExpanded by remember { mutableStateOf(false) }
    val accent = Color(profile.deviceType.accentArgb)
    val accentContainer = accent.copy(alpha = 0.12f)
    val status = when {
        connected && profile.isAutoMode -> "Conectado · Auto"
        connected -> "Conectado"
        profile.isAutoMode -> "Reconectando"
        else -> "Desconectado"
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(
                onClick = onClick,
                onClickLabel = "Abrir detalle del cooler"
            ),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(accentContainer)
                        .border(1.dp, accent.copy(alpha = 0.35f), MaterialTheme.shapes.large),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(profile.deviceType.imageRes),
                        contentDescription = "Imagen de ${profile.deviceType.deviceName}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(4.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = profile.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = profile.deviceType.deviceName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        shape = CircleShape,
                        color = if (connected) {
                            accentContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            StatusDot(connected = connected, color = accent)
                            Text(
                                text = status,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (connected) accent else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Opciones de ${profile.displayName}"
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Duplicar configuración") },
                            onClick = {
                                menuExpanded = false
                                onDuplicate()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Mover arriba") },
                            enabled = index > 0,
                            onClick = {
                                menuExpanded = false
                                onMove(-1)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Mover abajo") },
                            enabled = index < total - 1,
                            onClick = {
                                menuExpanded = false
                                onMove(1)
                            }
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (connected) {
                    Text(
                        text = if (profile.isAutoMode) "Modo automático" else "Velocidad ${profile.fanSpeed}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Última conexión: ${profile.lastSeenFormatted}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDot(connected: Boolean, color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (connected) color else MaterialTheme.colorScheme.outline)
    )
}

/**
 * Contenido cuando todavía no hay coolers añadidos.
 */
@Composable
private fun EmptyStateContent(
    onAddDevice: () -> Unit,
    modifier: Modifier = Modifier
) {
    EmptyPanel(
        title = "Aún no hay coolers",
        description = "Añade tu primer cooler RedMagic para controlar la velocidad y la iluminación RGB.",
        actionLabel = "Añadir dispositivo",
        onAction = onAddDevice,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        footer = "Asegúrate de que el cooler esté encendido y cerca del teléfono.",
        illustration = {
            val glowColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            val glowColors = remember(glowColor) { listOf(glowColor, Color.Transparent) }
            Box(
                modifier = Modifier
                    .size(184.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .drawBehind {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = glowColors,
                                center = center,
                                radius = size.minDimension * 0.7f
                            )
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(CoolerDeviceType.JACKET_5.imageRes),
                    contentDescription = "Imagen de ${CoolerDeviceType.JACKET_5.deviceName}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp)
                )
            }
        }
    )
}
