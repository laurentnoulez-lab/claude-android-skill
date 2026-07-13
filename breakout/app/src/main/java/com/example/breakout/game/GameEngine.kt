package com.example.breakout.game

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Moteur du casse-brique. Toute la logique (physique, collisions, score,
 * bonus/malus, niveaux, sauvegarde) vit ici, en Kotlin pur, sans aucune
 * dépendance Android : le moteur est donc testable en JUnit simple.
 *
 * Le monde a une largeur fixe [worldWidth] et une hauteur [worldHeight] ;
 * l'UI se charge de mettre ce repère à l'échelle de l'écran.
 */
class GameEngine(
    val worldWidth: Float,
    val worldHeight: Float,
    private val random: Random = Random.Default,
    private val events: GameEvents = object : GameEvents {},
) {

    companion object {
        const val STARTING_LIVES = 3
        const val MAX_LIVES = 6
        const val MAX_BALLS = 6
        const val LEVEL_CLEAR_BONUS = 250
        const val POWER_UP_SCORE = 50
        const val POWER_UP_DROP_CHANCE = 0.28f
        const val MALUS_CHANCE = 0.32f

        const val PADDLE_EFFECT_DURATION_S = 12f
        const val EXPAND_FACTOR = 1.6f
        const val SHRINK_FACTOR = 0.6f

        const val SPEED_EFFECT_DURATION_S = 8f
        const val SLOW_FACTOR = 0.65f
        const val FAST_FACTOR = 1.35f

        const val LASER_DURATION_S = 8f
        const val LASER_INTERVAL_S = 0.9f
        const val LASER_BEAMS = 8

        const val BIG_BALL_DURATION_S = 10f
        const val BIG_BALL_FACTOR = 1.8f

        /** Angle max de renvoi par la raquette, par rapport à la verticale. */
        private const val MAX_BOUNCE_ANGLE_RAD = 1.0472f // 60°
    }

    // Dimensions dérivées du monde.
    val ballRadius = worldWidth * 0.02f
    val basePaddleWidth = worldWidth * 0.24f
    val paddleHeight = worldWidth * 0.035f
    val paddleY = worldHeight * 0.92f
    val powerUpSize = worldWidth * 0.05f

    private val brickTop = worldHeight * 0.10f
    private val brickGap = worldWidth * 0.012f
    private val brickHeight = worldHeight * 0.028f
    private val baseBallSpeed = worldHeight * 0.55f
    private val laserSpeed = worldHeight * 0.6f

    // État exposé à l'UI (lu à chaque frame pour le rendu).
    var status: GameStatus = GameStatus.READY
        private set
    var score = 0
        private set
    var lives = STARTING_LIVES
        private set
    var level = 1
        private set
    var paddleX = worldWidth / 2f
        private set
    var paddleWidth = basePaddleWidth
        private set

    val balls = mutableListOf<Ball>()
    var bricks: List<Brick> = emptyList()
        private set
    val powerUps = mutableListOf<PowerUp>()
    val lasers = mutableListOf<Laser>()

    // Effets temporaires.
    private var paddleScale = 1f
    private var paddleEffectTimer = 0f
    private var slowTimer = 0f
    private var slowActive = false
    private var fastTimer = 0f
    private var fastActive = false
    private var laserTimer = 0f
    private var laserCooldown = 0f
    private var bigBallTimer = 0f
    private var bigBallActive = false

    /** Le bonus laser est-il actif ? (exposé pour le rendu) */
    val laserActive: Boolean get() = laserTimer > 0f

    /** Vitesse de balle du niveau courant (avant bonus/malus de vitesse). */
    private val levelBallSpeed: Float
        get() = baseBallSpeed * (1f + 0.06f * (level - 1))

    init {
        startGame()
    }

    /** (Re)démarre une partie complète au niveau 1. */
    fun startGame() {
        score = 0
        lives = STARTING_LIVES
        loadLevel(1)
    }

    /** Passe au niveau suivant après un LEVEL_CLEARED. */
    fun nextLevel() {
        if (status == GameStatus.LEVEL_CLEARED) {
            loadLevel(level + 1)
        }
    }

    private fun loadLevel(newLevel: Int) {
        level = newLevel
        bricks = buildBricks(Levels.pattern(newLevel))
        powerUps.clear()
        resetPaddleAndBall()
        status = GameStatus.READY
    }

    private fun buildBricks(pattern: List<String>): List<Brick> {
        val cols = Levels.COLUMNS
        val brickWidth = (worldWidth - brickGap * (cols + 1)) / cols
        val result = mutableListOf<Brick>()
        pattern.forEachIndexed { row, line ->
            line.forEachIndexed { col, char ->
                val hp = char.digitToIntOrNull() ?: return@forEachIndexed
                if (hp > 0) {
                    result += Brick(
                        x = brickGap + col * (brickWidth + brickGap),
                        y = brickTop + row * (brickHeight + brickGap),
                        width = brickWidth,
                        height = brickHeight,
                        hp = hp,
                        maxHp = hp,
                    )
                }
            }
        }
        return result
    }

    private fun resetPaddleAndBall() {
        cancelPowerUpEffects()
        paddleX = worldWidth / 2f
        balls.clear()
        balls += Ball(
            x = paddleX,
            y = paddleY - ballRadius,
            vx = 0f,
            vy = 0f,
            radius = ballRadius,
        )
    }

    private fun cancelPowerUpEffects() {
        paddleScale = 1f
        paddleEffectTimer = 0f
        slowTimer = 0f
        slowActive = false
        fastTimer = 0f
        fastActive = false
        laserTimer = 0f
        laserCooldown = 0f
        bigBallTimer = 0f
        bigBallActive = false
        lasers.clear()
        paddleWidth = basePaddleWidth
    }

    /** Déplace le centre de la raquette vers [x] (en coordonnées monde). */
    fun movePaddle(x: Float) {
        if (status != GameStatus.RUNNING && status != GameStatus.READY) return
        val half = paddleWidth / 2f
        paddleX = x.coerceIn(half, worldWidth - half)
        if (status == GameStatus.READY) {
            balls.firstOrNull()?.x = paddleX
        }
    }

    /** Lance la balle collée à la raquette. */
    fun launchBall() {
        if (status != GameStatus.READY) return
        val ball = balls.firstOrNull() ?: return
        // Léger angle aléatoire pour varier les départs.
        val angle = (random.nextFloat() - 0.5f) * 0.6f
        val speed = levelBallSpeed
        ball.vx = speed * sin(angle)
        ball.vy = -speed * cos(angle)
        status = GameStatus.RUNNING
    }

    fun togglePause() {
        status = when (status) {
            GameStatus.RUNNING -> GameStatus.PAUSED
            GameStatus.PAUSED -> GameStatus.RUNNING
            else -> return
        }
    }

    /** Avance la simulation de [dtSeconds] secondes. */
    fun update(dtSeconds: Float) {
        if (status != GameStatus.RUNNING) return

        val dt = dtSeconds.coerceIn(0f, 1f / 30f)
        updateEffectTimers(dt)
        updateLasers(dt)

        // Sous-échantillonnage : aucune balle ne doit avancer de plus que son
        // rayon par étape, pour ne pas traverser une brique.
        val maxSpeed = balls.maxOfOrNull { sqrt(it.vx * it.vx + it.vy * it.vy) } ?: 0f
        val steps = ceil(maxSpeed * dt / ballRadius).toInt().coerceIn(1, 8)
        val stepDt = dt / steps
        repeat(steps) {
            if (status == GameStatus.RUNNING) stepBalls(stepDt)
        }

        updatePowerUps(dt)
    }

    private fun updateEffectTimers(dt: Float) {
        if (paddleEffectTimer > 0f) {
            paddleEffectTimer -= dt
            if (paddleEffectTimer <= 0f) {
                setPaddleScale(1f, 0f)
            }
        }
        if (slowTimer > 0f) {
            slowTimer -= dt
            if (slowTimer <= 0f && slowActive) {
                slowActive = false
                scaleBallSpeeds(1f / SLOW_FACTOR)
            }
        }
        if (fastTimer > 0f) {
            fastTimer -= dt
            if (fastTimer <= 0f && fastActive) {
                fastActive = false
                scaleBallSpeeds(1f / FAST_FACTOR)
            }
        }
        if (bigBallTimer > 0f) {
            bigBallTimer -= dt
            if (bigBallTimer <= 0f && bigBallActive) {
                bigBallActive = false
                balls.forEach { it.radius = ballRadius }
            }
        }
        if (laserTimer > 0f) {
            laserTimer -= dt
            laserCooldown -= dt
            if (laserCooldown <= 0f) {
                laserCooldown = LASER_INTERVAL_S
                fireLasers()
            }
        }
    }

    private fun scaleBallSpeeds(factor: Float) {
        balls.forEach {
            it.vx *= factor
            it.vy *= factor
        }
    }

    /** Chaque balle tire [LASER_BEAMS] projectiles répartis tout autour d'elle. */
    private fun fireLasers() {
        for (ball in balls) {
            for (i in 0 until LASER_BEAMS) {
                val angle = i * (2f * Math.PI.toFloat() / LASER_BEAMS)
                lasers += Laser(
                    x = ball.x,
                    y = ball.y,
                    vx = laserSpeed * cos(angle),
                    vy = laserSpeed * sin(angle),
                )
            }
        }
    }

    private fun updateLasers(dt: Float) {
        if (lasers.isEmpty()) return
        val iterator = lasers.iterator()
        while (iterator.hasNext()) {
            val laser = iterator.next()
            laser.x += laser.vx * dt
            laser.y += laser.vy * dt
            if (laser.x < 0f || laser.x > worldWidth || laser.y < 0f || laser.y > worldHeight) {
                iterator.remove()
                continue
            }
            val brick = bricks.firstOrNull {
                it.alive && laser.x >= it.x && laser.x <= it.right &&
                    laser.y >= it.y && laser.y <= it.bottom
            }
            if (brick != null) {
                iterator.remove()
                hitBrick(brick)
                if (status != GameStatus.RUNNING) return
            }
        }
    }

    private fun stepBalls(dt: Float) {
        val lost = mutableListOf<Ball>()
        for (ball in balls) {
            ball.x += ball.vx * dt
            ball.y += ball.vy * dt

            collideWithWalls(ball)
            if (ball.y - ball.radius > worldHeight) {
                lost += ball
                continue
            }
            collideWithPaddle(ball)
            collideWithBricks(ball)
            if (status != GameStatus.RUNNING) return
        }

        if (lost.isNotEmpty()) {
            balls.removeAll(lost)
            if (balls.isEmpty()) {
                onAllBallsLost()
            }
        }
    }

    private fun collideWithWalls(ball: Ball) {
        if (ball.x - ball.radius < 0f && ball.vx < 0f) {
            ball.x = ball.radius
            ball.vx = -ball.vx
            events.onWallHit()
        } else if (ball.x + ball.radius > worldWidth && ball.vx > 0f) {
            ball.x = worldWidth - ball.radius
            ball.vx = -ball.vx
            events.onWallHit()
        }
        if (ball.y - ball.radius < 0f && ball.vy < 0f) {
            ball.y = ball.radius
            ball.vy = -ball.vy
            events.onWallHit()
        }
    }

    private fun collideWithPaddle(ball: Ball) {
        if (ball.vy <= 0f) return
        val halfWidth = paddleWidth / 2f
        val withinX = abs(ball.x - paddleX) <= halfWidth + ball.radius
        val withinY = ball.y + ball.radius >= paddleY &&
            ball.y - ball.radius <= paddleY + paddleHeight
        if (!withinX || !withinY) return

        // L'angle de renvoi dépend du point d'impact sur la raquette :
        // centre = tout droit, bords = angle maximal.
        val offset = ((ball.x - paddleX) / halfWidth).coerceIn(-1f, 1f)
        val angle = offset * MAX_BOUNCE_ANGLE_RAD
        val speed = sqrt(ball.vx * ball.vx + ball.vy * ball.vy)
        ball.vx = speed * sin(angle)
        ball.vy = -speed * cos(angle)
        ball.y = paddleY - ball.radius
        events.onPaddleHit()
    }

    private fun collideWithBricks(ball: Ball) {
        for (brick in bricks) {
            if (!brick.alive) continue
            val closestX = ball.x.coerceIn(brick.x, brick.right)
            val closestY = ball.y.coerceIn(brick.y, brick.bottom)
            val dx = ball.x - closestX
            val dy = ball.y - closestY
            if (dx * dx + dy * dy > ball.radius * ball.radius) continue

            // Rebond sur l'axe de moindre pénétration.
            if (abs(dx) > abs(dy)) {
                ball.vx = -ball.vx
                ball.x = if (dx > 0f) brick.right + ball.radius else brick.x - ball.radius
            } else {
                ball.vy = -ball.vy
                ball.y = if (dy > 0f) brick.bottom + ball.radius else brick.y - ball.radius
            }
            enforceMinVerticalAngle(ball)
            hitBrick(brick)
            // Une seule brique par étape : évite les doubles rebonds incohérents.
            return
        }
    }

    /**
     * Empêche les trajectoires quasi horizontales qui rendraient la partie
     * interminable.
     */
    private fun enforceMinVerticalAngle(ball: Ball) {
        val speed = sqrt(ball.vx * ball.vx + ball.vy * ball.vy)
        if (speed == 0f) return
        val minVy = speed * 0.18f
        if (abs(ball.vy) < minVy) {
            val sign = if (ball.vy < 0f) -1f else 1f
            ball.vy = sign * minVy
            val vxSign = if (ball.vx < 0f) -1f else 1f
            ball.vx = vxSign * sqrt((speed * speed - minVy * minVy).coerceAtLeast(0f))
        }
    }

    private fun hitBrick(brick: Brick) {
        brick.hp--
        if (brick.alive) {
            score += 5
            events.onBrickHit(destroyed = false)
            return
        }

        score += brick.points
        events.onBrickHit(destroyed = true)
        maybeDropPowerUp(brick)

        if (bricks.none { it.alive }) {
            score += LEVEL_CLEAR_BONUS
            if (level >= Levels.count) {
                status = GameStatus.WON
            } else {
                status = GameStatus.LEVEL_CLEARED
                events.onLevelCleared()
            }
        }
    }

    private fun maybeDropPowerUp(brick: Brick) {
        if (random.nextFloat() > POWER_UP_DROP_CHANCE) return
        val roll = random.nextFloat()
        val type = when {
            roll < MALUS_CHANCE -> listOf(
                PowerUpType.FAST_BALL,
                PowerUpType.SHRINK_PADDLE,
            ).random(random)

            roll < MALUS_CHANCE + 0.08f -> PowerUpType.EXTRA_LIFE

            else -> listOf(
                PowerUpType.EXPAND,
                PowerUpType.MULTI_BALL,
                PowerUpType.SLOW_BALL,
                PowerUpType.LASER_BALL,
                PowerUpType.BIG_BALL,
            ).random(random)
        }
        powerUps += PowerUp(
            x = brick.x + brick.width / 2f,
            y = brick.y + brick.height / 2f,
            type = type,
            size = powerUpSize,
        )
    }

    private fun updatePowerUps(dt: Float) {
        val fallSpeed = worldHeight * 0.25f
        val caught = mutableListOf<PowerUp>()
        val gone = mutableListOf<PowerUp>()
        for (powerUp in powerUps) {
            powerUp.y += fallSpeed * dt
            val half = powerUp.size / 2f
            val onPaddle = powerUp.y + half >= paddleY &&
                powerUp.y - half <= paddleY + paddleHeight &&
                abs(powerUp.x - paddleX) <= paddleWidth / 2f + half
            when {
                onPaddle -> caught += powerUp
                powerUp.y - half > worldHeight -> gone += powerUp
            }
        }
        powerUps.removeAll(gone)
        for (powerUp in caught) {
            powerUps.remove(powerUp)
            if (!powerUp.type.isMalus) score += POWER_UP_SCORE
            applyPowerUp(powerUp.type)
            events.onPowerUpCaught(powerUp.type)
        }
    }

    /** Applique l'effet d'un bonus ou d'un malus. Public pour les tests. */
    fun applyPowerUp(type: PowerUpType) {
        when (type) {
            PowerUpType.EXPAND -> setPaddleScale(EXPAND_FACTOR, PADDLE_EFFECT_DURATION_S)

            PowerUpType.SHRINK_PADDLE -> setPaddleScale(SHRINK_FACTOR, PADDLE_EFFECT_DURATION_S)

            PowerUpType.EXTRA_LIFE -> {
                lives = (lives + 1).coerceAtMost(MAX_LIVES)
            }

            PowerUpType.MULTI_BALL -> {
                val clones = mutableListOf<Ball>()
                for (ball in balls) {
                    if (balls.size + clones.size >= MAX_BALLS) break
                    clones += rotatedClone(ball, 0.44f)
                    if (balls.size + clones.size >= MAX_BALLS) break
                    clones += rotatedClone(ball, -0.44f)
                }
                balls += clones
            }

            PowerUpType.SLOW_BALL -> {
                if (!slowActive) {
                    slowActive = true
                    scaleBallSpeeds(SLOW_FACTOR)
                }
                slowTimer = SPEED_EFFECT_DURATION_S
            }

            PowerUpType.FAST_BALL -> {
                if (!fastActive) {
                    fastActive = true
                    scaleBallSpeeds(FAST_FACTOR)
                }
                fastTimer = SPEED_EFFECT_DURATION_S
            }

            PowerUpType.LASER_BALL -> {
                laserTimer = LASER_DURATION_S
                laserCooldown = 0f // Première salve immédiate.
            }

            PowerUpType.BIG_BALL -> {
                if (!bigBallActive) {
                    bigBallActive = true
                    balls.forEach { it.radius = ballRadius * BIG_BALL_FACTOR }
                }
                bigBallTimer = BIG_BALL_DURATION_S
            }
        }
    }

    /** Applique [scale] à la raquette pour [duration] secondes (1f = normal). */
    private fun setPaddleScale(scale: Float, duration: Float) {
        paddleScale = scale
        paddleEffectTimer = duration
        paddleWidth = (basePaddleWidth * scale).coerceAtMost(worldWidth * 0.5f)
        // Re-clampe la position dans les bornes avec la nouvelle largeur.
        val half = paddleWidth / 2f
        paddleX = paddleX.coerceIn(half, worldWidth - half)
    }

    private fun rotatedClone(ball: Ball, angleRad: Float): Ball {
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)
        return Ball(
            x = ball.x,
            y = ball.y,
            vx = ball.vx * cosA - ball.vy * sinA,
            vy = ball.vx * sinA + ball.vy * cosA,
            radius = ball.radius,
        )
    }

    private fun onAllBallsLost() {
        lives--
        events.onBallLost()
        if (lives <= 0) {
            lives = 0
            status = GameStatus.GAME_OVER
            events.onGameOver()
        } else {
            resetPaddleAndBall()
            status = GameStatus.READY
        }
    }

    // -----------------------------------------------------------------------
    // Sauvegarde / reprise
    // -----------------------------------------------------------------------

    /** Capture l'état complet de la partie pour la sauvegarde. */
    fun snapshot(): GameSnapshot = GameSnapshot(
        worldWidth = worldWidth,
        worldHeight = worldHeight,
        score = score,
        lives = lives,
        level = level,
        status = status,
        paddleX = paddleX,
        paddleScale = paddleScale,
        paddleEffectTimer = paddleEffectTimer,
        slowTimer = slowTimer,
        slowActive = slowActive,
        fastTimer = fastTimer,
        fastActive = fastActive,
        laserTimer = laserTimer,
        bigBallTimer = bigBallTimer,
        bigBallActive = bigBallActive,
        balls = balls.map { BallState(it.x, it.y, it.vx, it.vy, it.radius) },
        bricks = bricks.map { BrickState(it.x, it.y, it.width, it.height, it.hp, it.maxHp) },
        powerUps = powerUps.map { PowerUpState(it.x, it.y, it.type) },
        lasers = lasers.map { LaserState(it.x, it.y, it.vx, it.vy) },
    )

    /**
     * Restaure une partie sauvegardée. Les coordonnées sont remises à
     * l'échelle si les dimensions du monde ont changé (autre écran).
     * Une partie sauvegardée en cours reprend en pause.
     */
    fun restore(s: GameSnapshot) {
        val sx = worldWidth / s.worldWidth
        val sy = worldHeight / s.worldHeight

        score = s.score
        lives = s.lives
        level = s.level

        bricks = s.bricks.map {
            Brick(it.x * sx, it.y * sy, it.width * sx, it.height * sy, it.hp, it.maxHp)
        }
        balls.clear()
        balls += s.balls.map {
            Ball(it.x * sx, it.y * sy, it.vx * sx, it.vy * sy, it.radius * sx)
        }
        powerUps.clear()
        powerUps += s.powerUps.map { PowerUp(it.x * sx, it.y * sy, it.type, powerUpSize) }
        lasers.clear()
        lasers += s.lasers.map { Laser(it.x * sx, it.y * sy, it.vx * sx, it.vy * sy) }

        paddleScale = s.paddleScale
        paddleEffectTimer = s.paddleEffectTimer
        paddleWidth = (basePaddleWidth * paddleScale).coerceAtMost(worldWidth * 0.5f)
        val half = paddleWidth / 2f
        paddleX = (s.paddleX * sx).coerceIn(half, worldWidth - half)

        slowTimer = s.slowTimer
        slowActive = s.slowActive
        fastTimer = s.fastTimer
        fastActive = s.fastActive
        laserTimer = s.laserTimer
        laserCooldown = 0f
        bigBallTimer = s.bigBallTimer
        bigBallActive = s.bigBallActive

        if (balls.isEmpty()) {
            // Sauvegarde incohérente : repart d'une balle sur la raquette.
            resetPaddleAndBall()
            status = GameStatus.READY
            return
        }

        status = if (s.status == GameStatus.RUNNING) GameStatus.PAUSED else s.status
    }

    /** Une partie est en cours si elle mérite d'être sauvegardée. */
    val isInProgress: Boolean
        get() = status == GameStatus.READY || status == GameStatus.RUNNING ||
            status == GameStatus.PAUSED || status == GameStatus.LEVEL_CLEARED
}
