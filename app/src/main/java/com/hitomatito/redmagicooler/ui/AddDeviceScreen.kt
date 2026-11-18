package com.hitomatito.redmagicooler.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hitomatito.redmagicooler.CoolerDeviceType
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pantalla para añadir un cooler: elegir modelo e iniciar la búsqueda.
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
    modifier: Modifier = Modifier,
    connectionError: String? = null,
    onDismissError: () -> Unit = {}
) {
    // Detectar cuando se crea un perfil nuevo y navegar automáticamente
    LaunchedEffect(newlyCreatedProfileId) {
        newlyCreatedProfileId?.let { profileId ->
            delay(100.milliseconds)
            onProfileCreated(profileId)
        }
    }

    // Igual que en HomeScreen: el aviso se retira solo para no tapar la lista.
    LaunchedEffect(connectionError) {
        if (connectionError != null) {
            delay(8000.milliseconds)
            onDismissError()
        }
    }

    val busy = isScanning || isConnecting
    val reduceMotion = rememberReduceMotion()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Añadir dispositivo",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Selecciona tu modelo de cooler",
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
                .padding(horizontal = 16.dp)
        ) {
            AnimatedVisibility(
                visible = connectionError != null,
                enter = if (reduceMotion) fadeIn() else slideInVertically { -it } + fadeIn(),
                exit = if (reduceMotion) fadeOut() else slideOutVertically { -it } + fadeOut()
            ) {
                ErrorBanner(
                    message = connectionError ?: "",
                    onDismiss = onDismissError,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (busy) {
                ScanningStateCard(
                    isScanning = isScanning,
                    isConnecting = isConnecting,
                    statusMessage = statusMessage,
                    onCancel = onCancelScan,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "¿Qué modelo tienes?",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Enciende el cooler y mantenlo cerca del teléfono antes de elegir.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(CoolerDeviceType.entries) { deviceType ->
                    DeviceTypeCard(
                        deviceType = deviceType,
                        enabled = !busy,
                        onSelect = { onDeviceTypeSelected(deviceType) }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    HelpCard()
                }
            }
        }
    }
}

/**
 * Tarjeta con el progreso de búsqueda o conexión.
 */
@Composable
private fun ScanningStateCard(
    isScanning: Boolean,
    isConnecting: Boolean,
    statusMessage: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    LoadingPanel(
        title = if (isScanning) "Buscando tu cooler…" else "Conectando…",
        message = statusMessage,
        onCancel = onCancel,
        modifier = modifier
    )
}

/**
 * Fila seleccionable con un modelo de cooler.
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
            .animateContentSize()
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(
                enabled = enabled,
                onClick = onSelect,
                onClickLabel = "Seleccionar modelo"
            ),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(deviceType.imageRes),
                    contentDescription = "Imagen de ${deviceType.deviceName}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = deviceType.deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = deviceType.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Text(
                        text = "Generación ${deviceType.generation}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Consejos antes de buscar el cooler.
 */
@Composable
private fun HelpCard() {
    val tips = listOf(
        "Enciende el cooler antes de buscarlo",
        "Mantenlo a menos de 2 metros del teléfono",
        "Si no aparece, apágalo y vuelve a encenderlo",
        "Comprueba que el Bluetooth esté activado"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Antes de empezar",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            tips.forEach { tip ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = tip,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
