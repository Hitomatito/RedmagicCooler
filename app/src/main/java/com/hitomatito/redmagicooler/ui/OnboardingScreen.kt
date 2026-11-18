package com.hitomatito.redmagicooler.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val body: String
)

private val OnboardingPages = listOf(
    OnboardingPage(
        icon = Icons.Filled.Settings,
        title = "Controla tu cooler por Bluetooth",
        body = "La app se conecta al enfriador RedMagic mediante Bluetooth de baja energía. " +
            "No necesita internet ni cuenta: todo se queda en el teléfono."
    ),
    OnboardingPage(
        icon = Icons.Filled.LocationOn,
        title = "Permisos que necesitamos",
        body = "Android pide ubicación para escanear dispositivos BLE y permisos de Bluetooth " +
            "para conectarse. Sin ellos la app no puede encontrar el cooler."
    ),
    OnboardingPage(
        icon = Icons.Filled.Check,
        title = "Modo automático y notificaciones",
        body = "El modo automático mantiene una notificación persistente con la temperatura y " +
            "la velocidad. Si tu fabricante muestra un diálogo extra de permisos, acéptalo."
    )
)

/**
 * Introducción guiada que explica los permisos antes de pedirlos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pageIndex by remember { mutableIntStateOf(0) }
    val isLast = pageIndex == OnboardingPages.lastIndex
    val reduceMotion = rememberReduceMotion()

    BackHandler { onFinish() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Primeros pasos") },
                actions = {
                    TextButton(onClick = onFinish) { Text("Omitir") }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = pageIndex,
                    transitionSpec = {
                        if (reduceMotion) {
                            fadeIn() togetherWith fadeOut()
                        } else {
                            (slideInHorizontally { it } + fadeIn()) togetherWith
                                (slideOutHorizontally { -it } + fadeOut())
                        }
                    },
                    label = "onboarding-page"
                ) { index ->
                    val current = OnboardingPages[index]
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(128.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = current.icon,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Text(
                            text = current.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = current.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        if (index == OnboardingPages.lastIndex) {
                            FilledTonalButton(onClick = onOpenSettings) {
                                Icon(
                                    Icons.Filled.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Abrir ajustes del sistema")
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OnboardingPages.indices.forEach { index ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (index == pageIndex) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pageIndex) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (pageIndex > 0) {
                    TextButton(onClick = { pageIndex-- }) { Text("Anterior") }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                Button(onClick = {
                    if (isLast) {
                        onFinish()
                    } else {
                        pageIndex++
                    }
                }) {
                    Text(if (isLast) "Empezar" else "Siguiente")
                    if (!isLast) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    } else {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Filled.Check, contentDescription = null)
                    }
                }
            }
        }
    }
}
