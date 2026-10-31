package com.foxfw.companion

/**
 * Faithful model of FoxFW's on-screen text_input keyboard, used to drive it blind over RPC.
 * The firmware exposes no "insert text" command — only the 6 hardware keys — so to type we
 * replicate the keyboard's cursor state machine and emit the exact Up/Down/Left/Right/OK path.
 *
 * Layout (applications/services/gui/modules/text_input.c):
 *   Letters: row0 "qwertyuiop"  row1 "asdfghjkl:"  row2 "zxcvbnm ./"      (all 10 cols)
 *   Symbols: row0 "1234567890"  row1 ":/.-_=@#&"   row2 "!?%+*,;"         (10 / 9 / 7 cols)
 *   Right-side button column: row0 = backspace, row1 = SYM/ABC layer toggle, row2 = save.
 * Nav wraps horizontally (grid<->buttons, no wall); vertically row0..2 with Up at row0 -> text field.
 * Uppercase = long-press OK (shift). Backspace = long-press Back. Submit = buttons row2 OK.
 */
class FlipperKeyboard {
    enum class Layer { LETTERS, SYMBOLS }

    /** (key, long?) — long OK = shift/uppercase; long Back = delete. */
    data class Press(val key: Int, val long: Boolean = false)

    // mirror of TextInputModel nav state. The firmware's view_enter_callback parks the
    // cursor on the Save button (in_buttons, row 2) every time the keyboard appears, so
    // that — not (0,0) — is the state a freshly opened keyboard starts in.
    var layer = Layer.LETTERS
    var row = 2
    var col = 0
    var inText = false
    var inButtons = true

    private val letters = arrayOf("qwertyuiop", "asdfghjkl:", "zxcvbnm ./")
    private val symbols = arrayOf("1234567890", ":/.-_=@#&", "!?%+*,;")

    private fun rows(l: Layer) = if (l == Layer.LETTERS) letters else symbols
    private fun cols(l: Layer, r: Int) = rows(l)[r].length

    /** Reset to the text_input scene default (Save button selected — see view_enter_callback). */
    fun resync() { layer = Layer.LETTERS; row = 2; col = 0; inText = false; inButtons = true }

    // --- transition functions, matching the firmware's InputTypeShort handlers exactly ---
    private fun clamp() { val c = cols(layer, row); if (col >= c) col = c - 1 }
    private fun up()   { if (inText) return; if (row > 0) { row--; if (!inButtons) clamp() } else inText = true }
    private fun down() { if (inText) { inText = false; return }; if (row < 2) { row++; if (!inButtons) clamp() } }
    private fun left() { if (inText) return; if (inButtons) { inButtons = false; col = cols(layer, row) - 1 } else if (col > 0) col-- else inButtons = true }
    private fun right(){ if (inText) return; if (inButtons) { inButtons = false; col = 0 } else if (col < cols(layer, row) - 1) col++ else inButtons = true }

    private data class Target(val layer: Layer, val row: Int, val col: Int, val shift: Boolean)

    /** Where does [ch] live? Prefers the current layer to avoid needless SYM/ABC toggles. */
    private fun locate(ch: Char): Target? {
        if (ch in 'A'..'Z') return find(ch.lowercaseChar(), shift = true)   // uppercase = shift of letter
        return find(ch, shift = false)
    }

    private fun find(ch: Char, shift: Boolean): Target? {
        val order = if (layer == Layer.LETTERS) listOf(Layer.LETTERS, Layer.SYMBOLS)
                    else listOf(Layer.SYMBOLS, Layer.LETTERS)
        for (l in order) {
            val grid = rows(l)
            for (r in 0..2) {
                val c = grid[r].indexOf(ch)
                if (c >= 0) return Target(l, r, c, shift)
            }
        }
        return null
    }

    /** Emit the key path to insert [ch], mutating the model as if executed. Empty = unsupported char. */
    fun type(ch: Char): List<Press> {
        val t = locate(ch) ?: return emptyList()
        val out = ArrayList<Press>()
        if (inText) { out += Press(1); down() }                 // 1 = DOWN, leave text field
        if (layer != t.layer) out += switchLayer()
        if (inButtons) { out += Press(2); right() }             // 2 = RIGHT, exit buttons to col0
        while (row < t.row) { out += Press(1); down() }
        while (row > t.row) { out += Press(0); up() }           // 0 = UP (row>0, never enters text)
        while (col < t.col) { out += Press(2); right() }
        while (col > t.col) { out += Press(3); left() }         // 3 = LEFT
        out += Press(Proto.KEY_OK, t.shift)                     // insert (long = uppercase)
        return out
    }

    /** Navigate to the SYM/ABC toggle button (row1) and press it, then step back onto the grid. */
    private fun switchLayer(): List<Press> {
        val out = ArrayList<Press>()
        if (!inButtons) { repeat(col + 1) { out += Press(3); left() } }   // Left*(col+1) enters buttons
        while (row < 1) { out += Press(1); down() }
        while (row > 1) { out += Press(0); up() }
        out += Press(Proto.KEY_OK)                                         // toggle layer
        layer = if (layer == Layer.LETTERS) Layer.SYMBOLS else Layer.LETTERS
        out += Press(2); right()                                           // exit buttons -> col0 row1
        return out
    }

    /** Key path to press the Save/submit button (buttons row2 OK). */
    fun submit(): List<Press> {
        val out = ArrayList<Press>()
        if (inText) { out += Press(1); down() }
        if (!inButtons) { repeat(col + 1) { out += Press(3); left() } }
        while (row < 2) { out += Press(1); down() }
        while (row > 2) { out += Press(0); up() }
        out += Press(Proto.KEY_OK)
        return out
    }
}
