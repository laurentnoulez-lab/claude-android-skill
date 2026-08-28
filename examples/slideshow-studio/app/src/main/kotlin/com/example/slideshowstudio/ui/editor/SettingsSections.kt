package com.example.slideshowstudio.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.slideshowstudio.R
import com.example.slideshowstudio.engine.OutputFormat
import com.example.slideshowstudio.engine.Palette

/** Title of a settings block, in the same style everywhere. */
@Composable
internal fun SettingLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

/** One line of explanation under a choice, for the options whose effect is not obvious. */
@Composable
internal fun SettingHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** A row of chips laid out two by two, so long French labels stay readable. */
@Composable
internal fun <T> ChoiceChips(
    options: List<Pair<T, Int>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (value, label) ->
                    FilterChip(
                        selected = value == selected,
                        onClick = { onSelect(value) },
                        label = { Text(stringResource(label)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/** The two output formats, shown with a small preview of their shape. */
@Composable
internal fun FormatChooser(
    selected: OutputFormat,
    onSelect: (OutputFormat) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(
            Triple(OutputFormat.LANDSCAPE_1080P, R.string.format_landscape, R.string.format_landscape_detail),
            Triple(OutputFormat.PORTRAIT_1080P, R.string.format_portrait, R.string.format_portrait_detail),
        ).forEach { (format, title, detail) ->
            val active = format == selected
            val border = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .border(if (active) 2.dp else 1.dp, border, RoundedCornerShape(12.dp))
                    .clickable { onSelect(format) }
                    .padding(10.dp),
            ) {
                // A rectangle with the shape of the format says more than any wording.
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .aspectRatio(format.aspect)
                        .clip(RoundedCornerShape(3.dp))
                        .background(border),
                )
                Column {
                    Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = stringResource(detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Colour picker for the solid background: ready-made swatches, three sliders, and a hex field, all
 * three staying in step with each other.
 */
@Composable
internal fun SolidColorPicker(
    color: Int,
    onColorChange: (Int) -> Unit,
) {
    var hsv by remember { mutableStateOf(Palette.toHsv(color)) }
    var hexText by remember { mutableStateOf(Palette.toHex(color)) }

    // The colour can also change from outside (a swatch, or the hex field): resynchronise the
    // controls, but only when they do not already describe that colour, so sliders stay stable.
    LaunchedEffect(color) {
        if (Palette.hsvToColor(hsv.hue, hsv.saturation, hsv.value) != color) {
            hsv = Palette.toHsv(color)
        }
        if (Palette.parseHex(hexText) != color) {
            hexText = Palette.toHex(color)
        }
    }

    fun emit(hue: Float = hsv.hue, saturation: Float = hsv.saturation, value: Float = hsv.value) {
        hsv = Palette.Hsv(hue, saturation, value)
        onColorChange(Palette.hsvToColor(hue, saturation, value))
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(color))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
            )
            Palette.PRESETS.forEach { preset ->
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(preset))
                        .border(
                            width = if (preset == color) 2.dp else 1.dp,
                            color = if (preset == color) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = RoundedCornerShape(6.dp),
                        )
                        .clickable { onColorChange(preset) },
                )
            }
        }

        LabeledSlider(stringResource(R.string.color_hue), hsv.hue / 360f) { emit(hue = it * 360f) }
        LabeledSlider(stringResource(R.string.color_saturation), hsv.saturation) { emit(saturation = it) }
        LabeledSlider(stringResource(R.string.color_brightness), hsv.value) { emit(value = it) }

        OutlinedTextField(
            value = hexText,
            onValueChange = { text ->
                hexText = text
                Palette.parseHex(text)?.let(onColorChange)
            },
            label = { Text(stringResource(R.string.color_hex)) },
            singleLine = true,
            isError = Palette.parseHex(hexText) == null,
            modifier = Modifier
                .width(180.dp)
                .padding(top = 8.dp),
        )
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
            modifier = Modifier.width(84.dp),
        )
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
        )
    }
}
