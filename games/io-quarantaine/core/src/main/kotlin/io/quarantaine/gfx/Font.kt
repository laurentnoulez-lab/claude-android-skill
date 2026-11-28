package io.quarantaine.gfx

/**
 * Police bitmap 5x8 dessinée à la main (domaine du jeu, aucune police externe).
 * Chaque glyphe : 8 lignes, bit 4 = pixel le plus à gauche. Ligne 7 = jambage.
 */
object Font {
    const val GLYPH_W = 5
    const val GLYPH_H = 8
    const val ADVANCE = 6
    const val CELL_W = 8
    const val CELL_H = 10
    const val ATLAS = 128
    private const val COLS = ATLAS / CELL_W

    private val data: Map<Char, String> = mapOf(
        ' ' to "0000000000000000", '!' to "0404040404000400", '"' to "0A0A000000000000",
        '#' to "0A0A1F0A1F0A0A00", '%' to "1819020408130300", '&' to "0C12140815120D00",
        '\'' to "0404080000000000", '(' to "0204080808040200", ')' to "0804020202040800",
        '*' to "0004150E15040000", '+' to "0004041F04040000", ',' to "00000000000C0408",
        '-' to "0000001F00000000", '.' to "00000000000C0C00", '/' to "0001020408100000",
        '0' to "0E11131519110E00", '1' to "040C040404040E00", '2' to "0E11010204081F00",
        '3' to "1F02040201110E00", '4' to "02060A121F020200", '5' to "1F101E0101110E00",
        '6' to "0608101E11110E00", '7' to "1F01020408080800", '8' to "0E11110E11110E00",
        '9' to "0E11110F01020C00", ':' to "000C0C000C0C0000", ';' to "000C0C000C040800",
        '<' to "0204081008040200", '=' to "00001F001F000000", '>' to "0804020102040800",
        '?' to "0E11010204000400", '@' to "0E11010D15150E00",
        'A' to "0E1111111F111100", 'B' to "1E11111E11111E00", 'C' to "0E11101010110E00",
        'D' to "1C12111111121C00", 'E' to "1F10101E10101F00", 'F' to "1F10101E10101000",
        'G' to "0E11101711110F00", 'H' to "1111111F11111100", 'I' to "0E04040404040E00",
        'J' to "0702020202120C00", 'K' to "1112141814121100", 'L' to "1010101010101F00",
        'M' to "111B151511111100", 'N' to "1111191513111100", 'O' to "0E11111111110E00",
        'P' to "1E11111E10101000", 'Q' to "0E11111115120D00", 'R' to "1E11111E14121100",
        'S' to "0F10100E01011E00", 'T' to "1F04040404040400", 'U' to "1111111111110E00",
        'V' to "11111111110A0400", 'W' to "1111111515150A00", 'X' to "11110A040A111100",
        'Y' to "1111110A04040400", 'Z' to "1F01020408101F00", '[' to "0E08080808080E00",
        ']' to "0E02020202020E00", '_' to "0000000000001F00", '|' to "0404040404040400",
        'a' to "00000E010F110F00", 'b' to "1010161911111E00", 'c' to "00000E1010110E00",
        'd' to "01010D1311110F00", 'e' to "00000E111F100E00", 'f' to "0609081C08080800",
        'g' to "00000F11110F010E", 'h' to "1010161911111100", 'i' to "04000C0404040E00",
        'j' to "020006020202120C", 'k' to "1010121418141200", 'l' to "0C04040404040E00",
        'm' to "00001A1515111100", 'n' to "0000161911111100", 'o' to "00000E1111110E00",
        'p' to "00001E11111E1010", 'q' to "00000F11110F0101", 'r' to "0000161910101000",
        's' to "00000E100E011E00", 't' to "08081C0808090600", 'u' to "0000111111130D00",
        'v' to "00001111110A0400", 'w' to "0000111115150A00", 'x' to "0000110A040A1100",
        'y' to "00001111110F010E", 'z' to "00001F0204081F00",
        'é' to "02040E111F100E00", 'è' to "08040E111F100E00", 'ê' to "040A0E111F100E00",
        'ë' to "0A000E111F100E00", 'à' to "08040E010F110F00", 'â' to "040A0E010F110F00",
        'ç' to "00000E10100E040C", 'î' to "040A000C04040E00", 'ï' to "0A000C0404040E00",
        'ô' to "040A000E11110E00", 'ù' to "0804111111130D00", 'û' to "040A001111130D00",
        'É' to "02041F101E101F00", 'È' to "08041F101E101F00", 'Ê' to "040A1F101E101F00",
        'À' to "08040E111F111100", 'Â' to "040A0E111F111100", 'Ç' to "0E111010110E040C",
        'Ô' to "040A0E1111110E00", 'Î' to "040A0E0404040E00", 'Û' to "040A111111110E00",
        'Ù' to "0804111111110E00", '°' to "0C12120C00000000", '«' to "00050A140A050000",
        '»' to "00140A050A140000", '’' to "0C04080000000000", '×' to "00110A040A110000",
        '…' to "0000000000150000", '►' to "080C0E0F0E0C0800",
        '◄' to "02060E1E0E060200", '—' to "0000001F00000000", '∞' to "00000A150A000000", 'Œ' to "0F14141714140F00", 'œ' to "00000A1517140B00", '▲' to "0004040E0E1F0000", '▼' to "001F0E0E04040000"
    )

    private val chars: List<Char> = data.keys.toList()
    private val slot = HashMap<Char, Int>().apply { chars.forEachIndexed { i, c -> put(c, i) } }

    fun has(c: Char) = slot.containsKey(c)

    /** Index du glyphe dans l'atlas ('?' si inconnu). */
    fun glyphIndex(c: Char): Int = slot[c] ?: slot[c.uppercaseChar()] ?: slot['?']!!

    /** Coin haut-gauche (pixels) du glyphe dans l'atlas. */
    fun glyphX(i: Int) = (i % COLS) * CELL_W + 1
    fun glyphY(i: Int) = (i / COLS) * CELL_H + 1

    fun rows(c: Char): IntArray {
        val s = data[c] ?: data['?']!!
        return IntArray(8) { s.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    /** Construit l'atlas (blanc sur transparent). */
    fun buildAtlas(): Img {
        val img = Img(ATLAS, ATLAS)
        img.fill(1f, 1f, 1f, 0f)
        chars.forEachIndexed { i, c ->
            val rows = rows(c)
            val ox = glyphX(i); val oy = glyphY(i)
            for (y in 0 until GLYPH_H) for (x in 0 until GLYPH_W) {
                if (rows[y] and (0x10 shr x) != 0) img.set(ox + x, oy + y, 1f, 1f, 1f, 1f)
            }
        }
        return img
    }

    /** Dessine un texte dans une image (utilisé pour les panneaux texturés, ex. « SORTIE »). */
    fun drawInto(img: Img, text: String, x0: Int, y0: Int, cr: Float, cg: Float, cb: Float, ca: Float, scale: Int = 1) {
        var x = x0
        for (c in text) {
            val rows = rows(if (data.containsKey(c)) c else '?')
            for (y in 0 until GLYPH_H) for (xx in 0 until GLYPH_W) {
                if (rows[y] and (0x10 shr xx) != 0) {
                    for (sy in 0 until scale) for (sx in 0 until scale) img.set(x + xx * scale + sx, y0 + y * scale + sy, cr, cg, cb, ca)
                }
            }
            x += ADVANCE * scale
        }
    }

    fun width(text: String, scale: Float) = (text.length * ADVANCE - 1).coerceAtLeast(0) * scale
}
