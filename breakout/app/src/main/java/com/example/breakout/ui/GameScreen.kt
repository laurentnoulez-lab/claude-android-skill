package com.example.breakout.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.breakout.GameSaveStore
import com.example.breakout.HighScoreStore
import com.example.breakout.R
import com.example.breakout.game.GameEngine
import com.example.breakout.game.GameSnapshot
import com.example.breakout.game.GameStatus
import com.example.breakout.game.PowerUpType
import com.example.breakout.sound.SoundEffects
import kotlinx.coroutines.isActive

/** Largeur logique du monde ; la hauteur suit le ratio de l'écran. */
private const val WORLD_WIDTH = 360f

/** Copie immuable de l'état affiché par le HUD et les overlays. */
private data class HudSnapshot(
    val score: Int,
    val lives: Int,
    val level: Int,
    val status: GameStatus,
)

private fun snapshotOf(engine: GameEngine) = HudSnapshot(
    score = engine.score,
    lives = engine.lives,
    level = engine.level,
    status = engine.status,
)

@Composable
fun GameScreen(
    highScoreStore: HighScoreStore,
    saveStore: GameSaveStore,
    soundEffects: SoundEffects,
    initialSnapshot: GameSnapshot?,
    onExit: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(GameColors.backgroundBrush),
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val worldHeight = WORLD_WIDTH * heightPx / widthPx
        val scale = widthPx / WORLD_WIDTH

        val engine = remember(worldHeight) {
            GameEngine(WORLD_WIDTH, worldHeight, events = soundEffects).also { engine ->
                initialSnapshot?.let { engine.restore(it) }
            }
        }

        // Sauvegarde la partie en cours (ou efface la sauvegarde si terminée).
        fun persistGame() {
            if (engine.isInProgress) {
                saveStore.save(engine.snapshot())
            } else {
                saveStore.clear()
            }
        }

        var hud by remember(engine) { mutableStateOf(snapshotOf(engine)) }
        var frame by remember { mutableLongStateOf(0L) }
        var newRecord by remember(engine) { mutableStateOf(false) }

        // Boucle de jeu : une mise à jour de la physique par frame d'affichage.
        LaunchedEffect(engine) {
            var lastNanos = 0L
            while (isActive) {
                withFrameNanos { now ->
                    if (lastNanos != 0L) {
                        engine.update((now - lastNanos) / 1_000_000_000f)
                    }
                    lastNanos = now
                    frame++
                    val snapshot = snapshotOf(engine)
                    if (snapshot != hud) hud = snapshot
                }
            }
        }

        // Met le jeu en pause et sauvegarde quand l'application passe en
        // arrière-plan (c'est ce qui permet de reprendre après fermeture).
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, engine) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) {
                    if (engine.status == GameStatus.RUNNING) engine.togglePause()
                    persistGame()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        // Fin de partie : meilleur score + suppression de la sauvegarde.
        LaunchedEffect(hud.status) {
            if (hud.status == GameStatus.GAME_OVER || hud.status == GameStatus.WON) {
                newRecord = highScoreStore.submit(engine.score)
                saveStore.clear()
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(engine, scale) {
                    detectDragGestures(
                        onDragStart = { offset -> engine.movePaddle(offset.x / scale) },
                        onDrag = { change, _ ->
                            change.consume()
                            engine.movePaddle(change.position.x / scale)
                        },
                    )
                }
                .pointerInput(engine, scale) {
                    detectTapGestures {
                        engine.launchBall()
                    }
                },
        ) {
            frame // Lecture de l'état : invalide le dessin à chaque frame.
            scale(scale = scale, pivot = Offset.Zero) {
                drawWorld(engine)
            }
        }

        GameHud(
            hud = hud,
            onPauseToggle = { engine.togglePause() },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding(),
        )

        when (hud.status) {
            GameStatus.READY -> Text(
                text = stringResource(R.string.tap_to_launch),
                color = GameColors.textSecondary,
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 200.dp),
            )

            GameStatus.PAUSED -> GameOverlay(title = stringResource(R.string.pause)) {
                Button(onClick = { engine.togglePause() }) {
                    Text(stringResource(R.string.resume))
                }
                OutlinedButton(onClick = { engine.startGame() }) {
                    Text(stringResource(R.string.restart))
                }
                TextButton(
                    onClick = {
                        persistGame()
                        onExit()
                    },
                ) {
                    Text(stringResource(R.string.back_to_menu), color = GameColors.textSecondary)
                }
            }

            GameStatus.LEVEL_CLEARED -> GameOverlay(
                title = stringResource(R.string.level_cleared, hud.level),
                subtitle = stringResource(R.string.level_bonus, GameEngine.LEVEL_CLEAR_BONUS),
            ) {
                Button(
                    onClick = {
                        engine.nextLevel()
                        persistGame()
                    },
                ) {
                    Text(stringResource(R.string.next_level))
                }
            }

            GameStatus.GAME_OVER -> GameOverlay(
                title = stringResource(R.string.game_over),
                subtitle = finalScoreText(hud.score, newRecord),
            ) {
                Button(onClick = { engine.startGame() }) {
                    Text(stringResource(R.string.replay))
                }
                TextButton(onClick = onExit) {
                    Text(stringResource(R.string.back_to_menu), color = GameColors.textSecondary)
                }
            }

            GameStatus.WON -> GameOverlay(
                title = stringResource(R.string.won_title),
                subtitle = finalScoreText(hud.score, newRecord),
            ) {
                Button(onClick = { engine.startGame() }) {
                    Text(stringResource(R.string.replay))
                }
                TextButton(onClick = onExit) {
                    Text(stringResource(R.string.back_to_menu), color = GameColors.textSecondary)
                }
            }

            GameStatus.RUNNING -> Unit
        }
    }
}

@Composable
private fun finalScoreText(score: Int, newRecord: Boolean): String {
    val base = stringResource(R.string.final_score, score)
    return if (newRecord) base + "\n" + stringResource(R.string.new_record) else base
}

@Composable
private fun GameHud(
    hud: HudSnapshot,
    onPauseToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.score_value, hud.score),
            color = GameColors.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.level_value, hud.level),
            color = GameColors.textSecondary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "♥".repeat(hud.lives),
                color = GameColors.heart,
                fontSize = 16.sp,
            )
            Spacer(Modifier.width(12.dp))
            if (hud.status == GameStatus.RUNNING || hud.status == GameStatus.PAUSED) {
                Text(
                    text = if (hud.status == GameStatus.PAUSED) "▶" else "❚❚",
                    color = GameColors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .pointerInputPauseButton(onPauseToggle)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private fun Modifier.pointerInputPauseButton(onClick: () -> Unit): Modifier =
    this.pointerInput(onClick) {
        detectTapGestures { onClick() }
    }

@Composable
private fun GameOverlay(
    title: String,
    subtitle: String? = null,
    buttons: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GameColors.scrim),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xF0141B36),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 40.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    color = GameColors.textPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = GameColors.textSecondary,
                        fontSize = 16.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                buttons()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Rendu du monde (coordonnées monde, mises à l'échelle par l'appelant).
// ---------------------------------------------------------------------------

private fun DrawScope.drawWorld(engine: GameEngine) {
    drawBricks(engine)
    drawLasers(engine)
    drawPowerUps(engine)
    drawPaddle(engine)
    drawBalls(engine)
}

private fun DrawScope.drawLasers(engine: GameEngine) {
    for (laser in engine.lasers) {
        // Petit trait orienté dans le sens du déplacement.
        drawLine(
            color = GameColors.laser,
            start = Offset(laser.x - laser.vx * 0.02f, laser.y - laser.vy * 0.02f),
            end = Offset(laser.x, laser.y),
            strokeWidth = 1.6f,
        )
    }
}

private fun DrawScope.drawBricks(engine: GameEngine) {
    val cornerRadius = CornerRadius(2.5f, 2.5f)
    for (brick in engine.bricks) {
        if (!brick.alive) continue
        val color = GameColors.brickColor(brick.hp)
        drawRoundRect(
            color = color,
            topLeft = Offset(brick.x, brick.y),
            size = Size(brick.width, brick.height),
            cornerRadius = cornerRadius,
        )
        // Liseré clair en haut pour un léger relief.
        drawRoundRect(
            color = Color.White.copy(alpha = 0.22f),
            topLeft = Offset(brick.x, brick.y),
            size = Size(brick.width, brick.height * 0.35f),
            cornerRadius = cornerRadius,
        )
    }
}

private fun DrawScope.drawPaddle(engine: GameEngine) {
    val left = engine.paddleX - engine.paddleWidth / 2f
    drawRoundRect(
        brush = Brush.horizontalGradient(
            colors = listOf(GameColors.paddleStart, GameColors.paddleEnd),
            startX = left,
            endX = left + engine.paddleWidth,
        ),
        topLeft = Offset(left, engine.paddleY),
        size = Size(engine.paddleWidth, engine.paddleHeight),
        cornerRadius = CornerRadius(engine.paddleHeight / 2f, engine.paddleHeight / 2f),
    )
}

private fun DrawScope.drawBalls(engine: GameEngine) {
    for (ball in engine.balls) {
        val center = Offset(ball.x, ball.y)
        drawCircle(
            color = if (engine.laserActive) {
                GameColors.laser.copy(alpha = 0.30f)
            } else {
                GameColors.ball.copy(alpha = 0.18f)
            },
            radius = ball.radius * 2f,
            center = center,
        )
        drawCircle(
            color = GameColors.ball,
            radius = ball.radius,
            center = center,
        )
    }
}

private fun DrawScope.drawPowerUps(engine: GameEngine) {
    for (powerUp in engine.powerUps) {
        val center = Offset(powerUp.x, powerUp.y)
        val half = powerUp.size / 2f
        drawCircle(
            color = GameColors.powerUpColor(powerUp.type),
            radius = half,
            center = center,
        )
        // Anneau sombre : signale un malus à éviter.
        if (powerUp.type.isMalus) {
            drawCircle(
                color = GameColors.malusRing,
                radius = half,
                center = center,
                style = Stroke(width = half * 0.22f),
            )
        }
        drawPowerUpGlyph(powerUp.type, center, half)
    }
}

private fun DrawScope.drawPowerUpGlyph(type: PowerUpType, center: Offset, half: Float) {
    val glyph = Color.White
    val stroke = half * 0.22f
    when (type) {
        PowerUpType.EXPAND -> {
            val arm = half * 0.55f
            val head = half * 0.28f
            drawLine(glyph, center - Offset(arm, 0f), center + Offset(arm, 0f), stroke)
            drawLine(glyph, center - Offset(arm, 0f), center + Offset(-arm + head, -head), stroke)
            drawLine(glyph, center - Offset(arm, 0f), center + Offset(-arm + head, head), stroke)
            drawLine(glyph, center + Offset(arm, 0f), center + Offset(arm - head, -head), stroke)
            drawLine(glyph, center + Offset(arm, 0f), center + Offset(arm - head, head), stroke)
        }

        PowerUpType.EXTRA_LIFE -> {
            val arm = half * 0.5f
            drawLine(glyph, center - Offset(arm, 0f), center + Offset(arm, 0f), stroke)
            drawLine(glyph, center - Offset(0f, arm), center + Offset(0f, arm), stroke)
        }

        PowerUpType.MULTI_BALL -> {
            val offset = half * 0.4f
            val dotRadius = half * 0.2f
            drawCircle(glyph, dotRadius, center + Offset(0f, -offset))
            drawCircle(glyph, dotRadius, center + Offset(-offset, offset * 0.7f))
            drawCircle(glyph, dotRadius, center + Offset(offset, offset * 0.7f))
        }

        PowerUpType.SLOW_BALL -> {
            drawCircle(
                color = glyph,
                radius = half * 0.5f,
                center = center,
                style = Stroke(width = stroke),
            )
        }

        PowerUpType.LASER_BALL -> {
            // Étoile : quatre rayons autour d'un point central.
            val ray = half * 0.55f
            drawCircle(glyph, half * 0.16f, center)
            drawLine(glyph, center + Offset(-ray, 0f), center + Offset(ray, 0f), stroke)
            drawLine(glyph, center + Offset(0f, -ray), center + Offset(0f, ray), stroke)
            val diag = ray * 0.7f
            drawLine(glyph, center + Offset(-diag, -diag), center + Offset(diag, diag), stroke)
            drawLine(glyph, center + Offset(-diag, diag), center + Offset(diag, -diag), stroke)
        }

        PowerUpType.BIG_BALL -> {
            drawCircle(glyph, half * 0.2f, center)
            drawCircle(
                color = glyph,
                radius = half * 0.55f,
                center = center,
                style = Stroke(width = stroke * 0.8f),
            )
        }

        PowerUpType.FAST_BALL -> {
            // Double chevron « avance rapide ».
            val h = half * 0.42f
            val w = half * 0.34f
            for (xShift in listOf(-w * 1.1f, w * 0.1f)) {
                val base = center + Offset(xShift, 0f)
                drawLine(glyph, base + Offset(0f, -h), base + Offset(w, 0f), stroke)
                drawLine(glyph, base + Offset(w, 0f), base + Offset(0f, h), stroke)
            }
        }

        PowerUpType.SHRINK_PADDLE -> {
            // Deux flèches horizontales pointant vers le centre.
            val arm = half * 0.6f
            val head = half * 0.26f
            val inner = half * 0.12f
            drawLine(glyph, center - Offset(arm, 0f), center - Offset(inner, 0f), stroke)
            drawLine(glyph, center - Offset(inner, 0f), center - Offset(inner + head, head), stroke)
            drawLine(glyph, center - Offset(inner, 0f), center - Offset(inner + head, -head), stroke)
            drawLine(glyph, center + Offset(arm, 0f), center + Offset(inner, 0f), stroke)
            drawLine(glyph, center + Offset(inner, 0f), center + Offset(inner + head, head), stroke)
            drawLine(glyph, center + Offset(inner, 0f), center + Offset(inner + head, -head), stroke)
        }
    }
}
