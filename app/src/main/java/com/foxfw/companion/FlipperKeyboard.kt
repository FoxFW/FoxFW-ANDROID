package com.foxfw.companion

/**
 * Faithful model of FoxFW's on-screen text_input keyboard, used to drive it blind over RPC.
 * The firmware exposes no "insert text" command — only the 6 hardware keys — so to type we
 * replicate the keyboard's cursor state machine and emit the exact Up/Down/Left/Right/OK path.
 *
 * Layout (applications/services/gui/modules/text_input.c):
 *   Letters: row0 "qwertyuiop"  row1 "asdfghjkl:"  row2 "zxcvbnm ./"      (all 10 cols)
 *   Symbols: row0 "1234567890"  row1 ":/.-_=@#&"   row2 "!?%+*,;"         (10 / 9 / 7 cols)
 *   Right-side button column: row0 = DEL (backspace), row1 = SYM/ABC layer toggle, row2 = OK (save).
 * Nav wraps horizontally (grid<->buttons, no wall); vertically row0..2 with Up at row0 -> text field.
 * Uppercase = long-press OK (shift). Backspace = long-press Back. Submit = buttons row2 OK.
 *
 * Because the model is blind, any key press it didn't issue (the user's own D-pad, a manual DEL, a
 * layer toggle, the firmware auto-jumping to OK when the buffer fills) silently desyncs it. To stay
 * honest we read the live screen-mirror framebuffer before each character ([readState]) and snap the
 * model to the TRUE cursor position — the selected key is drawn as a filled box, everything else as
 * an outline, so it's directly recoverable.
 */
class FlipperKeyboard {
    enum class Layer { LETTERS, SYMBOLS }

    /** (key, long?) — long OK = shift/uppercase; long Back = delete. key ints are Flipper InputKey
     *  codes: UP=0, DOWN=1, RIGHT=2, LEFT=3, OK=Proto.KEY_OK. */
    data class Press(val key: Int, val long: Boolean = false)

    /** A recovered (or modelled) cursor position on the keyboard. */
    data class Snapshot(
        val inText: Boolean,
        val inButtons: Boolean,
        val row: Int,
        val col: Int,
        val layer: Layer,
    )

    // mirror of TextInputModel nav state. The firmware's view_enter_callback parks the
    // cursor on the Save button (in_buttons, row 2) every time the keyboard appears, so
    // that — not (0,0) — is the state a freshly opened keyboard starts in.
    var layer = Layer.LETTERS
    var row = 2
    var col = 0
    var inText = false
    var inButtons = true

    private val letters = arrayOf("qwertyuiop", "asdfghjkl:", "zxcvbnm ./")
    private val symbols = arrayOf("1234567890", ":/.-_=@#&", "!?%+*,;()")

    private fun rows(l: Layer) = if (l == Layer.LETTERS) letters else symbols
    private fun cols(l: Layer, r: Int) = rows(l)[r].length

    /** Reset to the text_input scene default (Save button selected — see view_enter_callback). */
    fun resync() { layer = Layer.LETTERS; row = 2; col = 0; inText = false; inButtons = true }

    /** Snap the model to a position recovered from the screen (ground truth beats the blind model). */
    fun setState(s: Snapshot) {
        layer = s.layer; inText = s.inText; inButtons = s.inButtons; row = s.row; col = s.col
        if (!inButtons && !inText) clamp()
    }

    fun snapshot(): Snapshot = Snapshot(inText, inButtons, row, col, layer)

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

    /** Where [ch] sits (row/col/layer + whether it needs shift) — public view of [locate] for the
     *  verify-before-commit driver. */
    data class Nav(val layer: Layer, val row: Int, val col: Int, val shift: Boolean)
    fun navFor(ch: Char): Nav? = locate(ch)?.let { Nav(it.layer, it.row, it.col, it.shift) }

    /** Movement path to sit ON the key for [ch] WITHOUT the committing OK. Mutates the model to the
     *  target key, so the caller can verify from the live screen before deciding to press OK. */
    fun pathTo(ch: Char): List<Press> {
        val t = locate(ch) ?: return emptyList()
        val out = ArrayList<Press>()
        if (inText) { out += Press(1); down() }
        if (layer != t.layer) out += switchLayer()
        if (inButtons) { out += Press(2); right() }
        while (row < t.row) { out += Press(1); down() }
        while (row > t.row) { out += Press(0); up() }
        while (col < t.col) { out += Press(2); right() }
        while (col > t.col) { out += Press(3); left() }
        return out
    }

    /** Movement path to sit ON the Save button (buttons row2) WITHOUT pressing it. */
    fun pathToSubmit(): List<Press> {
        val out = ArrayList<Press>()
        if (inText) { out += Press(1); down() }
        if (!inButtons) { repeat(col + 1) { out += Press(3); left() } }
        while (row < 2) { out += Press(1); down() }
        while (row > 2) { out += Press(0); up() }
        return out
    }

    companion object {
        /** Every character the Fox on-screen keyboard can produce — the 3 letter rows, the 3 symbol
         *  rows, shifted uppercase, and space. Used to stop the phone box from accepting a character the
         *  Flipper keyboard simply has no key for. */
        val SUPPORTED: Set<Char> = buildSet {
            for (s in listOf("qwertyuiop", "asdfghjkl:", "zxcvbnm ./", "1234567890", ":/.-_=@#&", "!?%+*,;()")) s.forEach { add(it) }
            for (c in 'a'..'z') add(c.uppercaseChar())
        }
        fun supports(ch: Char): Boolean = ch in SUPPORTED

        // Exact draw geometry from text_input.c (draw_grid / draw_buttons), so we can read the
        // selected box straight out of the 128x64 1bpp screen-mirror framebuffer.
        private val ROW_TOP = intArrayOf(20, 34, 48)
        private val ROW_H = intArrayOf(13, 13, 14)
        private val LET_COLS = intArrayOf(10, 10, 10)
        private val LET_SX = intArrayOf(1, 1, 1)
        private val SYM_COLS = intArrayOf(10, 9, 9)
        private val SYM_SX = intArrayOf(1, 6, 6)
        private const val GRID_W = 9
        private const val BTN_LEFT = 103
        private const val BTN_W = 24

        /** Framebuffer is the u8g2 page buffer: byte = buf[x + (y/8)*128], bit = (y & 7). */
        private fun on(fb: ByteArray, x: Int, y: Int): Boolean {
            if (x < 0 || x >= 128 || y < 0 || y >= 64) return false
            val idx = (y shr 3) * 128 + x
            if (idx < 0 || idx >= fb.size) return false
            return ((fb[idx].toInt() shr (y and 7)) and 1) != 0
        }

        /** Count lit pixels in a rectangle. */
        private fun ink(fb: ByteArray, x0: Int, x1: Int, y0: Int, y1: Int): Int {
            var n = 0
            for (y in y0 until y1) for (x in x0 until x1) if (on(fb, x, y)) n++
            return n
        }

        /** Fraction of a box's INTERIOR (inset 2px, so the outline frame doesn't count) that's lit.
         *  A selected key/button is a filled box (~1.0 minus its inverted glyph); an unselected one is
         *  just an outline, so its interior holds only the small black glyph (~0.2–0.35). */
        private fun interiorFill(fb: ByteArray, x: Int, w: Int, top: Int, h: Int): Double {
            var on = 0; var tot = 0
            for (yy in top + 2 until top + h - 2) for (xx in x + 2 until x + w - 2) {
                tot++; if (on(fb, xx, yy)) on++
            }
            return if (tot == 0) 0.0 else on.toDouble() / tot
        }

        /**
         * Recover the keyboard cursor state from a screen-mirror frame, or null if this frame doesn't
         * look like the text_input keyboard (so the caller falls back to the blind model). The selected
         * element is the single filled box; absence of any fill means the cursor is in the text field.
         */
        fun readState(fb: ByteArray): Snapshot? {
            if (fb.size < 1024) return null
            // Sanity: the DEL button (always drawn, top-right) must be present, else no keyboard here.
            if (ink(fb, BTN_LEFT, BTN_LEFT + BTN_W, ROW_TOP[0], ROW_TOP[0] + ROW_H[0]) < 10) return null

            // Layer: letters row2 has a key at the far left (x≈1, 'z'); symbols row2 starts at x=16, so
            // that corner is blank. A frame or fill there => letters.
            val layer = if (ink(fb, 1, 10, ROW_TOP[2], ROW_TOP[2] + ROW_H[2]) > 12) Layer.LETTERS else Layer.SYMBOLS
            val sxArr = if (layer == Layer.LETTERS) LET_SX else SYM_SX
            val colsArr = if (layer == Layer.LETTERS) LET_COLS else SYM_COLS

            for (r in 0..2) {
                val top = ROW_TOP[r]; val h = ROW_H[r]
                // A selected grid key?
                for (c in 0 until colsArr[r]) {
                    val x = sxArr[r] + c * 10
                    if (interiorFill(fb, x, GRID_W, top, h) > 0.55) return Snapshot(false, false, r, c, layer)
                }
                // The selected button on this row?
                if (interiorFill(fb, BTN_LEFT, BTN_W, top, h) > 0.45) return Snapshot(false, true, r, 0, layer)
            }
            // Nothing filled -> cursor is in the text field.
            return Snapshot(true, false, 0, 0, layer)
        }

        /** True only if the frame is REALLY the text_input keyboard — not a menu, an app, or the boot
         *  logo. Beyond readState()'s button check, it verifies the regular 1px gaps between the row-0
         *  key boxes (pitch 10, 9-wide boxes): a full-screen image like the fox logo fills those gaps in,
         *  so it fails here even though it happened to have ink in the button corner. */
        fun isTextInput(fb: ByteArray): Boolean {
            if (readState(fb) == null) return false
            var emptyGaps = 0
            for (k in 1..9) {
                val gx = k * 10                         // the blank column between two key boxes
                var inkCols = 0
                for (y in ROW_TOP[0] + 2 until ROW_TOP[0] + ROW_H[0] - 1) if (on(fb, gx, y)) inkCols++
                if (inkCols <= 1) emptyGaps++
            }
            return emptyGaps >= 7
        }
    }
}
