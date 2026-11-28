package io.quarantaine

import io.quarantaine.gfx.Font
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Vérifie que tous les caractères des textes affichés existent dans la police bitmap. */
class FontTest {

    /** Extrait les littéraux de chaîne simples (hors chaînes brutes """...""", utilisées pour les shaders). */
    private fun literals(src: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < src.length) {
            if (src.startsWith("\"\"\"", i)) {
                val end = src.indexOf("\"\"\"", i + 3)
                i = if (end < 0) src.length else end + 3
                continue
            }
            if (src.startsWith("//", i)) { i = src.indexOf('\n', i).let { if (it < 0) src.length else it }; continue }
            if (src[i] == '\'' && i + 2 < src.length) {
                // caractère littéral : 'x' ou '\x'
                i += if (src[i + 1] == '\\') 4 else 3
                continue
            }
            if (src[i] == '"') {
                val sb = StringBuilder()
                var j = i + 1
                var depth = 0
                while (j < src.length && src[j] != '\n') {
                    val c = src[j]
                    if (depth == 0 && c == '"') break
                    if (c == '\\') { j += 2; continue }
                    if (c == '$' && j + 1 < src.length && src[j + 1] == '{') { depth++; j += 2; continue }
                    if (depth > 0) { if (c == '}') depth--; j++; continue }
                    if (c == '$' && j + 1 < src.length && src[j + 1].isLetter()) { j++; while (j < src.length && src[j].isLetterOrDigit()) j++; continue }
                    sb.append(c); j++
                }
                out.add(sb.toString())
                i = j + 1
                continue
            }
            i++
        }
        return out
    }

    @Test
    fun allDisplayedCharactersHaveGlyphs() {
        val root = File("src/main/kotlin")
        val missing = sortedSetOf<String>()
        root.walkTopDown().filter { it.extension == "kt" && it.name != "Font.kt" && it.name != "LevelMaps.kt" }.forEach { f ->
            for (s in literals(f.readText())) for (c in s) if (!Font.has(c)) missing.add("'$c' (${f.name}: \"$s\")")
        }
        assertTrue(missing.isEmpty(), "Glyphes manquants : $missing")
    }
}
