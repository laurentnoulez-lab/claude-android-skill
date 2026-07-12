package com.example.breakout.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.breakout.HighScoreStore
import com.example.breakout.R
import com.example.breakout.sound.SoundEffects

@Composable
fun BreakoutApp() {
    val context = LocalContext.current
    val highScoreStore = remember { HighScoreStore(context) }
    val soundEffects = remember { SoundEffects() }
    DisposableEffect(Unit) {
        onDispose { soundEffects.release() }
    }

    var inGame by remember { mutableStateOf(false) }
    var highScore by remember { mutableIntStateOf(highScoreStore.highScore()) }

    BreakoutTheme {
        if (inGame) {
            GameScreen(
                highScoreStore = highScoreStore,
                soundEffects = soundEffects,
                onExit = {
                    highScore = highScoreStore.highScore()
                    inGame = false
                },
            )
        } else {
            MenuScreen(
                highScore = highScore,
                onPlay = { inGame = true },
            )
        }
    }
}

@Composable
private fun MenuScreen(
    highScore: Int,
    onPlay: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GameColors.backgroundBrush)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.app_name).uppercase(),
            color = GameColors.accent,
            fontSize = 40.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 4.sp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.menu_tagline),
            color = GameColors.textSecondary,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(48.dp))
        if (highScore > 0) {
            Text(
                text = stringResource(R.string.high_score, highScore),
                color = GameColors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(24.dp))
        }
        Button(onClick = onPlay) {
            Text(
                text = stringResource(R.string.play),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(48.dp))
        Text(
            text = stringResource(R.string.menu_help),
            color = GameColors.textSecondary,
            fontSize = 14.sp,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
