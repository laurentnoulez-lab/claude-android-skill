package io.quarantaine.world

import io.quarantaine.gfx.Tex

/*
 * Légende des cartes ASCII (1 caractère = 1 cellule de 1 unité ≈ 3,2 m) :
 *  Murs pleins : # technique  % roche  & consoles  = tuyaux  ! danger  @ chair alien  E interrupteur de sortie
 *  Portes      : D normale  R rouge  B bleue  Y jaune  X passage secret (ressemble à un mur)
 *  Sols        : . dalles  : caillebotis  ; dalles + plafonnier  , sol infesté  * sol extérieur (ciel)
 *                ~ soufre en fusion (dégâts)  1..9 estrade (hauteur n x 0,25)  C caisse (obstacle 0,75)
 *  Objets      : P joueur  z mineur infecté  c rampant  s cracheur  b brute  d drone  M boss
 *                h petite trousse  H trousse  a gilet  A exo-armure  m balles  n cartouches  o roquettes  p cellules
 *                S fusil à pompe  G mitrailleuse  K lance-roquettes  U annihilateur  r/u/y cartes rouge/bleue/jaune
 *                t baril explosif  l colonne lumineuse
 */

class Cell(val ch: Char) {
    var solid = false
    var wallTex = Tex.TECH
    var floor = 0f
    var ceil = 2f
    var floorTex = Tex.FLOOR_TILE
    var ceilTex = Tex.CEIL
    var stepTex = Tex.STEP
    var sky = false
    var lava = false
    var crate = false
    var exit = false
    var door: Door? = null
    var seen = false
    /** Lumière pré-calculée au centre de la cellule (pour éclairer les objets mobiles). */
    var lr = 0.3f; var lg = 0.3f; var lb = 0.3f
}

class Door(val cx: Int, val cy: Int, val key: Int, val secret: Boolean, val tex: Int) {
    /** 0 = fermée, 1 = ouverte. */
    var open = 0f
    var state = CLOSED
    var timer = 0f
    var floor = 0f
    var top = 2f

    /** Altitude du bas du battant (le battant remonte dans le plafond). */
    fun bottom() = floor + open * (top - floor - 0.08f)

    companion object {
        const val CLOSED = 0
        const val OPENING = 1
        const val OPEN = 2
        const val CLOSING = 3
    }
}

class Thing(val ch: Char, val x: Float, val y: Float, val z: Float, val order: Int)

class Light(val x: Float, val y: Float, val z: Float, val radius: Float, val r: Float, val g: Float, val b: Float)

class LevelDef(
    val index: Int,
    val title: String,
    val subtitle: String,
    val briefing: List<String>,
    val rows: List<String>,
    val ceil: Float,
    val ambient: FloatArray,
    val skyAmbient: FloatArray,
    val fog: FloatArray,
    val fogDensity: Float,
    val music: Int,
    val startYawDeg: Float,
    val parTime: Int,
    val boss: Boolean = false,
)

class Level(val def: LevelDef) {
    val w = def.rows[0].length
    val h = def.rows.size
    val cells: Array<Cell>
    val doors = ArrayList<Door>()
    val things = ArrayList<Thing>()
    val lights = ArrayList<Light>()
    var startX = 1.5f
    var startY = 1.5f
    var startZ = 0f
    val hasSky: Boolean
    var secretsTotal = 0

    init {
        cells = Array(w * h) { i ->
            val cx = i % w; val cy = i / w
            Cell(def.rows[h - 1 - cy][cx])
        }
        for (cy in 0 until h) for (cx in 0 until w) setup(cx, cy, cell(cx, cy)!!)
        resolveEntityFloors()
        floodSky()
        hasSky = cells.any { it.sky }
        for (d in doors) {
            val c = cell(d.cx, d.cy)!!
            // La porte prend l'altitude du sol voisin le plus bas
            var f = Float.MAX_VALUE
            for ((dx, dy) in NEIGH4) {
                val n = cell(d.cx + dx, d.cy + dy) ?: continue
                if (!n.solid && n.door == null) f = minOf(f, n.floor)
            }
            if (f == Float.MAX_VALUE) f = 0f
            c.floor = f; c.ceil = f + def.ceil
            d.floor = f; d.top = c.ceil
        }
        collectLights()
    }

    fun cell(cx: Int, cy: Int): Cell? = if (cx < 0 || cy < 0 || cx >= w || cy >= h) null else cells[cy * w + cx]

    fun cellAt(x: Float, y: Float): Cell? = cell(fl(x), fl(y))

    private fun setup(cx: Int, cy: Int, c: Cell) {
        val ceil = def.ceil
        c.ceil = ceil
        when (val ch = c.ch) {
            '#' -> wall(c, Tex.TECH)
            '%' -> wall(c, Tex.ROCK)
            '&' -> wall(c, Tex.COMPUTER)
            '=' -> wall(c, Tex.PIPES)
            '!' -> wall(c, Tex.HAZARD)
            '@' -> wall(c, Tex.FLESH)
            'E' -> { wall(c, Tex.EXIT); c.exit = true }
            '.' -> {}
            ':' -> c.floorTex = Tex.FLOOR_GRATE
            ';' -> c.ceilTex = Tex.CEIL_LIGHT
            ',' -> c.floorTex = Tex.FLOOR_FLESH
            '*' -> { c.floorTex = Tex.GROUND; c.stepTex = Tex.ROCK }
            '~' -> { c.floor = -0.25f; c.floorTex = Tex.LAVA; c.lava = true; c.stepTex = Tex.ROCK }
            'C' -> { c.floor = 0.75f; c.floorTex = Tex.CRATE_TOP; c.stepTex = Tex.CRATE; c.crate = true }
            in '1'..'9' -> { c.floor = (ch - '0') * 0.25f; c.floorTex = Tex.FLOOR_PLATE }
            'D', 'R', 'B', 'Y', 'X' -> {
                val key = when (ch) { 'R' -> 0; 'B' -> 1; 'Y' -> 2; else -> -1 }
                val tex = when (ch) {
                    'R' -> Tex.DOOR_RED; 'B' -> Tex.DOOR_BLUE; 'Y' -> Tex.DOOR_YELLOW
                    'X' -> neighbourWallTex(cx, cy)
                    else -> Tex.DOOR
                }
                val d = Door(cx, cy, key, ch == 'X', tex)
                c.door = d
                doors.add(d)
                if (ch == 'X') secretsTotal++
            }
            else -> {
                if (ch in THING_CHARS) {
                    things.add(Thing(ch, cx + 0.5f, cy + 0.5f, 0f, things.size))
                    if (ch == 'P') { startX = cx + 0.5f; startY = cy + 0.5f }
                } else wall(c, Tex.TECH)
            }
        }
    }

    private fun wall(c: Cell, tex: Int) { c.solid = true; c.wallTex = tex }

    private fun neighbourWallTex(cx: Int, cy: Int): Int {
        for ((dx, dy) in NEIGH4) {
            val ch = def.rows.getOrNull(h - 1 - (cy + dy))?.getOrNull(cx + dx) ?: continue
            when (ch) { '#' -> return Tex.TECH; '%' -> return Tex.ROCK; '=' -> return Tex.PIPES; '@' -> return Tex.FLESH; '!' -> return Tex.HAZARD }
        }
        return Tex.TECH
    }

    /** Les cellules d'objets prennent l'altitude et le type de sol majoritaires parmi leurs 8 voisines. */
    private fun resolveEntityFloors() {
        for (cy in 0 until h) for (cx in 0 until w) {
            val c = cell(cx, cy)!!
            if (c.ch !in THING_CHARS) continue
            val counts = HashMap<Float, Int>()
            var tex = Tex.FLOOR_TILE; var texVotes = -1
            val texCount = HashMap<Int, Int>()
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val n = cell(cx + dx, cy + dy) ?: continue
                if (n.solid || n.door != null || n.crate || n.ch in THING_CHARS) continue
                counts[n.floor] = (counts[n.floor] ?: 0) + 1
                if (!n.lava) texCount[n.floorTex] = (texCount[n.floorTex] ?: 0) + 1
            }
            val best = counts.entries.maxWithOrNull(compareBy<Map.Entry<Float, Int>> { it.value }.thenBy { it.key })
            c.floor = best?.key ?: 0f
            for ((t, v) in texCount) if (v > texVotes) { texVotes = v; tex = t }
            c.floorTex = tex
            if (c.floor < 0f) { c.lava = true; c.floorTex = Tex.LAVA }
            if (c.ch == 'P') startZ = c.floor
        }
        for (t in things.indices) {
            val th = things[t]
            things[t] = Thing(th.ch, th.x, th.y, cellAt(th.x, th.y)!!.floor, th.order)
        }
    }

    /** Le ciel s'étend depuis les sols extérieurs (*) à travers les sols non intérieurs (lave, estrades, caisses...). */
    private fun floodSky() {
        val queue = ArrayDeque<Int>()
        for (i in cells.indices) if (cells[i].ch == '*') { cells[i].sky = true; queue.add(i) }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val cx = i % w; val cy = i / w
            for ((dx, dy) in NEIGH4) {
                val n = cell(cx + dx, cy + dy) ?: continue
                if (n.sky || n.solid || n.door != null) continue
                if (n.ch == '.' || n.ch == ';' || n.ch == ',') continue
                n.sky = true
                queue.add((cy + dy) * w + cx + dx)
            }
        }
        for (c in cells) if (c.sky) {
            c.ceil = SKY_HEIGHT
            if (c.floorTex == Tex.FLOOR_PLATE) c.floorTex = Tex.GROUND
            if (c.stepTex == Tex.STEP) c.stepTex = Tex.ROCK
        }
    }

    private fun collectLights() {
        for (cy in 0 until h) for (cx in 0 until w) {
            val c = cell(cx, cy)!!
            val x = cx + 0.5f; val y = cy + 0.5f
            when {
                c.ch == ';' -> lights.add(Light(x, y, c.ceil - 0.15f, 5.5f, 0.95f, 0.95f, 1f))
                c.lava && (cx + cy) % 2 == 0 -> lights.add(Light(x, y, c.floor + 0.35f, 3.2f, 1.1f, 0.45f, 0.1f))
                c.exit -> lights.add(Light(x, y, 0.9f, 3.5f, 0.3f, 1f, 0.4f))
                c.ch == '&' && openNeighbour(cx, cy) && (cx + cy) % 2 == 0 -> lights.add(Light(x, y, 0.8f, 2.2f, 0.15f, 0.55f, 0.35f))
                c.ch == '@' && openNeighbour(cx, cy) && (cx * 7 + cy * 3) % 5 == 0 -> lights.add(Light(x, y, 0.6f, 2.2f, 0.5f, 0.6f, 0.1f))
            }
        }
        for (t in things) {
            if (t.ch == 'l') lights.add(Light(t.x, t.y, t.z + 1.0f, 5f, 0.3f, 0.85f, 1f))
        }
    }

    private fun openNeighbour(cx: Int, cy: Int): Boolean {
        for ((dx, dy) in NEIGH4) { val n = cell(cx + dx, cy + dy) ?: continue; if (!n.solid) return true }
        return false
    }

    /** Hauteur de plafond effective (une porte fermée abaisse le « plafond » de sa cellule). */
    fun effCeil(c: Cell): Float = c.door?.bottom() ?: c.ceil

    companion object {
        const val SKY_HEIGHT = 9f
        const val SKY_WALL = 4.5f
        const val THING_CHARS = "PzcsbdMhHaAmnopSGKUruytl"
        val NEIGH4 = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        fun fl(v: Float) = kotlin.math.floor(v).toInt()
    }
}
