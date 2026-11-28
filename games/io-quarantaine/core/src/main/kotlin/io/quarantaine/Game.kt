package io.quarantaine

import io.quarantaine.audio.Mixer
import io.quarantaine.audio.Sfx
import io.quarantaine.game.Ammo
import io.quarantaine.game.Difficulty
import io.quarantaine.game.Enemies
import io.quarantaine.game.Input
import io.quarantaine.game.NoEvents
import io.quarantaine.game.Player
import io.quarantaine.game.Progress
import io.quarantaine.game.Settings
import io.quarantaine.game.Weapons
import io.quarantaine.game.World
import io.quarantaine.game.WorldEvents
import io.quarantaine.gfx.Camera
import io.quarantaine.gfx.Renderer
import io.quarantaine.math.PI
import io.quarantaine.math.Rng
import io.quarantaine.math.wrapAngle
import io.quarantaine.platform.Gl
import io.quarantaine.platform.Host
import io.quarantaine.platform.TouchEvent
import io.quarantaine.ui.Icons
import io.quarantaine.ui.Ui
import io.quarantaine.world.Levels
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Point d'entrée du jeu, indépendant de la plateforme.
 * Toutes les méthodes (sauf [onTouch], [requestBack], [requestPause]) s'exécutent sur le fil de rendu.
 */
class Game(private val host: Host, sampleRate: Int) : WorldEvents {

    enum class State { TITLE, DIFFICULTY, OPTIONS, HELP, INTRO, PLAYING, PAUSED, LEVEL_DONE, DEAD, VICTORY }

    val mixer = Mixer(sampleRate)
    val settings = Settings()
    val progress = Progress()

    private var renderer: Renderer? = null
    private var ui: Ui? = null
    private var width = 1
    private var height = 1

    private val touches = ConcurrentLinkedQueue<TouchEvent>()
    @Volatile private var backRequested = false
    @Volatile private var pauseRequested = false

    var state = State.TITLE
        private set
    private var optionsReturn = State.TITLE
    private var stateTime = 0f
    private var time = 0f

    var world: World? = null
        private set
    private var attract: World? = null
    private var levelIndex = 0
    private var difficulty = 1
    private val levelStartInventory = Player()
    private val input = Input()
    private val cam = Camera()
    private val rng = Rng(99)
    private var shake = 0f
    private var showMap = false
    private var msg = ""
    private var msgTime = 0f
    private var doneNewRecord = false
    private var doneBest: Float? = null
    private var doneTime = 0f
    private var totalKills = 0
    private var totalKillsMax = 0
    private var totalTime = 0f

    // ------------------------------------------------------------------ pointeurs
    private class Ptr(val id: Int, var role: Int, var x: Float, var y: Float, val sx: Float, val sy: Float)
    private val ptrs = HashMap<Int, Ptr>()
    private var lookDx = 0f
    private var lookDy = 0f
    private var clickX = -1f; private var clickY = -1f; private var clickSX = -1f; private var clickSY = -1f
    private var stickId = -1
    private var stickBaseX = 0f; private var stickBaseY = 0f

    companion object {
        private const val ROLE_UI = 0
        private const val ROLE_STICK = 1
        private const val ROLE_LOOK = 2
        private const val ROLE_FIRE = 3
        private const val ROLE_BUTTON = 4
        private val KEY_COLORS = arrayOf(floatArrayOf(1f, 0.2f, 0.15f), floatArrayOf(0.25f, 0.5f, 1f), floatArrayOf(1f, 0.85f, 0.15f))
        private val ORANGE = floatArrayOf(1f, 0.55f, 0.12f)
        private val GREY = floatArrayOf(0.55f, 0.6f, 0.68f)
        private val DOOR_COLOR = floatArrayOf(0.8f, 0.6f, 0.25f)
    }

    init {
        settings.load(host)
        progress.load(host)
        applyAudioSettings()
        mixer.setMusic(Levels.MUSIC_TITLE)
    }

    private fun applyAudioSettings() {
        mixer.musicVolume = settings.musicVolume
        mixer.sfxVolume = settings.sfxVolume
    }

    // ------------------------------------------------------------------ cycle de vie

    fun onSurfaceCreated(gl: Gl) {
        val r = Renderer(gl)
        r.init()
        renderer = r
        ui = Ui(gl, r.hudProg, r.stream).also { it.init() }
    }

    fun onSurfaceChanged(w: Int, h: Int) {
        width = max(1, w); height = max(1, h)
        renderer?.width = width; renderer?.height = height
    }

    fun onTouch(e: TouchEvent) { touches.add(e) }
    fun requestBack() { backRequested = true }
    fun requestPause() { pauseRequested = true }

    fun onDrawFrame(dtRaw: Float) {
        val dt = min(max(dtRaw, 0f), 0.05f)
        time += dt; stateTime += dt
        if (pauseRequested) { pauseRequested = false; if (state == State.PLAYING) setState(State.PAUSED) }
        if (backRequested) { backRequested = false; handleBack() }
        processTouches()
        update(dt)
        render()
    }

    private fun setState(s: State) {
        state = s
        stateTime = 0f
        ptrs.values.forEach { it.role = ROLE_UI }
        stickId = -1
        clickX = -1f
    }

    private fun handleBack() {
        when (state) {
            State.PLAYING -> setState(State.PAUSED)
            State.PAUSED -> setState(State.PLAYING)
            State.OPTIONS -> { settings.save(host); setState(optionsReturn) }
            State.DIFFICULTY, State.HELP -> setState(State.TITLE)
            State.TITLE -> host.quit()
            State.INTRO, State.LEVEL_DONE, State.DEAD, State.VICTORY -> toTitle()
        }
    }

    // ------------------------------------------------------------------ parcours

    fun startNewGame(diff: Int) {
        difficulty = diff
        totalKills = 0; totalKillsMax = 0; totalTime = 0f
        enterLevel(0, null, true)
    }

    private fun continueGame() {
        difficulty = progress.difficulty
        val carry = Player()
        val ok = progress.inventory.isNotEmpty() && carry.deserialize(progress.inventory)
        enterLevel(progress.level.coerceIn(0, Levels.all.size - 1), if (ok) carry else null, true)
    }

    /** Charge un secteur. [carry] : inventaire hérité. */
    fun enterLevel(index: Int, carry: Player?, briefing: Boolean) {
        levelIndex = index
        val def = Levels.all[index]
        val w = World(def, difficulty, this, carry, settings.aimAssist, seed = 1000L + index * 31 + (time * 1000).toLong())
        world = w
        levelStartInventory.copyInventoryFrom(w.player)
        showMap = false
        msg = ""; msgTime = 0f; shake = 0f
        mixer.setMusic(def.music)
        progress.level = index
        progress.difficulty = difficulty
        progress.inventory = w.player.serialize()
        progress.save(host)
        setState(if (briefing) State.INTRO else State.PLAYING)
    }

    private fun restartLevel() {
        val carry = Player().also { it.copyInventoryFrom(levelStartInventory) }
        enterLevel(levelIndex, carry, false)
    }

    private fun toTitle() {
        world = null
        mixer.setMusic(Levels.MUSIC_TITLE)
        setState(State.TITLE)
    }

    private fun onLevelComplete(w: World) {
        doneTime = w.time
        totalKills += w.kills; totalKillsMax += w.killsTotal; totalTime += w.time
        doneBest = progress.bestTime(host, levelIndex, difficulty)
        doneNewRecord = progress.submitTime(host, levelIndex, difficulty, w.time)
        if (w.def.boss) {
            progress.completed = true
            progress.level = -1
            progress.inventory = ""
            progress.save(host)
            mixer.setMusic(Levels.MUSIC_VICTORY)
            setState(State.VICTORY)
        } else {
            progress.level = levelIndex + 1
            progress.inventory = w.player.serialize()
            progress.save(host)
            mixer.setMusic(Levels.MUSIC_VICTORY)
            setState(State.LEVEL_DONE)
        }
    }

    // ------------------------------------------------------------------ événements du monde

    override fun sound(id: Int, x: Float, y: Float, vol: Float) {
        val p = world?.player ?: return
        val dx = x - p.x; val dy = y - p.y
        val d = sqrt(dx * dx + dy * dy)
        if (d > 32f) return
        val v = vol / (1f + d * 0.16f)
        val pan = if (d < 0.5f) 0f else (dx * sin(p.yaw) - dy * cos(p.yaw)) / d
        mixer.play(id, v, pan * 0.8f, 0.94f + rng.float() * 0.12f)
    }

    override fun soundUi(id: Int, vol: Float) = mixer.play(id, vol, 0f, 1f)
    override fun vibrate(ms: Int, strength: Float) { if (settings.vibration) host.vibrate(ms, strength) }
    override fun message(text: String) { msg = text; msgTime = 2.6f }
    override fun shake(amount: Float) { shake = max(shake, amount) }

    // ------------------------------------------------------------------ entrées

    private fun processTouches() {
        clickX = -1f
        while (true) {
            val e = touches.poll() ?: break
            when (e.action) {
                TouchEvent.DOWN -> {
                    val p = Ptr(e.pointerId, ROLE_UI, e.x, e.y, e.x, e.y)
                    ptrs[e.pointerId] = p
                    if (state == State.PLAYING) p.role = gameRole(p)
                }
                TouchEvent.MOVE -> {
                    val p = ptrs[e.pointerId] ?: continue
                    if (p.role == ROLE_LOOK || p.role == ROLE_FIRE) { lookDx += e.x - p.x; lookDy += e.y - p.y }
                    p.x = e.x; p.y = e.y
                }
                TouchEvent.UP, TouchEvent.CANCEL -> {
                    val p = ptrs.remove(e.pointerId) ?: continue
                    if (p.id == stickId) stickId = -1
                    if (p.role == ROLE_UI && e.action == TouchEvent.UP) {
                        val d = sqrt((e.x - p.sx) * (e.x - p.sx) + (e.y - p.sy) * (e.y - p.sy))
                        if (d < height * 0.1f) { clickX = e.x; clickY = e.y; clickSX = p.sx; clickSY = p.sy }
                    }
                }
            }
        }
    }

    // Géométrie des contrôles en jeu
    private val hf get() = height.toFloat()
    private val wf get() = width.toFloat()
    private fun fireX() = wf - hf * 0.2f
    private fun fireY() = hf - hf * 0.25f
    private fun fireR() = hf * 0.13f
    private fun nextX() = wf - hf * 0.13f
    private fun nextY() = hf * 0.5f
    private fun nextR() = hf * 0.075f
    private fun slotW() = hf * 0.115f
    private fun slotH() = hf * 0.085f
    private fun slotsX0() = wf / 2f - slotW() * 3f
    private fun slotsY0() = hf - slotH() - hf * 0.02f
    private fun pauseX() = hf * 0.08f
    private fun mapX() = hf * 0.21f
    private fun topBtnY() = hf * 0.08f
    private fun topBtnR() = hf * 0.06f
    private fun stickR() = hf * 0.15f

    private fun inCircle(x: Float, y: Float, cx: Float, cy: Float, r: Float) = (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r

    private fun gameRole(p: Ptr): Int {
        val x = p.x; val y = p.y
        val w = world ?: return ROLE_UI
        if (inCircle(x, y, pauseX(), topBtnY(), topBtnR() * 1.3f)) { setState(State.PAUSED); mixer.play(Sfx.UI_CLICK, 0.6f); return ROLE_BUTTON }
        if (inCircle(x, y, mapX(), topBtnY(), topBtnR() * 1.3f)) { showMap = !showMap; mixer.play(Sfx.UI_CLICK, 0.6f); return ROLE_BUTTON }
        if (y >= slotsY0() - hf * 0.02f && x >= slotsX0() && x <= slotsX0() + slotW() * 6f) {
            val slot = ((x - slotsX0()) / slotW()).toInt().coerceIn(0, 5)
            if (w.player.owned[slot]) input.select = slot
            return ROLE_BUTTON
        }
        if (inCircle(x, y, nextX(), nextY(), nextR() * 1.25f)) { input.next = true; return ROLE_BUTTON }
        if (inCircle(x, y, fireX(), fireY(), fireR() * 1.15f)) return ROLE_FIRE
        if (x < wf * 0.45f) {
            if (stickId < 0) {
                stickId = p.id
                val r = stickR()
                stickBaseX = x.coerceIn(r * 1.1f, wf * 0.45f - r * 0.5f)
                stickBaseY = y.coerceIn(hf * 0.2f + r, hf - r * 1.05f)
                return ROLE_STICK
            }
            return ROLE_LOOK
        }
        return ROLE_LOOK
    }

    private fun buildInput() {
        input.moveX = 0f; input.moveY = 0f
        val sp = ptrs[stickId]
        if (sp != null) {
            val r = stickR()
            var vx = (sp.x - stickBaseX) / r; var vy = (sp.y - stickBaseY) / r
            val l = sqrt(vx * vx + vy * vy)
            if (l > 1f) { vx /= l; vy /= l }
            val dead = 0.12f
            val lc = min(1f, l)
            if (lc < dead) { vx = 0f; vy = 0f } else {
                val k = (lc - dead) / (1f - dead) / lc
                vx *= k; vy *= k
            }
            input.moveX = vx
            input.moveY = -vy
        }
        val k = settings.sensitivity * 2.6f / hf
        input.lookYaw += -lookDx * k
        input.lookPitch += -lookDy * k * 0.75f * (if (settings.invertY) -1f else 1f)
        lookDx = 0f; lookDy = 0f
        input.fire = ptrs.values.any { it.role == ROLE_FIRE }
    }

    // ------------------------------------------------------------------ mise à jour

    private fun update(dt: Float) {
        msgTime = max(0f, msgTime - dt)
        shake = max(0f, shake - dt * 2.5f)
        val w = world
        when (state) {
            State.PLAYING -> if (w != null) {
                buildInput()
                w.aimAssist = settings.aimAssist
                w.update(dt, input)
                input.clearImpulses()
                if (w.completed) onLevelComplete(w)
                else if (w.player.dead && w.player.deadTime > 1.8f) setState(State.DEAD)
            }
            State.DEAD -> if (w != null) { input.moveX = 0f; input.moveY = 0f; input.fire = false; w.update(dt, input) }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ rendu

    private fun render() {
        val r = renderer ?: return
        val u = ui ?: return
        r.width = width; r.height = height
        val w = world
        val inGame = w != null && (state == State.INTRO || state == State.PLAYING || state == State.PAUSED || state == State.LEVEL_DONE || state == State.DEAD) ||
            (state == State.OPTIONS && optionsReturn == State.PAUSED && w != null)
        if (inGame && w != null) {
            val p = w.player
            cam.x = p.x; cam.y = p.y
            val bob = sin(p.bobPhase * 2f) * 0.018f * p.bobAmount
            val sk = shake * shake
            cam.z = p.eyeZ + bob + rng.signed() * 0.03f * sk
            cam.yaw = p.yaw + rng.signed() * 0.02f * sk
            cam.pitch = p.pitch + rng.signed() * 0.02f * sk
            if (state == State.INTRO) { cam.yaw = p.yaw + sin(stateTime * 0.3f) * 0.15f }
            r.render(w, cam, time, !p.dead && state != State.INTRO)
        } else {
            val a = attract ?: World(Levels.all[0], 1, NoEvents).also { attract = it }
            val vw = if (state == State.VICTORY && w != null) w else a
            if (vw === a) {
                cam.x = 7f; cam.y = 22.5f; cam.z = 0.75f
                cam.yaw = time * 0.07f; cam.pitch = 0.18f + sin(time * 0.11f) * 0.06f
            } else {
                cam.x = w!!.player.x; cam.y = w.player.y; cam.z = w.player.eyeZ + 0.3f
                cam.yaw = w.player.yaw + time * 0.1f; cam.pitch = 0.25f
            }
            r.render(vw, cam, time, false)
        }
        u.begin(width, height)
        when (state) {
            State.PLAYING -> drawHud(u, w!!, true)
            State.PAUSED -> { drawHud(u, w!!, false); drawPause(u, w) }
            State.DEAD -> { drawHud(u, w!!, false); drawDead(u) }
            State.LEVEL_DONE -> drawLevelDone(u, w!!)
            State.INTRO -> drawIntro(u, w!!)
            State.TITLE -> drawTitle(u)
            State.DIFFICULTY -> drawDifficulty(u)
            State.OPTIONS -> drawOptions(u)
            State.HELP -> drawHelp(u)
            State.VICTORY -> drawVictory(u)
        }
        u.end()
    }

    // ------------------------------------------------------------------ widgets

    private fun button(u: Ui, label: String, cx: Float, cy: Float, bw: Float, bh: Float, accent: Boolean = false): Boolean {
        val x0 = cx - bw / 2; val y0 = cy - bh / 2; val x1 = cx + bw / 2; val y1 = cy + bh / 2
        fun inside(x: Float, y: Float) = x in x0..x1 && y in y0..y1
        val pressed = ptrs.values.any { it.role == ROLE_UI && inside(it.x, it.y) && inside(it.sx, it.sy) }
        val base = if (accent) ORANGE else GREY
        u.rect(x0, y0, x1, y1, 0.04f, 0.05f, 0.07f, if (pressed) 0.95f else 0.78f)
        if (pressed) u.rect(x0, y0, x1, y1, base[0], base[1], base[2], 0.35f)
        u.frame(x0, y0, x1, y1, max(2f, bh * 0.045f), base[0], base[1], base[2], 0.9f)
        val ts = u.px(1f / 170f)
        u.text(label, cx, cy - ts * 4f, ts, 1f, 1f, 1f, 1f, 0)
        val clicked = clickX >= 0f && inside(clickX, clickY) && inside(clickSX, clickSY)
        if (clicked) { clickX = -1f; mixer.play(Sfx.UI_CLICK, 0.7f) }
        return clicked
    }

    private fun slider(u: Ui, label: String, value: Float, min: Float, max: Float, cx: Float, cy: Float, bw: Float): Float {
        val ts = u.px(1f / 190f)
        val x0 = cx - bw / 2; val x1 = cx + bw / 2
        val bh = hf * 0.075f
        u.text(label, x0, cy - bh * 0.9f, ts, 0.85f, 0.88f, 0.95f)
        val barY = cy + bh * 0.15f
        u.rect(x0, barY - hf * 0.008f, x1, barY + hf * 0.008f, 0.25f, 0.27f, 0.3f, 0.9f)
        var v = value
        for (p in ptrs.values) {
            if (p.role != ROLE_UI) continue
            if (p.sx in x0 - 20f..x1 + 20f && abs(p.sy - barY) < bh * 0.7f) v = min + (max - min) * ((p.x - x0) / (x1 - x0)).coerceIn(0f, 1f)
        }
        val t = (v - min) / (max - min)
        u.rect(x0, barY - hf * 0.008f, x0 + (x1 - x0) * t, barY + hf * 0.008f, ORANGE[0], ORANGE[1], ORANGE[2], 1f)
        u.circle(x0 + (x1 - x0) * t, barY, hf * 0.03f, 1f, 1f, 1f, 1f)
        return v
    }

    private fun dim(u: Ui, a: Float = 0.6f) = u.rect(0f, 0f, wf, hf, 0f, 0f, 0f, a)

    private fun fmtTime(t: Float): String {
        val s = t.toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    // ------------------------------------------------------------------ écrans

    private fun drawTitle(u: Ui) {
        dim(u, 0.35f)
        val big = u.px(1f / 70f)
        val pulse = 0.85f + 0.15f * sin(time * 2f)
        u.text("IO : QUARANTAINE", wf / 2, hf * 0.1f, big, 1f * pulse, 0.42f * pulse, 0.1f, 1f, 0)
        u.text("Station minière Prométhée — Lune de Jupiter", wf / 2, hf * 0.1f + big * 10f, u.px(1f / 200f), 0.9f, 0.85f, 0.7f, 1f, 0)
        val bw = min(wf * 0.5f, hf * 1.0f); val bh = hf * 0.105f
        var y = hf * 0.42f
        val step = bh * 1.2f
        if (progress.level >= 0) {
            if (button(u, "CONTINUER — ${Levels.all[progress.level.coerceIn(0, 3)].title}", wf / 2, y, bw, bh, true)) { continueGame(); return }
            y += step
        }
        if (button(u, "NOUVELLE PARTIE", wf / 2, y, bw, bh, progress.level < 0)) { setState(State.DIFFICULTY); return }
        y += step
        if (button(u, "OPTIONS", wf / 2, y, bw, bh)) { optionsReturn = State.TITLE; setState(State.OPTIONS); return }
        y += step
        if (button(u, "COMMENT JOUER", wf / 2, y, bw, bh)) { setState(State.HELP); return }
        y += step
        if (button(u, "QUITTER", wf / 2, y, bw, bh)) { host.quit(); return }
        val small = u.px(1f / 260f)
        if (progress.completed) u.text("Campagne terminée — L'Hôte a été vaincu", wf / 2, hf - small * 14f, small, 0.5f, 1f, 0.5f, 0.9f, 0)
        u.text("v1.0", wf - small * 4f, hf - small * 12f, small, 0.6f, 0.6f, 0.6f, 0.7f, 1)
    }

    private fun drawDifficulty(u: Ui) {
        dim(u)
        val ts = u.px(1f / 110f)
        u.text("DIFFICULTÉ", wf / 2, hf * 0.08f, ts, 1f, 0.55f, 0.15f, 1f, 0)
        val bw = min(wf * 0.6f, hf * 1.2f); val bh = hf * 0.13f
        for (d in 0..2) {
            val cy = hf * 0.34f + d * bh * 1.45f
            if (button(u, Difficulty.NAMES[d], wf / 2, cy, bw, bh, d == 1)) { startNewGame(d); return }
            u.text(Difficulty.DESCRIPTIONS[d], wf / 2, cy + bh * 0.52f, u.px(1f / 240f), 0.75f, 0.78f, 0.85f, 1f, 0)
        }
        if (button(u, "RETOUR", wf / 2, hf * 0.88f, bw * 0.5f, hf * 0.09f)) setState(State.TITLE)
    }

    private fun drawOptions(u: Ui) {
        dim(u, 0.72f)
        val ts = u.px(1f / 110f)
        u.text("OPTIONS", wf / 2, hf * 0.05f, ts, 1f, 0.55f, 0.15f, 1f, 0)
        val colW = min(wf * 0.4f, hf * 0.85f)
        val lx = wf * 0.27f; val rx = wf * 0.73f
        settings.sensitivity = slider(u, "SENSIBILITÉ DE VISÉE  ×%.1f".format(settings.sensitivity), settings.sensitivity, 0.3f, 3f, lx, hf * 0.3f, colW)
        settings.musicVolume = slider(u, "VOLUME DE LA MUSIQUE  ${(settings.musicVolume * 100).toInt()} %", settings.musicVolume, 0f, 1f, lx, hf * 0.5f, colW)
        settings.sfxVolume = slider(u, "VOLUME DES EFFETS  ${(settings.sfxVolume * 100).toInt()} %", settings.sfxVolume, 0f, 1f, lx, hf * 0.7f, colW)
        applyAudioSettings()
        val bh = hf * 0.1f
        if (button(u, "AXE VERTICAL : " + if (settings.invertY) "INVERSÉ" else "NORMAL", rx, hf * 0.27f, colW, bh)) settings.invertY = !settings.invertY
        if (button(u, "VISÉE ASSISTÉE : " + if (settings.aimAssist) "OUI" else "NON", rx, hf * 0.45f, colW, bh)) settings.aimAssist = !settings.aimAssist
        if (button(u, "VIBRATIONS : " + if (settings.vibration) "OUI" else "NON", rx, hf * 0.63f, colW, bh)) {
            settings.vibration = !settings.vibration
            if (settings.vibration) host.vibrate(60, 0.6f)
        }
        if (button(u, "RETOUR", wf / 2, hf * 0.88f, colW * 0.6f, bh, true)) { settings.save(host); setState(optionsReturn) }
    }

    private fun drawHelp(u: Ui) {
        dim(u, 0.78f)
        val ts = u.px(1f / 110f)
        u.text("COMMENT JOUER", wf / 2, hf * 0.05f, ts, 1f, 0.55f, 0.15f, 1f, 0)
        val s = u.px(1f / 200f)
        val lines = listOf(
            "Pouce gauche : joystick de déplacement (avancer, reculer, pas de côté).",
            "Pouce droit : glissez pour tourner et viser. Bouton rouge : tirer.",
            "Le bouton de tir peut aussi être glissé pour viser en tirant.",
            "Changer d'arme : touchez une icône en bas, ou le petit bouton rond à droite.",
            "Les portes s'ouvrent à votre approche. Certaines exigent une carte d'accès.",
            "Poussez les murs suspects : des passages secrets s'y cachent.",
            "Évitez le soufre en fusion. Tirez sur les barils pour les faire exploser.",
            "Atteignez l'interrupteur SORTIE pour terminer le secteur.",
            "Bouton CARTE en haut à gauche : plan des zones explorées.",
        )
        lines.forEachIndexed { i, l -> u.text(l, wf * 0.08f, hf * 0.2f + i * s * 13f, s, 0.9f, 0.92f, 0.95f) }
        if (button(u, "RETOUR", wf / 2, hf * 0.9f, hf * 0.6f, hf * 0.09f, true)) setState(State.TITLE)
    }

    private fun drawIntro(u: Ui, w: World) {
        dim(u, 0.62f)
        val def = w.def
        val big = u.px(1f / 80f)
        u.text(def.title, wf / 2, hf * 0.12f, u.px(1f / 150f), 0.9f, 0.85f, 0.7f, 1f, 0)
        u.text(def.subtitle, wf / 2, hf * 0.2f, big, 1f, 0.5f, 0.12f, 1f, 0)
        val s = u.px(1f / 190f)
        def.briefing.forEachIndexed { i, l ->
            val a = ((stateTime - 0.3f - i * 0.35f) * 3f).coerceIn(0f, 1f)
            u.text(l, wf / 2, hf * 0.42f + i * s * 13f, s, 0.92f, 0.94f, 1f, a, 0)
        }
        u.text("Difficulté : ${Difficulty.NAMES[difficulty]}", wf / 2, hf * 0.78f, u.px(1f / 260f), 0.7f, 0.72f, 0.8f, 1f, 0)
        if (stateTime > 1f) {
            val a = 0.6f + 0.4f * sin(time * 4f)
            u.text("Touchez l'écran pour commencer", wf / 2, hf * 0.88f, u.px(1f / 210f), 1f, 1f, 1f, a, 0)
            if (clickX >= 0f) { clickX = -1f; setState(State.PLAYING) }
        }
    }

    private fun drawPause(u: Ui, w: World) {
        dim(u, 0.6f)
        u.text("PAUSE", wf / 2, hf * 0.07f, u.px(1f / 100f), 1f, 0.55f, 0.15f, 1f, 0)
        val s = u.px(1f / 230f)
        u.text("${w.def.title} — ${w.def.subtitle}", wf / 2, hf * 0.19f, s, 0.85f, 0.85f, 0.85f, 1f, 0)
        u.text("Ennemis ${w.kills}/${w.killsTotal}   Secrets ${w.secretsFound}/${w.level.secretsTotal}   Temps ${fmtTime(w.time)}", wf / 2, hf * 0.25f, s, 0.75f, 0.8f, 0.9f, 1f, 0)
        val bw = min(wf * 0.5f, hf * 1f); val bh = hf * 0.1f
        if (button(u, "REPRENDRE", wf / 2, hf * 0.4f, bw, bh, true)) { setState(State.PLAYING); return }
        if (button(u, "OPTIONS", wf / 2, hf * 0.53f, bw, bh)) { optionsReturn = State.PAUSED; setState(State.OPTIONS); return }
        if (button(u, "RECOMMENCER LE SECTEUR", wf / 2, hf * 0.66f, bw, bh)) { restartLevel(); return }
        if (button(u, "MENU PRINCIPAL", wf / 2, hf * 0.79f, bw, bh)) { toTitle(); return }
    }

    private fun drawDead(u: Ui) {
        u.rect(0f, 0f, wf, hf, 0.35f, 0f, 0f, min(0.55f, stateTime * 0.5f))
        u.text("VOUS ÊTES MORT", wf / 2, hf * 0.22f, u.px(1f / 75f), 1f, 0.15f, 0.1f, 1f, 0)
        if (stateTime < 0.8f) return
        val bw = min(wf * 0.5f, hf * 1f); val bh = hf * 0.1f
        if (button(u, "RECOMMENCER LE SECTEUR", wf / 2, hf * 0.55f, bw, bh, true)) { restartLevel(); return }
        if (button(u, "MENU PRINCIPAL", wf / 2, hf * 0.69f, bw, bh)) { toTitle(); return }
    }

    private fun drawLevelDone(u: Ui, w: World) {
        dim(u, 0.7f)
        u.text("${w.def.title} TERMINÉ", wf / 2, hf * 0.08f, u.px(1f / 95f), 1f, 0.55f, 0.15f, 1f, 0)
        u.text(w.def.subtitle, wf / 2, hf * 0.18f, u.px(1f / 210f), 0.85f, 0.85f, 0.8f, 1f, 0)
        val s = u.px(1f / 150f)
        val lx = wf * 0.3f; val rx = wf * 0.7f
        val pk = if (w.killsTotal > 0) w.kills * 100 / w.killsTotal else 100
        val ps = if (w.level.secretsTotal > 0) w.secretsFound * 100 / w.level.secretsTotal else 100
        val rows = listOf(
            "ENNEMIS" to "$pk %",
            "SECRETS" to "$ps %",
            "TEMPS" to fmtTime(doneTime),
            "RÉFÉRENCE" to fmtTime(w.def.parTime.toFloat()),
            "RECORD" to (if (doneNewRecord) "NOUVEAU !" else doneBest?.let { fmtTime(it) } ?: "—"),
        )
        rows.forEachIndexed { i, (k, v) ->
            val y = hf * 0.3f + i * s * 12f
            val a = ((stateTime - i * 0.25f) * 3f).coerceIn(0f, 1f)
            u.text(k, lx, y, s, 0.85f, 0.88f, 0.95f, a)
            u.text(v, rx, y, s, if (k == "RECORD" && doneNewRecord) 0.4f else 1f, 1f, if (k == "RECORD" && doneNewRecord) 0.4f else 1f, a, 1)
        }
        if (stateTime > 1.2f && button(u, "CONTINUER", wf / 2, hf * 0.88f, min(wf * 0.4f, hf * 0.8f), hf * 0.1f, true)) {
            enterLevel(levelIndex + 1, w.player, true)
        }
    }

    private fun drawVictory(u: Ui) {
        dim(u, 0.55f)
        u.text("VICTOIRE", wf / 2, hf * 0.07f, u.px(1f / 70f), 0.5f, 1f, 0.45f, 1f, 0)
        val s = u.px(1f / 200f)
        val lines = listOf(
            "L'Hôte s'effondre dans un dernier hurlement.",
            "Le réacteur de Prométhée se stabilise lentement.",
            "Au-dessus du dôme, Jupiter veille, indifférente.",
            "La quarantaine est levée... pour cette fois.",
        )
        lines.forEachIndexed { i, l ->
            val a = ((stateTime - 0.5f - i * 0.6f) * 2f).coerceIn(0f, 1f)
            u.text(l, wf / 2, hf * 0.26f + i * s * 13f, s, 0.95f, 0.95f, 0.9f, a, 0)
        }
        val st = u.px(1f / 230f)
        val pk = if (totalKillsMax > 0) totalKills * 100 / totalKillsMax else 0
        if (totalKillsMax > 0) u.text("Ennemis éliminés : $pk %   Temps total : ${fmtTime(totalTime)}   Difficulté : ${Difficulty.NAMES[difficulty]}", wf / 2, hf * 0.66f, st, 0.75f, 0.85f, 1f, 1f, 0)
        if (stateTime > 2.5f && button(u, "MENU PRINCIPAL", wf / 2, hf * 0.85f, min(wf * 0.4f, hf * 0.8f), hf * 0.1f, true)) toTitle()
    }

    // ------------------------------------------------------------------ HUD

    private fun drawHud(u: Ui, w: World, controls: Boolean) {
        val p = w.player
        // Effets plein écran
        if (p.hurtFlash > 0f) {
            u.vignette(0.85f, 0f, 0f, min(1f, p.hurtFlash * 1.2f))
            u.rect(0f, 0f, wf, hf, 0.6f, 0f, 0f, p.hurtFlash * 0.18f)
        }
        if (p.pickupFlash > 0f) u.rect(0f, 0f, wf, hf, 1f, 0.9f, 0.5f, p.pickupFlash * 0.07f)
        val cell = w.level.cellAt(p.x, p.y)
        if (cell != null && cell.lava && p.z <= cell.floor + 0.02f) u.vignette(1f, 0.45f, 0f, 0.5f + 0.2f * sin(time * 12f))
        if (p.hurtDirTime > 0f) {
            val rel = wrapAngle(p.hurtDirAngle - p.yaw)
            val ang = -rel
            val cx = wf / 2 + sin(ang) * hf * 0.3f; val cy = hf / 2 - cos(ang) * hf * 0.3f
            u.arrow(cx, cy, hf * 0.08f, ang, 1f, 0.15f, 0.1f, p.hurtDirTime)
        }
        // Réticule
        val c = hf * 0.012f; val t = max(2f, hf * 0.003f)
        val cr = if (p.cooldown > 0f) 1f else 0.85f
        u.rect(wf / 2 - c * 2, hf / 2 - t / 2, wf / 2 - c * 0.7f, hf / 2 + t / 2, cr, 1f, cr, 0.85f)
        u.rect(wf / 2 + c * 0.7f, hf / 2 - t / 2, wf / 2 + c * 2, hf / 2 + t / 2, cr, 1f, cr, 0.85f)
        u.rect(wf / 2 - t / 2, hf / 2 - c * 2, wf / 2 + t / 2, hf / 2 - c * 0.7f, cr, 1f, cr, 0.85f)
        u.rect(wf / 2 - t / 2, hf / 2 + c * 0.7f, wf / 2 + t / 2, hf / 2 + c * 2, cr, 1f, cr, 0.85f)

        if (showMap) drawMap(u, w)

        // Barre de vie du boss
        val boss = w.boss
        if (boss != null && boss.alive && boss.state != 0) {
            val bw = wf * 0.4f; val x0 = wf / 2 - bw / 2; val y0 = hf * 0.155f; val bh = hf * 0.025f
            u.rect(x0 - 3, y0 - 3, x0 + bw + 3, y0 + bh + 3, 0f, 0f, 0f, 0.7f)
            u.rect(x0, y0, x0 + bw * boss.hp / boss.type.hp, y0 + bh, 0.85f, 0.15f, 0.1f, 0.95f)
            u.text("L'HÔTE", wf / 2, y0 + bh + hf * 0.01f, u.px(1f / 260f), 1f, 0.7f, 0.6f, 1f, 0)
        }

        // Santé et armure (haut gauche)
        val big = u.px(1f / 120f)
        val hx = hf * 0.32f
        val hy = hf * 0.035f
        val low = p.health <= 25
        val hc = if (low) (if (sin(time * 8f) > 0f) 1f else 0.6f) else 0.95f
        u.iconAt(Icons.HEALTH, hx, hy + big * 4f, big * 6f, 1f, 0.25f, 0.2f, 1f)
        u.text("${p.health}", hx + big * 4.5f, hy, big, hc, if (low) 0.3f else 0.95f, if (low) 0.25f else 0.95f)
        val ax = hx + big * 26f
        u.iconAt(Icons.ARMOR, ax, hy + big * 4f, big * 6f, if (p.armorHeavy) 0.3f else 0.35f, if (p.armorHeavy) 0.55f else 0.85f, if (p.armorHeavy) 1f else 0.4f, if (p.armor > 0) 1f else 0.35f)
        u.text("${p.armor}", ax + big * 4.5f, hy, big, 0.9f, 0.95f, 1f, if (p.armor > 0) 1f else 0.5f)

        // Munitions et cartes (haut droite)
        val wd = Weapons.defs[p.weapon]
        val ammoTxt = if (wd.ammo == Ammo.NONE) "∞" else "${p.ammo[wd.ammo]}"
        u.text(if (ammoTxt == "∞") "--" else ammoTxt, wf - hf * 0.05f, hy, big, 1f, 0.85f, 0.4f, 1f, 1)
        val sm = u.px(1f / 300f)
        u.text(wd.name, wf - hf * 0.05f, hy + big * 9.5f, sm, 0.85f, 0.85f, 0.85f, 0.9f, 1)
        val ammoShort = arrayOf("BAL", "CAR", "ROQ", "CEL")
        for (i in 0 until 4) {
            val y = hy + big * 9.5f + sm * 11f * (i + 1)
            val active = wd.ammo == i
            u.text("${ammoShort[i]} ${p.ammo[i]}/${Ammo.MAX[i]}", wf - hf * 0.05f, y, sm, if (active) 1f else 0.6f, if (active) 0.8f else 0.62f, if (active) 0.3f else 0.66f, 0.9f, 1)
        }
        for (k in 0 until 3) {
            val kc = KEY_COLORS[k]
            val kx = wf - hf * 0.05f - big * 22f - k * big * 7f
            if (p.keys[k]) u.iconAt(Icons.KEY, kx, hy + big * 4f, big * 5f, kc[0], kc[1], kc[2], 1f)
            else u.iconAt(Icons.KEY, kx, hy + big * 4f, big * 5f, 0.3f, 0.3f, 0.3f, 0.35f)
        }

        // Message
        if (msgTime > 0f) u.text(msg, wf / 2, hf * 0.2f, u.px(1f / 200f), 1f, 0.95f, 0.75f, min(1f, msgTime * 2f), 0)

        if (!controls) return
        val ui = 0.55f
        // Boutons du haut
        u.circle(pauseX(), topBtnY(), topBtnR(), 0f, 0f, 0f, 0.4f)
        u.ring(pauseX(), topBtnY(), topBtnR(), 1f, 1f, 1f, ui)
        u.iconAt(Icons.PAUSE, pauseX(), topBtnY(), topBtnR() * 0.9f, 1f, 1f, 1f, ui + 0.2f)
        u.circle(mapX(), topBtnY(), topBtnR(), 0f, 0f, 0f, 0.4f)
        u.ring(mapX(), topBtnY(), topBtnR(), if (showMap) 1f else 1f, if (showMap) 0.6f else 1f, if (showMap) 0.2f else 1f, ui)
        u.iconAt(Icons.MAP, mapX(), topBtnY(), topBtnR() * 0.9f, 1f, 1f, 1f, ui + 0.2f)

        // Emplacements d'armes
        for (i in 0 until Weapons.COUNT) {
            val x0 = slotsX0() + i * slotW(); val y0 = slotsY0()
            val owned = p.owned[i]
            val sel = p.weapon == i
            u.rect(x0 + 2, y0, x0 + slotW() - 2, y0 + slotH(), 0f, 0f, 0f, if (sel) 0.55f else 0.3f)
            if (sel) u.frame(x0 + 2, y0, x0 + slotW() - 2, y0 + slotH(), 2f, ORANGE[0], ORANGE[1], ORANGE[2], 0.9f)
            val a = if (!owned) 0.15f else if (!w.hasAmmoFor(i)) 0.35f else if (sel) 1f else 0.7f
            u.iconAt(Icons.WEAPONS[i], x0 + slotW() / 2, y0 + slotH() * 0.45f, slotH() * 0.6f, if (sel) 1f else 0.9f, if (sel) 0.75f else 0.9f, if (sel) 0.4f else 0.9f, a)
            u.text("${i + 1}", x0 + slotW() * 0.1f, y0 + slotH() * 0.1f, u.px(1f / 400f), 0.8f, 0.8f, 0.8f, a, -1, false)
        }

        // Tir et arme suivante
        val firing = ptrs.values.any { it.role == ROLE_FIRE }
        u.circle(fireX(), fireY(), fireR(), 0.75f, 0.08f, 0.05f, if (firing) 0.55f else 0.28f)
        u.ring(fireX(), fireY(), fireR(), 1f, 0.35f, 0.25f, 0.75f)
        u.iconAt(Icons.FIRE, fireX(), fireY(), fireR() * 0.85f, 1f, 0.85f, 0.8f, 0.8f)
        u.circle(nextX(), nextY(), nextR(), 0f, 0f, 0f, 0.35f)
        u.ring(nextX(), nextY(), nextR(), 1f, 1f, 1f, ui)
        u.iconAt(Icons.CYCLE, nextX(), nextY(), nextR() * 1.1f, 1f, 1f, 1f, ui + 0.2f)

        // Joystick
        val r = stickR()
        val sp = ptrs[stickId]
        if (sp != null) {
            u.circle(stickBaseX, stickBaseY, r, 0f, 0f, 0f, 0.25f)
            u.ring(stickBaseX, stickBaseY, r, 1f, 1f, 1f, 0.5f)
            var dx = sp.x - stickBaseX; var dy = sp.y - stickBaseY
            val l = sqrt(dx * dx + dy * dy)
            if (l > r) { dx = dx / l * r; dy = dy / l * r }
            u.circle(stickBaseX + dx, stickBaseY + dy, r * 0.42f, 1f, 1f, 1f, 0.55f)
        } else {
            val bx = r * 1.25f + wf * 0.02f; val by = hf - r * 1.3f
            u.ring(bx, by, r, 1f, 1f, 1f, 0.22f)
            u.circle(bx, by, r * 0.42f, 1f, 1f, 1f, 0.2f)
        }
    }

    private fun drawMap(u: Ui, w: World) {
        val p = w.player
        val lv = w.level
        val x0 = wf * 0.12f; val x1 = wf * 0.88f; val y0 = hf * 0.14f; val y1 = hf * 0.84f
        u.rect(x0, y0, x1, y1, 0f, 0.02f, 0.03f, 0.78f)
        u.frame(x0, y0, x1, y1, 2f, 0.3f, 0.6f, 0.7f, 0.8f)
        val cs = hf * 0.034f
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
        for (gy in 0 until lv.h) for (gx in 0 until lv.w) {
            val cl = lv.cell(gx, gy)!!
            if (!cl.seen) continue
            val sx = cx + (gx - p.x) * cs; val sy = cy - (gy + 1 - p.y) * cs
            if (sx < x0 || sx + cs > x1 || sy < y0 || sy + cs > y1) continue
            val d = cl.door
            when {
                cl.exit -> u.rect(sx, sy, sx + cs, sy + cs, 0.2f, 1f, 0.3f, 0.95f)
                cl.solid -> u.rect(sx, sy, sx + cs, sy + cs, 0.45f, 0.5f, 0.55f, 0.9f)
                d != null && !d.secret -> {
                    val kc = if (d.key >= 0) KEY_COLORS[d.key] else DOOR_COLOR
                    u.rect(sx, sy, sx + cs, sy + cs, kc[0], kc[1], kc[2], 0.95f)
                }
                d != null -> u.rect(sx, sy, sx + cs, sy + cs, 0.45f, 0.5f, 0.55f, 0.9f)
                cl.lava -> u.rect(sx, sy, sx + cs, sy + cs, 0.7f, 0.25f, 0.05f, 0.8f)
                cl.crate -> u.rect(sx, sy, sx + cs, sy + cs, 0.3f, 0.35f, 0.2f, 0.85f)
                else -> {
                    val b = 0.12f + cl.floor * 0.1f
                    u.rect(sx, sy, sx + cs, sy + cs, b, b + 0.03f, b + 0.05f, 0.85f)
                }
            }
        }
        for (pk in w.pickups) {
            if (pk.taken || pk.ch !in "ruy") continue
            if (lv.cellAt(pk.x, pk.y)?.seen != true) continue
            val k = "ruy".indexOf(pk.ch)
            val sx = cx + (pk.x - p.x) * cs; val sy = cy - (pk.y - p.y) * cs
            if (sx in x0..x1 && sy in y0..y1) u.circle(sx, sy, cs * 0.35f, KEY_COLORS[k][0], KEY_COLORS[k][1], KEY_COLORS[k][2], 1f)
        }
        u.arrow(cx, cy, cs * 1.3f, PI / 2f - p.yaw, 1f, 0.85f, 0.2f, 1f)
        val s = u.px(1f / 280f)
        u.text("ENNEMIS ${w.kills}/${w.killsTotal}   SECRETS ${w.secretsFound}/${w.level.secretsTotal}   TEMPS ${fmtTime(w.time)}", cx, y1 - s * 12f, s, 0.85f, 0.9f, 1f, 1f, 0)
    }

    // ------------------------------------------------------------------ outils de test

    /** Démarre directement un secteur (banc de test). */
    fun debugStart(index: Int, diff: Int, allWeapons: Boolean) {
        difficulty = diff
        enterLevel(index, null, false)
        if (allWeapons) {
            val p = world!!.player
            for (i in 0 until Weapons.COUNT) p.owned[i] = true
            for (i in 0 until 4) p.ammo[i] = Ammo.MAX[i] / 2
        }
    }

    fun debugSetState(s: State) = setState(s)
    fun debugInput(): Input = input
    fun debugStickHold(id: Int) { stickId = id }

    @Suppress("unused")
    private fun enemyCount(w: World) = w.enemies.count { it.alive && it.type.id != Enemies.BARREL }

    private fun atan2f(y: Float, x: Float) = atan2(y, x)
}
