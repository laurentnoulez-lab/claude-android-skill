package com.example.slideshowstudio.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.slideshowstudio.R
import com.example.slideshowstudio.audio.AudioTrackInfo
import com.example.slideshowstudio.engine.SoundtrackSettings
import com.example.slideshowstudio.engine.TrackTransition
import com.example.slideshowstudio.ui.SlideshowAction
import com.example.slideshowstudio.ui.SlideshowUiState
import kotlin.math.roundToInt

/** The playlist and everything that shapes how it is heard. */
@Composable
internal fun SoundtrackSection(
    state: SlideshowUiState,
    onAddMusic: () -> Unit,
    onAction: (SlideshowAction) -> Unit,
) {
    val settings = state.soundtrackSettings

    SettingLabel(stringResource(R.string.music_title))

    state.music.forEachIndexed { index, track ->
        TrackRow(
            position = index + 1,
            track = track,
            canMoveUp = index > 0,
            canMoveDown = index < state.music.lastIndex,
            onAction = onAction,
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        FilledTonalButton(onClick = onAddMusic) {
            Icon(Icons.Filled.MusicNote, contentDescription = null)
            Text(
                text = stringResource(R.string.music_add),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (state.isReadingMusic) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
        }
    }

    if (state.music.isNotEmpty()) {
        Text(
            text = stringResource(
                R.string.music_total,
                formatSeconds(state.musicDurationSeconds),
                formatSeconds(state.totalDurationSeconds),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        val soundtrack = state.soundtrack
        if (soundtrack != null) {
            // The user is told what is missing rather than having the music quietly looped.
            Text(
                text = if (state.musicFallsShort) {
                    stringResource(R.string.music_too_short, formatSeconds(soundtrack.missingSeconds))
                } else {
                    stringResource(R.string.music_covers)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.musicFallsShort) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }

        SettingLabel(stringResource(R.string.music_transition))
        ChoiceChips(
            options = listOf(
                TrackTransition.CUT to R.string.music_cut,
                TrackTransition.CROSSFADE to R.string.music_crossfade,
            ),
            selected = settings.transition,
            onSelect = { onAction(SlideshowAction.SetTrackTransition(it)) },
        )
        if (settings.transition == TrackTransition.CROSSFADE) {
            SettingLabel(stringResource(R.string.music_crossfade_duration, format(settings.crossfadeSeconds)))
            Slider(
                value = settings.crossfadeSeconds,
                onValueChange = { onAction(SlideshowAction.SetCrossfadeSeconds(roundToHalf(it))) },
                valueRange = SoundtrackSettings.MIN_CROSSFADE..SoundtrackSettings.MAX_CROSSFADE,
                steps = 8,
            )
        }

        SwitchRow(
            label = stringResource(R.string.music_fade_in),
            checked = settings.fadeIn,
            onCheckedChange = { onAction(SlideshowAction.SetMusicFadeIn(it)) },
        )
        SwitchRow(
            label = stringResource(R.string.music_fade_out),
            checked = settings.fadeOut,
            onCheckedChange = { onAction(SlideshowAction.SetMusicFadeOut(it)) },
        )
        if (settings.fadeIn || settings.fadeOut) {
            SettingLabel(stringResource(R.string.music_fade_duration, format(settings.fadeSeconds)))
            Slider(
                value = settings.fadeSeconds,
                onValueChange = { onAction(SlideshowAction.SetMusicFadeSeconds(roundToHalf(it))) },
                valueRange = SoundtrackSettings.MIN_FADE..SoundtrackSettings.MAX_FADE,
                steps = 8,
            )
        }

        SettingLabel(stringResource(R.string.music_volume, (settings.volume * 100).roundToInt()))
        Slider(
            value = settings.volume,
            onValueChange = { onAction(SlideshowAction.SetMusicVolume(it)) },
            valueRange = 0f..1f,
        )
    }
}

@Composable
private fun TrackRow(
    position: Int,
    track: AudioTrackInfo,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onAction: (SlideshowAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.music_position, position),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                text = formatSeconds(track.durationSeconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = { onAction(SlideshowAction.MoveMusic(track.id, -1)) },
            enabled = canMoveUp,
        ) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.music_up))
        }
        IconButton(
            onClick = { onAction(SlideshowAction.MoveMusic(track.id, 1)) },
            enabled = canMoveDown,
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.music_down))
        }
        IconButton(onClick = { onAction(SlideshowAction.RemoveMusic(track.id)) }) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.music_remove))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun roundToHalf(value: Float): Float = (value * 2f).roundToInt() / 2f

private fun formatSeconds(seconds: Float): String {
    val total = seconds.roundToInt()
    return "%d:%02d".format(total / 60, total % 60)
}
