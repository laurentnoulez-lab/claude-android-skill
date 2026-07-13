package com.example.breakout.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class GameEngineTest {

    private fun newEngine(): GameEngine =
        GameEngine(worldWidth = 360f, worldHeight = 640f, random = Random(42))

    /** Fait avancer la simulation de [seconds] par pas de 16 ms. */
    private fun GameEngine.run(seconds: Float) {
        var remaining = seconds
        while (remaining > 0f) {
            update(0.016f)
            remaining -= 0.016f
        }
    }

    @Test
    fun `initial state is ready with a stuck ball`() {
        val engine = newEngine()
        assertEquals(GameStatus.READY, engine.status)
        assertEquals(GameEngine.STARTING_LIVES, engine.lives)
        assertEquals(0, engine.score)
        assertEquals(1, engine.level)
        assertEquals(1, engine.balls.size)
        assertTrue(engine.bricks.isNotEmpty())
    }

    @Test
    fun `launch starts the game with a moving ball`() {
        val engine = newEngine()
        engine.launchBall()
        assertEquals(GameStatus.RUNNING, engine.status)
        val ball = engine.balls.first()
        assertTrue("La balle doit partir vers le haut", ball.vy < 0f)
    }

    @Test
    fun `paddle stays within world bounds`() {
        val engine = newEngine()
        engine.movePaddle(-100f)
        assertEquals(engine.paddleWidth / 2f, engine.paddleX, 0.01f)
        engine.movePaddle(10_000f)
        assertEquals(engine.worldWidth - engine.paddleWidth / 2f, engine.paddleX, 0.01f)
    }

    @Test
    fun `ball bounces off side walls`() {
        val engine = newEngine()
        engine.launchBall()
        val ball = engine.balls.first()
        ball.x = ball.radius + 1f
        ball.y = engine.worldHeight / 2f
        ball.vx = -200f
        ball.vy = -10f
        engine.update(0.016f)
        assertTrue("vx doit être inversé après rebond sur le mur gauche", ball.vx > 0f)
    }

    @Test
    fun `ball bounces off paddle upward`() {
        val engine = newEngine()
        engine.launchBall()
        val ball = engine.balls.first()
        ball.x = engine.paddleX
        ball.y = engine.paddleY - ball.radius - 1f
        ball.vx = 0f
        ball.vy = 300f
        engine.update(0.016f)
        assertTrue("vy doit repartir vers le haut après la raquette", ball.vy < 0f)
    }

    @Test
    fun `paddle bounce angle depends on impact point`() {
        val engine = newEngine()
        engine.launchBall()
        val ball = engine.balls.first()
        // Impact sur le bord droit de la raquette : la balle part vers la droite.
        ball.x = engine.paddleX + engine.paddleWidth / 2f - 1f
        ball.y = engine.paddleY - ball.radius - 1f
        ball.vx = 0f
        ball.vy = 300f
        engine.update(0.016f)
        assertTrue("La balle doit partir vers la droite", ball.vx > 0f)
        assertTrue("La balle doit partir vers le haut", ball.vy < 0f)
    }

    @Test
    fun `losing the last ball costs a life and resets the ball`() {
        val engine = newEngine()
        engine.launchBall()
        val ball = engine.balls.first()
        ball.x = 10f
        ball.y = engine.worldHeight - 1f
        ball.vx = 0f
        ball.vy = 500f
        engine.movePaddle(engine.worldWidth - 10f) // Raquette loin de la balle.
        engine.run(1f)
        assertEquals(GameEngine.STARTING_LIVES - 1, engine.lives)
        assertEquals(GameStatus.READY, engine.status)
        assertEquals(1, engine.balls.size)
    }

    @Test
    fun `game over when all lives are lost`() {
        val engine = newEngine()
        repeat(GameEngine.STARTING_LIVES) {
            engine.launchBall()
            val ball = engine.balls.first()
            ball.x = 10f
            ball.y = engine.worldHeight - 1f
            ball.vx = 0f
            ball.vy = 500f
            engine.movePaddle(engine.worldWidth - 10f)
            engine.run(1f)
        }
        assertEquals(0, engine.lives)
        assertEquals(GameStatus.GAME_OVER, engine.status)
    }

    @Test
    fun `hitting a brick reduces its hp and increases score`() {
        val engine = newEngine()
        engine.launchBall()
        val brick = engine.bricks.first()
        val ball = engine.balls.first()
        ball.x = brick.x + brick.width / 2f
        ball.y = brick.bottom + ball.radius + 1f
        ball.vx = 0f
        ball.vy = -300f
        val scoreBefore = engine.score
        engine.update(0.016f)
        assertTrue("La brique doit avoir perdu un point de vie", brick.hp < brick.maxHp)
        assertTrue("Le score doit augmenter", engine.score > scoreBefore)
        assertTrue("La balle doit rebondir vers le bas", ball.vy > 0f)
    }

    @Test
    fun `destroying every brick clears the level with a bonus`() {
        val engine = newEngine()
        engine.launchBall()
        // Ne laisse qu'une brique d'un seul point de vie.
        engine.bricks.drop(1).forEach { it.hp = 0 }
        val last = engine.bricks.first()
        last.hp = 1
        val ball = engine.balls.first()
        ball.x = last.x + last.width / 2f
        ball.y = last.bottom + ball.radius + 1f
        ball.vx = 0f
        ball.vy = -300f
        val scoreBefore = engine.score
        engine.update(0.016f)
        assertEquals(GameStatus.LEVEL_CLEARED, engine.status)
        assertTrue(
            "Le bonus de niveau doit être compté",
            engine.score >= scoreBefore + last.points + GameEngine.LEVEL_CLEAR_BONUS,
        )
    }

    @Test
    fun `next level keeps score and increases level`() {
        val engine = newEngine()
        engine.launchBall()
        engine.bricks.drop(1).forEach { it.hp = 0 }
        val last = engine.bricks.first()
        last.hp = 1
        val ball = engine.balls.first()
        ball.x = last.x + last.width / 2f
        ball.y = last.bottom + ball.radius + 1f
        ball.vx = 0f
        ball.vy = -300f
        engine.update(0.016f)
        val score = engine.score
        engine.nextLevel()
        assertEquals(2, engine.level)
        assertEquals(score, engine.score)
        assertEquals(GameStatus.READY, engine.status)
        assertTrue(engine.bricks.all { it.alive })
    }

    @Test
    fun `expand power up widens the paddle`() {
        val engine = newEngine()
        engine.applyPowerUp(PowerUpType.EXPAND)
        assertTrue(engine.paddleWidth > engine.basePaddleWidth)
    }

    @Test
    fun `extra life power up adds a life up to the cap`() {
        val engine = newEngine()
        engine.applyPowerUp(PowerUpType.EXTRA_LIFE)
        assertEquals(GameEngine.STARTING_LIVES + 1, engine.lives)
        repeat(10) { engine.applyPowerUp(PowerUpType.EXTRA_LIFE) }
        assertEquals(GameEngine.MAX_LIVES, engine.lives)
    }

    @Test
    fun `multi ball power up adds balls up to the cap`() {
        val engine = newEngine()
        engine.launchBall()
        engine.applyPowerUp(PowerUpType.MULTI_BALL)
        assertEquals(3, engine.balls.size)
        val speed = engine.balls.map { sqrt(it.vx * it.vx + it.vy * it.vy) }
        // Les clones conservent la vitesse de la balle d'origine.
        assertEquals(speed[0], speed[1], 0.5f)
        repeat(5) { engine.applyPowerUp(PowerUpType.MULTI_BALL) }
        assertEquals(GameEngine.MAX_BALLS, engine.balls.size)
    }

    @Test
    fun `fast ball malus speeds up then restores ball speed`() {
        val engine = newEngine()
        engine.launchBall()
        val ball = engine.balls.first()
        val speedBefore = sqrt(ball.vx * ball.vx + ball.vy * ball.vy)
        engine.applyPowerUp(PowerUpType.FAST_BALL)
        val speedFast = sqrt(ball.vx * ball.vx + ball.vy * ball.vy)
        assertEquals(speedBefore * GameEngine.FAST_FACTOR, speedFast, 0.5f)
        // Laisse l'effet expirer (la balle est maintenue en jeu manuellement).
        var elapsed = 0f
        while (elapsed < GameEngine.SPEED_EFFECT_DURATION_S + 1f) {
            ball.x = engine.worldWidth / 2f
            ball.y = engine.worldHeight * 0.7f
            engine.update(0.016f)
            elapsed += 0.016f
        }
        val speedAfter = engine.balls.first().let { sqrt(it.vx * it.vx + it.vy * it.vy) }
        assertEquals(speedBefore, speedAfter, speedBefore * 0.05f)
    }

    @Test
    fun `shrink paddle malus narrows the paddle and expand overrides it`() {
        val engine = newEngine()
        engine.applyPowerUp(PowerUpType.SHRINK_PADDLE)
        assertEquals(
            engine.basePaddleWidth * GameEngine.SHRINK_FACTOR,
            engine.paddleWidth,
            0.01f,
        )
        // Un bonus d'élargissement remplace le malus.
        engine.applyPowerUp(PowerUpType.EXPAND)
        assertTrue(engine.paddleWidth > engine.basePaddleWidth)
    }

    @Test
    fun `big ball power up grows then restores ball radius`() {
        val engine = newEngine()
        engine.launchBall()
        val ball = engine.balls.first()
        engine.applyPowerUp(PowerUpType.BIG_BALL)
        assertEquals(engine.ballRadius * GameEngine.BIG_BALL_FACTOR, ball.radius, 0.01f)
        var elapsed = 0f
        while (elapsed < GameEngine.BIG_BALL_DURATION_S + 1f) {
            ball.x = engine.worldWidth / 2f
            ball.y = engine.worldHeight * 0.7f
            engine.update(0.016f)
            elapsed += 0.016f
        }
        assertEquals(engine.ballRadius, engine.balls.first().radius, 0.01f)
    }

    @Test
    fun `laser power up fires beams that destroy bricks`() {
        val engine = newEngine()
        engine.launchBall()
        val ball = engine.balls.first()
        // Balle au centre, sous les briques, presque immobile verticalement.
        ball.x = engine.worldWidth / 2f
        ball.y = engine.worldHeight / 2f
        engine.applyPowerUp(PowerUpType.LASER_BALL)
        val aliveBefore = engine.bricks.count { it.alive }
        var elapsed = 0f
        while (elapsed < 2f && engine.status == GameStatus.RUNNING) {
            ball.x = engine.worldWidth / 2f
            ball.y = engine.worldHeight / 2f
            engine.update(0.016f)
            elapsed += 0.016f
        }
        val aliveAfter = engine.bricks.count { it.alive }
        assertTrue(
            "Les lasers doivent détruire des briques ($aliveBefore -> $aliveAfter)",
            aliveAfter < aliveBefore,
        )
    }

    @Test
    fun `catching a malus gives no score`() {
        val engine = newEngine()
        engine.launchBall()
        val before = engine.score
        engine.applyPowerUp(PowerUpType.FAST_BALL)
        assertEquals(before, engine.score)
    }

    @Test
    fun `snapshot and restore round trip preserves the game`() {
        val engine = newEngine()
        engine.launchBall()
        engine.applyPowerUp(PowerUpType.EXPAND)
        engine.applyPowerUp(PowerUpType.MULTI_BALL)
        engine.bricks.first().hp = 0
        engine.run(0.2f)

        val snapshot = engine.snapshot()
        val restored = GameEngine(engine.worldWidth, engine.worldHeight, random = Random(1))
        restored.restore(snapshot)

        assertEquals(engine.score, restored.score)
        assertEquals(engine.lives, restored.lives)
        assertEquals(engine.level, restored.level)
        assertEquals(engine.balls.size, restored.balls.size)
        assertEquals(engine.paddleWidth, restored.paddleWidth, 0.01f)
        assertEquals(
            engine.bricks.count { it.alive },
            restored.bricks.count { it.alive },
        )
        // Une partie sauvegardée en cours reprend en pause.
        assertEquals(GameStatus.PAUSED, restored.status)
    }

    @Test
    fun `snapshot json round trip is lossless`() {
        val engine = newEngine()
        engine.launchBall()
        engine.applyPowerUp(PowerUpType.LASER_BALL)
        engine.update(0.016f)
        val snapshot = engine.snapshot()
        val decoded = GameSnapshot.fromJson(snapshot.toJson())
        assertEquals(snapshot, decoded)
    }

    @Test
    fun `corrupt save json is rejected`() {
        assertEquals(null, GameSnapshot.fromJson("pas du json"))
        assertEquals(null, GameSnapshot.fromJson("{\"version\":99}"))
    }

    @Test
    fun `restore rescales to a different world size`() {
        val engine = newEngine() // 360 x 640
        engine.launchBall()
        val snapshot = engine.snapshot()

        val other = GameEngine(720f, 1280f, random = Random(1))
        other.restore(snapshot)

        assertEquals(engine.balls.first().x * 2f, other.balls.first().x, 0.01f)
        assertEquals(engine.balls.first().y * 2f, other.balls.first().y, 0.01f)
        assertEquals(engine.bricks.first().x * 2f, other.bricks.first().x, 0.01f)
        // Toutes les briques restent dans le monde.
        assertTrue(other.bricks.all { it.right <= other.worldWidth + 0.01f })
    }

    @Test
    fun `ready game is in progress but game over is not`() {
        val engine = newEngine()
        assertTrue(engine.isInProgress)
        repeat(GameEngine.STARTING_LIVES) {
            engine.launchBall()
            val ball = engine.balls.first()
            ball.x = 10f
            ball.y = engine.worldHeight - 1f
            ball.vx = 0f
            ball.vy = 500f
            engine.movePaddle(engine.worldWidth - 10f)
            engine.run(1f)
        }
        assertEquals(GameStatus.GAME_OVER, engine.status)
        assertTrue(!engine.isInProgress)
    }

    @Test
    fun `slow power up reduces then restores ball speed`() {
        val engine = newEngine()
        engine.launchBall()
        val ball = engine.balls.first()
        val speedBefore = sqrt(ball.vx * ball.vx + ball.vy * ball.vy)
        engine.applyPowerUp(PowerUpType.SLOW_BALL)
        val speedSlow = sqrt(ball.vx * ball.vx + ball.vy * ball.vy)
        assertEquals(speedBefore * GameEngine.SLOW_FACTOR, speedSlow, 0.5f)
        // Laisse l'effet expirer (la balle est maintenue en jeu manuellement).
        var elapsed = 0f
        while (elapsed < GameEngine.SPEED_EFFECT_DURATION_S + 1f) {
            ball.x = engine.worldWidth / 2f
            ball.y = engine.worldHeight * 0.7f
            engine.update(0.016f)
            elapsed += 0.016f
        }
        val speedAfter = engine.balls.first().let { sqrt(it.vx * it.vx + it.vy * it.vy) }
        assertEquals(speedBefore, speedAfter, speedBefore * 0.05f)
    }

    @Test
    fun `pause freezes the simulation`() {
        val engine = newEngine()
        engine.launchBall()
        engine.togglePause()
        assertEquals(GameStatus.PAUSED, engine.status)
        val ball = engine.balls.first()
        val x = ball.x
        val y = ball.y
        engine.run(0.5f)
        assertEquals(x, ball.x, 0.001f)
        assertEquals(y, ball.y, 0.001f)
        engine.togglePause()
        assertEquals(GameStatus.RUNNING, engine.status)
    }

    @Test
    fun `restart resets score lives and level`() {
        val engine = newEngine()
        engine.launchBall()
        engine.applyPowerUp(PowerUpType.EXTRA_LIFE)
        engine.startGame()
        assertEquals(0, engine.score)
        assertEquals(GameEngine.STARTING_LIVES, engine.lives)
        assertEquals(1, engine.level)
        assertEquals(GameStatus.READY, engine.status)
    }

    @Test
    fun `every level pattern is well formed`() {
        for (level in 1..Levels.count) {
            val pattern = Levels.pattern(level)
            assertTrue("Le niveau $level doit avoir des lignes", pattern.isNotEmpty())
            pattern.forEach { line ->
                assertEquals(
                    "Chaque ligne du niveau $level doit faire ${Levels.COLUMNS} colonnes",
                    Levels.COLUMNS,
                    line.length,
                )
                assertTrue(
                    "Caractères valides uniquement au niveau $level",
                    line.all { it == '.' || it in '1'..'3' },
                )
            }
            assertTrue(
                "Le niveau $level doit contenir au moins une brique",
                pattern.any { line -> line.any { it in '1'..'3' } },
            )
        }
    }

    @Test
    fun `ball never tunnels through bricks at high speed`() {
        val engine = newEngine()
        engine.launchBall()
        val ball = engine.balls.first()
        val brick = engine.bricks.first()
        ball.x = brick.x + brick.width / 2f
        ball.y = brick.bottom + 60f
        ball.vx = 0f
        ball.vy = -2000f // Vitesse extrême : le sous-échantillonnage doit gérer.
        engine.update(0.033f)
        assertTrue(
            "La brique doit avoir été touchée malgré la vitesse",
            engine.bricks.any { it.hp < it.maxHp } || abs(ball.vy) != 2000f,
        )
    }
}
