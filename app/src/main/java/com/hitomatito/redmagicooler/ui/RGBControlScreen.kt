package com.hitomatito.redmagicooler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hitomatito.redmagicooler.model.LightEffect
import com.hitomatito.redmagicooler.model.RGBConfig

/**
 * Contenido de la hoja modal de control de la iluminación RGB del cooler.
 */
@Composable
fun RGBControlSheet(
    isConnected: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isAutoMode: Boolean = false,
    rgbConfig: RGBConfig? = null,
    onSetColorful: () -> Unit,
    onSetBreathFullColor: () -> Unit,
    onSetBreathSingleColor: (Int, Int, Int) -> Unit,
    onSetAlwaysBright: (Int, Int, Int) -> Unit,
    onTurnOffLight: () -> Unit
) {
    // Hay conexión directa o el servicio la mantiene (modo automático)
    val isEffectivelyConnected = isConnected || isAutoMode

    // Solo los efectos de un color guardan un color real; en el resto el perfil guarda 0,0,0
    val initialColor = rgbConfig?.takeIf {
        (it.effect == LightEffect.BREATH_SINGLE || it.effect == LightEffect.ALWAYS_BRIGHT) &&
            (it.red + it.green + it.blue > 0)
    }

    var redValue by rememberSaveable { mutableIntStateOf(initialColor?.red ?: 255) }
    var greenValue by rememberSaveable { mutableIntStateOf(initialColor?.green ?: 0) }
    var blueValue by rememberSaveable { mutableIntStateOf(initialColor?.blue ?: 0) }
    // "off" es un estado válido y debe sobrevivir a la rotación aunque el perfil
    // no guarde configuración RGB.
    var selectedEffect by rememberSaveable {
        mutableStateOf(rgbConfig?.effect?.toEffectKey() ?: "off")
    }

    val scrollState = rememberScrollState()
    val currentColor = Color(redValue, greenValue, blueValue)
    val requiresColor = selectedEffect == "singleBreathing" || selectedEffect == "fixed"
    val rainbowSwatch = remember { rainbowBrush() }
    val multiSwatch = remember { multiColorBrush() }

    val applyColor = remember(
        selectedEffect,
        redValue,
        greenValue,
        blueValue,
        onSetBreathSingleColor,
        onSetAlwaysBright
    ) {
        {
            when (selectedEffect) {
                "singleBreathing" -> onSetBreathSingleColor(redValue, greenValue, blueValue)
                "fixed" -> onSetAlwaysBright(redValue, greenValue, blueValue)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Iluminación RGB",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (isEffectivelyConnected) "Elige un efecto y su color" else "Conecta el cooler para controlar la luz",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Cerrar iluminación RGB")
            }
        }

            if (!isEffectivelyConnected) {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "El cooler no está conectado. Los cambios no se aplicarán.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            PreviewCard(
                effectKey = selectedEffect,
                color = currentColor,
                rainbowSwatch = rainbowSwatch,
                multiSwatch = multiSwatch
            )

            Text(
                text = "Efecto",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EffectCard(
                    title = "Arcoíris",
                    subtitle = "Ciclo automático",
                    swatch = rainbowSwatch,
                    selected = selectedEffect == "rainbow",
                    enabled = isEffectivelyConnected,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectedEffect = "rainbow"
                        onSetColorful()
                    }
                )
                EffectCard(
                    title = "Multicolor",
                    subtitle = "Respiración",
                    swatch = multiSwatch,
                    selected = selectedEffect == "multiBreathing",
                    enabled = isEffectivelyConnected,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectedEffect = "multiBreathing"
                        onSetBreathFullColor()
                    }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EffectCard(
                    title = "Un color",
                    subtitle = "Respiración",
                    swatch = SolidColor(currentColor),
                    selected = selectedEffect == "singleBreathing",
                    enabled = isEffectivelyConnected,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectedEffect = "singleBreathing"
                        onSetBreathSingleColor(redValue, greenValue, blueValue)
                    }
                )
                EffectCard(
                    title = "Fijo",
                    subtitle = "Luz constante",
                    swatch = SolidColor(currentColor),
                    selected = selectedEffect == "fixed",
                    enabled = isEffectivelyConnected,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectedEffect = "fixed"
                        onSetAlwaysBright(redValue, greenValue, blueValue)
                    }
                )
            }

            OffEffectCard(
                selected = selectedEffect == "off",
                enabled = isEffectivelyConnected,
                onClick = {
                    selectedEffect = "off"
                    onTurnOffLight()
                }
            )

            if (requiresColor) {
                ColorSection(
                    red = redValue,
                    green = greenValue,
                    blue = blueValue,
                    enabled = isEffectivelyConnected,
                    onRedChange = { redValue = it },
                    onGreenChange = { greenValue = it },
                    onBlueChange = { blueValue = it },
                    onPresetSelected = { preset ->
                        redValue = preset.red
                        greenValue = preset.green
                        blueValue = preset.blue
                        if (isEffectivelyConnected) {
                            when (selectedEffect) {
                                "singleBreathing" -> onSetBreathSingleColor(
                                    preset.red, preset.green, preset.blue
                                )

                                "fixed" -> onSetAlwaysBright(
                                    preset.red, preset.green, preset.blue
                                )
                            }
                        }
                    },
                    onApply = applyColor
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
}

/**
 * Vista previa grande del efecto seleccionado.
 */
@Composable
private fun PreviewCard(
    effectKey: String?,
    color: Color,
    rainbowSwatch: Brush,
    multiSwatch: Brush
) {
    val offColor = MaterialTheme.colorScheme.inverseSurface
    val fallbackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val brush = remember(effectKey, color, rainbowSwatch, multiSwatch, offColor, fallbackColor) {
        when (effectKey) {
            "rainbow" -> rainbowSwatch
            "multiBreathing" -> multiSwatch
            "singleBreathing", "fixed" -> SolidColor(color)
            "off" -> SolidColor(offColor)
            else -> SolidColor(fallbackColor)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Vista previa",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = effectLabel(effectKey),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (effectKey == "singleBreathing" || effectKey == "fixed") {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Text(
                            text = colorToHex(color),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(brush)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = MaterialTheme.shapes.large
                    )
            )
        }
    }
}

/**
 * Tarjeta seleccionable de efecto.
 */
@Composable
private fun EffectCard(
    title: String,
    subtitle: String,
    swatch: Brush,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = MaterialTheme.shapes.large

    Card(
        modifier = modifier
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = onClick
            )
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                }
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(swatch)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
                if (selected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Seleccionado",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                }
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Tarjeta de ancho completo para apagar la iluminación.
 */
@Composable
private fun OffEffectCard(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = MaterialTheme.shapes.large

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = onClick
            )
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                }
            ),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.inverseSurface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Apagar",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    text = "Sin iluminación",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Seleccionado",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Selector de color con sliders y atajos.
 */
@Composable
private fun ColorSection(
    red: Int,
    green: Int,
    blue: Int,
    enabled: Boolean,
    onRedChange: (Int) -> Unit,
    onGreenChange: (Int) -> Unit,
    onBlueChange: (Int) -> Unit,
    onPresetSelected: (ColorPreset) -> Unit,
    onApply: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Color",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = colorToHex(Color(red, green, blue)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(red, green, blue))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
                Text(
                    text = "RGB ($red, $green, $blue)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            ColorSlider(
                label = "Rojo",
                value = red,
                color = Color(0xFFE53935),
                enabled = enabled,
                onValueChange = onRedChange
            )
            ColorSlider(
                label = "Verde",
                value = green,
                color = Color(0xFF43A047),
                enabled = enabled,
                onValueChange = onGreenChange
            )
            ColorSlider(
                label = "Azul",
                value = blue,
                color = Color(0xFF1E88E5),
                enabled = enabled,
                onValueChange = onBlueChange
            )

            Text(
                text = "Colores rápidos",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ColorPreset.entries.forEach { preset ->
                    val selected = preset.red == red && preset.green == green && preset.blue == blue
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable(enabled = enabled) { onPresetSelected(preset) }
                            .semantics {
                                contentDescription = preset.label
                                this.selected = selected
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(preset.color)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = CircleShape
                                )
                        )
                    }
                }
            }

            Button(
                onClick = onApply,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Aplicar color")
            }
        }
    }
}

@Composable
private fun ColorSlider(
    label: String,
    value: Int,
    color: Color,
    enabled: Boolean,
    onValueChange: (Int) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = {
                onValueChange(it.toInt())
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            },
            valueRange = 0f..255f,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color,
                inactiveTrackColor = color.copy(alpha = 0.24f)
            )
        )
    }
}

private fun rainbowBrush(): Brush = Brush.sweepGradient(
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

private fun multiColorBrush(): Brush = Brush.linearGradient(
    listOf(
        Color(0xFFE53935),
        Color(0xFF8E24AA),
        Color(0xFF1E88E5),
        Color(0xFF43A047)
    )
)

private fun colorToHex(color: Color): String {
    val red = (color.red * 255).toInt().coerceIn(0, 255)
    val green = (color.green * 255).toInt().coerceIn(0, 255)
    val blue = (color.blue * 255).toInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(red, green, blue)
}

private fun effectLabel(key: String?): String = when (key) {
    "rainbow" -> "Arcoíris"
    "multiBreathing" -> "Respiración multicolor"
    "singleBreathing" -> "Respiración de un color"
    "fixed" -> "Color fijo"
    "off" -> "Apagado"
    else -> "Sin efecto seleccionado"
}

private fun LightEffect.toEffectKey(): String = when (this) {
    LightEffect.COLORFUL -> "rainbow"
    LightEffect.BREATH_FULLCOLOR -> "multiBreathing"
    LightEffect.BREATH_SINGLE -> "singleBreathing"
    LightEffect.ALWAYS_BRIGHT -> "fixed"
}

/**
 * Atajos de color del selector.
 */
private enum class ColorPreset(
    val red: Int,
    val green: Int,
    val blue: Int,
    val label: String
) {
    RED(255, 0, 0, "Rojo"),
    ORANGE(255, 140, 0, "Naranja"),
    YELLOW(255, 235, 59, "Amarillo"),
    GREEN(0, 200, 83, "Verde"),
    CYAN(0, 188, 212, "Cian"),
    BLUE(41, 98, 255, "Azul"),
    PURPLE(156, 39, 176, "Morado"),
    WHITE(255, 255, 255, "Blanco");

    val color: Color = Color(red, green, blue)
}
