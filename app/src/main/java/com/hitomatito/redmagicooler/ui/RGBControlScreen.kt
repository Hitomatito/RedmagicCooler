package com.hitomatito.redmagicooler.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Pantalla dedicada para el control RGB del cooler
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RGBControlScreen(
    isConnected: Boolean,
    isAutoMode: Boolean = false,
    onNavigateBack: () -> Unit,
    onSetColorful: () -> Unit,
    onSetBreathFullColor: () -> Unit,
    onSetBreathSingleColor: (Int, Int, Int) -> Unit,
    onSetAlwaysBright: (Int, Int, Int) -> Unit,
    onTurnOffLight: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Considerar conectado si hay conexión directa O el servicio está manejando la conexión (modo auto)
    val isEffectivelyConnected = isConnected || isAutoMode
    
    var redValue by remember { mutableIntStateOf(255) }
    var greenValue by remember { mutableIntStateOf(0) }
    var blueValue by remember { mutableIntStateOf(0) }
    var selectedEffect by remember { mutableStateOf<String?>(null) }
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Control de Luz RGB") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver"
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
            if (!isEffectivelyConnected) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Cooler no conectado",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Efectos de luz
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Efectos de Luz",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    // Indicador de efecto seleccionado
                    if (selectedEffect != null) {
                        val effectName = when (selectedEffect) {
                            "rainbow" -> "Arcoiris"
                            "multiBreathing" -> "Respiracion Multi"
                            "singleBreathing" -> "Respiracion Simple"
                            "fixed" -> "Fijo"
                            "off" -> "Apagar"
                            else -> ""
                        }
                        Text(
                            text = "Seleccionado: $effectName",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Arcoíris
                    Button(
                        onClick = {
                            selectedEffect = "rainbow"
                            onSetColorful()
                        },
                        enabled = isEffectivelyConnected,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedEffect == "rainbow") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text("🌈 Arcoiris", fontWeight = FontWeight.Bold)
                            Text("Ciclo automatico", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    // Respiración Multi
                    Button(
                        onClick = {
                            selectedEffect = "multiBreathing"
                            onSetBreathFullColor()
                        },
                        enabled = isEffectivelyConnected,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedEffect == "multiBreathing") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text("Respiracion Multi", fontWeight = FontWeight.Bold)
                            Text("Respiracion multicolor", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    // Respiración Simple
                    Button(
                        onClick = { selectedEffect = "singleBreathing" },
                        enabled = isEffectivelyConnected,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedEffect == "singleBreathing") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text("Respiracion Simple", fontWeight = FontWeight.Bold)
                            Text("Respiracion 1 color", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    // Fijo
                    Button(
                        onClick = { selectedEffect = "fixed" },
                        enabled = isEffectivelyConnected,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedEffect == "fixed") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text("Fijo", fontWeight = FontWeight.Bold)
                            Text("Luz constante", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    // Apagar
                    Button(
                        onClick = {
                            selectedEffect = "off"
                            onTurnOffLight()
                        },
                        enabled = isEffectivelyConnected,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedEffect == "off") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text("⚫ Apagar", fontWeight = FontWeight.Bold)
                            Text("Apagado", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Selector de color (solo si el efecto seleccionado lo permite)
            if (selectedEffect != null && (selectedEffect == "singleBreathing" || selectedEffect == "fixed")) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Seleccionar Color",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )

                        // Vista previa del color
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Círculo de vista previa
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .background(
                                        color = Color(redValue, greenValue, blueValue),
                                        shape = CircleShape
                                    )
                                    .border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.outline,
                                        shape = CircleShape
                                    )
                            )

                            // Valores RGB y HEX
                            Column(
                                horizontalAlignment = Alignment.End
                            ) {
                                Text(
                                    text = "RGB: ($redValue, $greenValue, $blueValue)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "HEX: #%02X%02X%02X".format(redValue, greenValue, blueValue),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Slider Rojo
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Rojo", fontWeight = FontWeight.Medium)
                                Text(redValue.toString(), fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = redValue.toFloat(),
                                onValueChange = { redValue = it.toInt() },
                                valueRange = 0f..255f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.Red,
                                    activeTrackColor = Color.Red
                                ),
                                enabled = isEffectivelyConnected
                            )
                        }

                        // Slider Verde
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Verde", fontWeight = FontWeight.Medium)
                                Text(greenValue.toString(), fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = greenValue.toFloat(),
                                onValueChange = { greenValue = it.toInt() },
                                valueRange = 0f..255f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.Green,
                                    activeTrackColor = Color.Green
                                ),
                                enabled = isEffectivelyConnected
                            )
                        }

                        // Slider Azul
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Azul", fontWeight = FontWeight.Medium)
                                Text(blueValue.toString(), fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = blueValue.toFloat(),
                                onValueChange = { blueValue = it.toInt() },
                                valueRange = 0f..255f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.Blue,
                                    activeTrackColor = Color.Blue
                                ),
                                enabled = isEffectivelyConnected
                            )
                        }

                        HorizontalDivider()

                        // Colores rápidos
                        Text(
                            text = "Colores Rapidos",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Rojo
                            Button(
                                onClick = { redValue = 255; greenValue = 0; blueValue = 0 },
                                enabled = isEffectivelyConnected,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                            ) {
                                Text("R")
                            }
                            // Naranja
                            Button(
                                onClick = { redValue = 255; greenValue = 165; blueValue = 0 },
                                enabled = isEffectivelyConnected,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(255, 165, 0))
                            ) {
                                Text("N")
                            }
                            // Amarillo
                            Button(
                                onClick = { redValue = 255; greenValue = 255; blueValue = 0 },
                                enabled = isEffectivelyConnected,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Yellow)
                            ) {
                                Text("A")
                            }
                            // Verde
                            Button(
                                onClick = { redValue = 0; greenValue = 255; blueValue = 0 },
                                enabled = isEffectivelyConnected,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Green)
                            ) {
                                Text("V")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Cian
                            Button(
                                onClick = { redValue = 0; greenValue = 255; blueValue = 255 },
                                enabled = isEffectivelyConnected,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan)
                            ) {
                                Text("C")
                            }
                            // Azul
                            Button(
                                onClick = { redValue = 0; greenValue = 0; blueValue = 255 },
                                enabled = isEffectivelyConnected,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Blue)
                            ) {
                                Text("Az")
                            }
                            // Magenta
                            Button(
                                onClick = { redValue = 255; greenValue = 0; blueValue = 255 },
                                enabled = isEffectivelyConnected,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Magenta)
                            ) {
                                Text("M")
                            }
                            // Espacio vacío
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        HorizontalDivider()

                        // Botón de aplicación solo para efectos con color
                        Button(
                            onClick = {
                                when (selectedEffect) {
                                    "singleBreathing" -> onSetBreathSingleColor(redValue, greenValue, blueValue)
                                    "fixed" -> onSetAlwaysBright(redValue, greenValue, blueValue)
                                }
                            },
                            enabled = isEffectivelyConnected,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            val effectText = if (selectedEffect == "singleBreathing") "Respiración Simple" else "Fijo"
                            Text("✅ Aplicar $effectText")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}