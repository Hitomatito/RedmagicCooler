package com.hitomatito.redmagicooler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hitomatito.redmagicooler.model.LightEffect
import com.hitomatito.redmagicooler.model.RGBConfig

/**
 * Pantalla de control de la iluminación RGB del cooler.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RGBControlScreen(
    isConnected: Boolean,
    modifier: Modifier = Modifier,
    isAutoMode: Boolean = false,
    rgbConfig: RGBConfig? = null,
    onNavigateBack: () -> Unit,
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

    var redValue by remember { mutableIntStateOf(initialColor?.red ?: 255) }
    var greenValue by remember { mutableIntStateOf(initialColor?.green ?: 0) }
    var blueValue by remember { mutableIntStateOf(initialColor?.blue ?: 0) }
    var selectedEffect by remember { mutableStateOf(rgbConfig?.effect?.toEffectKey()) }

    val scrollState = rememberScrollState()
    val currentColor = Color(redValue, greenValue, blueValue)
    val requiresColor = selectedEffect == "singleBreathing" || selectedEffect == "fixed"

    val applyColor: () -> Unit = {
        when (selectedEffect) {
            "singleBreathing" -> onSetBreathSingleColor(redValue, greenValue, blueValue)
            "fixed" -> onSetAlwaysBright(redValue, greenValue, blueValue)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
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
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!isEffectivelyConnected) {
                Card(
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
                connected = isEffectivelyConnected
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
                    swatch = rainbowBrush(),
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
                    swatch = multiColorBrush(),
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
}

/**
 * Vista previa grande del efecto seleccionado.
 */
@Composable
private fun PreviewCard(
    effectKey: String?,
    color: Color,
    connected: Boolean
) {
    val brush = when (effectKey) {
        "rainbow" -> rainbowBrush()
        "multiBreathing" -> multiColorBrush()
        "singleBreathing", "fixed" -> SolidColor(color)
        "off" -> SolidColor(Color(0xFF101014))
        else -> SolidColor(MaterialTheme.colorScheme.surfaceContainerHighest)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
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
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
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
                        contentDescription = null,
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
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
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
                    .background(Color(0xFF1B1B1F))
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
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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
                    contentDescription = null,
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
                            .clickable(enabled = enabled) { onPresetSelected(preset) }
                    )
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
            onValueChange = { onValueChange(it.toInt()) },
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
    val blue: Int
) {
    RED(255, 0, 0),
    ORANGE(255, 140, 0),
    YELLOW(255, 235, 59),
    GREEN(0, 200, 83),
    CYAN(0, 188, 212),
    BLUE(41, 98, 255),
    PURPLE(156, 39, 176),
    WHITE(255, 255, 255);

    val color: Color
        get() = Color(red, green, blue)
}
