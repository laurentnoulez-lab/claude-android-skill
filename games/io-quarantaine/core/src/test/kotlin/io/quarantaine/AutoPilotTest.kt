package io.quarantaine

import io.quarantaine.game.Enemies
import io.quarantaine.game.Input
import io.quarantaine.game.NoEvents
import io.quarantaine.game.Physics
import io.quarantaine.game.World
import io.quarantaine.world.Level
import io.quarantaine.world.Levels
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Traverse chaque secteur avec la vraie simulation (collisions, portes, cartes, marches)
 * en suivant un chemin calculé sur la grille. Les ennemis sont neutralisés : on teste la carte.
 */
class AutoPilotTest {

    private fun path(w: World, sx: Int, sy: Int, goal: (Int, Int) -> Boolean): List<Pair<Int, Int>>? {
        val lv = w.level
        val prev = HashMap<Int, Int>()
        val q = ArrayDeque<Int>()
        val start = sy * lv.w + sx
        q.add(start); prev[start] = -1
        while (q.isNotEmpty()) {
            val i = q.removeFirst()
            val cx = i % lv.w; val cy = i / lv.w
            if (goal(cx, cy)) {
                val out = ArrayList<Pair<Int, Int>>()
                var k = i
                while (k != -1) { out.add(k % lv.w to k / lv.w); k = prev[k]!! }
                return out.reversed()
            }
            val c = lv.cell(cx, cy)!!
            for ((dx, dy) in Level.NEIGH4) {
                val nx = cx + dx; val ny = cy + dy
                val n = lv.cell(nx, ny) ?: continue
                val ni = ny * lv.w + nx
                if (prev.containsKey(ni)) continue
                if (goal(nx, ny)) { prev[ni] = i; q.addFirst(ni); continue }
                if (n.solid) continue
                val d = n.door
                if (d != null && d.key >= 0 && !w.player.keys[d.key]) continue
                if (d == null && n.floor - c.floor > Physics.STEP) continue
                if (n.crate) continue
                prev[ni] = i; q.add(ni)
            }
        }
        return null
    }

    private fun run(index: Int) {
        val w = World(Levels.all[index], 1, NoEvents)
        for (e in w.enemies) if (e.type.id != Enemies.BOSS) w.hurtEnemy(e, 100000, e.x, e.y, true, 0f)
        val input = Input()
        var t = 0f
        var route: List<Pair<Int, Int>>? = null
        var replan = 0f
        while (t < 600f && !w.completed) {
            val p = w.player
            p.health = 100
            replan -= 1f / 30f
            if (route == null || replan <= 0f) {
                replan = 0.5f
                val cx = floor(p.x).toInt(); val cy = floor(p.y).toInt()
                val keyCells = w.pickups.filter { !it.taken && it.ch in "ruy" }.map { floor(it.x).toInt() to floor(it.y).toInt() }.toSet()
                val boss = w.boss
                route = if (boss != null) {
                    val bx = floor(boss.x).toInt(); val by = floor(boss.y).toInt()
                    if (kotlin.math.abs(cx - bx) + kotlin.math.abs(cy - by) <= 3) emptyList()
                    else path(w, cx, cy) { x, y -> kotlin.math.abs(x - bx) + kotlin.math.abs(y - by) <= 3 }
                        ?: error("Arène du boss inaccessible depuis ($cx,$cy)")
                } else path(w, cx, cy) { x, y -> (x to y) in keyCells }
                    ?: path(w, cx, cy) { x, y -> w.level.cell(x, y)!!.exit }
                    ?: error("Secteur ${index + 1} : aucun chemin depuis ($cx,$cy), clés=${p.keys.toList()}")
            }
            val r = route!!
            if (r.isEmpty()) break
            // Vise la deuxième cellule du chemin
            val target = if (r.size > 2) r[2] else r.last()
            val tx = target.first + 0.5f; val ty = target.second + 0.5f
            p.yaw = atan2(ty - p.y, tx - p.x)
            input.moveY = 1f; input.moveX = 0f
            w.update(1f / 30f, input)
            t += 1f / 30f
        }
        if (Levels.all[index].boss) {
            val boss = w.boss!!
            val dist = kotlin.math.abs(w.player.x - boss.x) + kotlin.math.abs(w.player.y - boss.y)
            assertTrue(dist < 5f, "Le joueur n'a pas atteint le boss (distance $dist)")
            boss.hp = 1
            w.hurtEnemy(boss, 100000, boss.x, boss.y, true, 0f)
            repeat(200) { w.update(1f / 30f, input) }
        }
        assertTrue(w.completed, "Secteur ${index + 1} non terminé en ${t.toInt()} s (joueur en ${w.player.x},${w.player.y})")
        println("Secteur ${index + 1} traversé en ${t.toInt()} s simulées, clés ${w.player.keys.toList()}")
    }

    @Test fun sector1() = run(0)
    @Test fun sector2() = run(1)
    @Test fun sector3() = run(2)
    @Test fun boss() = run(3)
}
