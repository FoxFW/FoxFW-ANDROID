package com.foxfw.companion

/**
 * Reads the text currently shown in the Flipper's on-screen text_input field, straight from the live
 * screen-mirror framebuffer, by matching each rendered glyph against the device font.
 *
 * The font is FontSecondary = u8g2_font_haxrcorp4089_tr (see applications/services/gui/canvas.c). Every
 * Flipper renders it identically, so the exact glyph bitmaps are BAKED IN below ([DEFAULT_FONT],
 * decoded once from the firmware's u8g2 font data) — no per-user calibration, and matches are pixel-
 * exact because the template IS the bitmap the firmware blits.
 *
 * Geometry from text_input.c (draw_text_field): the field frame is y8..19, text is drawn with
 * FontSecondary at baseline y17 from x≈4; a leading "..." marks a left-scrolled view. We scan y9..19.
 */
object FlipperOcr {
    private const val TF_X0 = 3
    private const val TF_X1 = 125
    private const val TF_Y0 = 9
    private const val TF_Y1 = 19   // exclusive — stops BEFORE the field frame's bottom edge at y19,
                                   // which would otherwise be a full-width ink line that blobs the whole row

    /** Exact FontSecondary (haxrcorp4089) glyphs, "<code>:<w>,<h>,<row0>,<row1>,…;" per glyph.
     *  rows are bit c = pixel at column c (LSB = leftmost), top row first. Generated from the firmware. */
    private const val DEFAULT_FONT =
        "33:1,7,0,1,1,1,1,1,0,1;34:3,3,-4,5,5,5;35:5,5,-1,10,31,10,31,10;36:5,9,1,4,14,21,5,14,20,21,14,4;37:7,7,0,66,37,18,8,36,82,33;38:6,7,0,6,9,9,38,41,17,46;39:1,3,-4,1,1,1;40:3,9,1,4,2,1,1,1,1,1,2,4;41:3,9,1,1,2,4,4,4,4,4,2,1;42:5,5,-2,4,21,14,21,4;43:5,5,-1,4,4,31,4,4;44:2,3,1,2,2,1;45:5,1,-3,31;46:1,1,0,1;47:7,7,0,64,32,16,8,4,2,1;48:5,7,0,14,17,25,21,19,17,14;49:2,7,0,2,3,2,2,2,2,2;50:5,7,0,14,17,16,8,4,2,31;51:5,7,0,14,17,16,12,16,17,14;52:5,7,0,8,12,10,9,31,8,8;53:5,7,0,31,1,15,16,16,17,14;54:5,7,0,14,17,1,15,17,17,14;55:5,7,0,31,16,16,8,8,4,4;56:5,7,0,14,17,17,14,17,17,14;57:5,7,0,14,17,17,30,16,17,14;58:1,4,-1,1,0,0,1;59:2,6,1,2,0,0,2,2,1;60:3,5,-1,4,2,1,2,4;61:5,3,-2,31,0,31;62:3,5,-1,1,2,4,2,1;63:5,7,0,14,17,16,8,4,0,4;64:8,8,0,126,129,185,165,165,121,1,126;65:5,7,0,14,17,17,31,17,17,17;66:5,7,0,15,17,17,15,17,17,15;67:5,7,0,14,17,1,1,1,17,14;68:5,7,0,15,17,17,17,17,17,15;69:5,7,0,31,1,1,15,1,1,31;70:5,7,0,31,1,1,15,1,1,1;71:5,7,0,14,17,1,25,17,17,14;72:5,7,0,17,17,17,31,17,17,17;73:1,7,0,1,1,1,1,1,1,1;74:5,7,0,16,16,16,16,16,17,14;75:5,7,0,17,9,5,3,5,9,17;76:5,7,0,1,1,1,1,1,1,31;77:7,7,0,65,99,85,73,65,65,65;78:5,7,0,17,17,19,21,25,17,17;79:5,7,0,14,17,17,17,17,17,14;80:5,7,0,15,17,17,15,1,1,1;81:5,8,1,14,17,17,17,17,17,14,16;82:5,7,0,15,17,17,15,5,9,17;83:5,7,0,14,17,1,14,16,17,14;84:5,7,0,31,4,4,4,4,4,4;85:5,7,0,17,17,17,17,17,17,14;86:5,7,0,17,17,17,10,10,4,4;87:7,7,0,73,73,73,73,73,73,54;88:5,7,0,17,17,10,4,10,17,17;89:5,7,0,17,17,10,4,4,4,4;90:5,7,0,31,16,8,4,2,1,31;91:3,9,1,7,1,1,1,1,1,1,1,7;92:7,7,0,1,2,4,8,16,32,64;93:3,9,1,7,4,4,4,4,4,4,4,7;94:5,3,-4,4,10,17;95:5,1,0,31;96:3,3,-4,1,2,4;97:4,5,0,14,9,9,9,14;98:4,7,0,1,1,7,9,9,9,7;99:4,5,0,6,9,1,9,6;100:4,7,0,8,8,14,9,9,9,14;101:4,5,0,6,9,15,1,6;102:2,7,0,2,1,1,3,1,1,1;103:4,7,2,14,9,9,9,14,8,6;104:4,7,0,1,1,7,9,9,9,9;105:1,7,0,1,0,1,1,1,1,1;106:2,9,2,2,0,2,2,2,2,2,2,1;107:4,7,0,1,1,9,5,3,5,9;108:1,7,0,1,1,1,1,1,1,1;109:7,5,0,55,73,73,73,73;110:4,5,0,7,9,9,9,9;111:4,5,0,6,9,9,9,6;112:4,7,2,7,9,9,9,7,1,1;113:4,7,2,14,9,9,9,14,8,8;114:3,5,0,7,1,1,1,1;115:3,5,0,6,1,2,4,3;116:3,7,0,2,2,7,2,2,2,2;117:4,5,0,9,9,9,9,14;118:4,5,0,9,9,9,9,6;119:7,5,0,73,73,73,73,54;120:4,5,0,9,9,6,9,9;121:4,7,2,9,9,9,9,14,8,6;122:4,5,0,15,8,6,1,15;123:3,9,1,4,2,2,2,1,2,2,2,4;124:1,9,1,1,1,1,1,1,1,1,1,1;125:3,9,1,1,2,2,2,4,2,2,2,1;126:6,2,-3,38,25;"

    /** A tight glyph bitmap (cropped to its ink bounds). [bottomRel] = where the glyph's bottom row sits
     *  relative to the text baseline (0 = on it, negative = above, positive = below) — this is what tells
     *  `_` from `-` and `.` from `,`, which are identical bitmaps at different heights on the line. */
    class Glyph(val w: Int, val h: Int, val rows: IntArray, val bottomRel: Int = 0) {
        fun get(c: Int, r: Int) = if (r in 0 until h && c in 0 until w) (rows[r] ushr c) and 1 else 0
    }

    private const val BASELINE = 17   // FontSecondary baseline y in the field (canvas_draw_str y=17)
    private val templates = LinkedHashMap<Char, Glyph>()

    fun ensureLoaded() {
        if (templates.isNotEmpty()) return
        for (entry in DEFAULT_FONT.split(';')) {
            if (entry.isBlank()) continue
            val colon = entry.indexOf(':'); if (colon < 0) continue
            val ch = entry.substring(0, colon).toIntOrNull()?.toChar() ?: continue
            // Load ALL glyphs (incl. ( ) etc.): a filename can contain characters the keyboard can't
            // type, and we still want to read them faithfully. Typing is filtered separately.
            // Format: w, h, bottomRel, then h rows.
            val nums = entry.substring(colon + 1).split(',').mapNotNull { it.toIntOrNull() }
            if (nums.size < 3) continue
            val w = nums[0]; val h = nums[1]; val bottomRel = nums[2]
            if (nums.size < 3 + h) continue
            templates[ch] = Glyph(w, h, nums.subList(3, 3 + h).toIntArray(), bottomRel)
        }
    }

    val ready: Boolean get() = templates.isNotEmpty()

    private fun on(fb: ByteArray, x: Int, y: Int): Boolean {
        if (x < 0 || x >= 128 || y < 0 || y >= 64) return false
        val idx = (y shr 3) * 128 + x
        return idx in fb.indices && ((fb[idx].toInt() shr (y and 7)) and 1) != 0
    }

    /** Tight-crop a glyph from a column span [x0,x1) of the text-field band. Null if that span is blank. */
    private fun cropBand(fb: ByteArray, x0: Int, x1: Int): Glyph? {
        var minx = 128; var maxx = -1; var miny = 64; var maxy = -1
        for (y in TF_Y0 until TF_Y1) for (x in x0 until x1) if (on(fb, x, y)) {
            if (x < minx) minx = x; if (x > maxx) maxx = x; if (y < miny) miny = y; if (y > maxy) maxy = y
        }
        if (maxx < 0) return null
        val w = maxx - minx + 1; val h = maxy - miny + 1
        if (w > 24 || h > 16) return null
        val rows = IntArray(h)
        for (r in 0 until h) { var m = 0; for (c in 0 until w) if (on(fb, minx + c, miny + r)) m = m or (1 shl c); rows[r] = m }
        return Glyph(w, h, rows)
    }

    /** Split the field's glyph band into left-to-right glyphs (1px gaps separate them in this font). */
    fun segment(fb: ByteArray): List<Glyph> {
        val colInk = IntArray(128)
        for (x in TF_X0 until TF_X1) { var n = 0; for (y in TF_Y0 until TF_Y1) if (on(fb, x, y)) n++; colInk[x] = n }
        val out = ArrayList<Glyph>()
        var runStart = -1
        for (x in TF_X0 until TF_X1) {
            if (colInk[x] > 0) { if (runStart < 0) runStart = x }
            else if (runStart >= 0) { cropBand(fb, runStart, x)?.let { out.add(it) }; runStart = -1 }
        }
        if (runStart >= 0) cropBand(fb, runStart, TF_X1)?.let { out.add(it) }
        return out
    }

    /** Overlap score of two glyphs, allowing ±1px shift, normalised 0..1 (1 = identical). The compare
     *  window spans the UNION of both glyphs at the current shift — so a pixel of the wider glyph that the
     *  shift pushes past the narrower one's edge is still counted as a mismatch. Without this, a 1px bar
     *  ('l') scores a perfect 1.0 against '1' (its extra top-flag pixel slid out of the window) and the
     *  two become indistinguishable. */
    private fun score(a: Glyph, b: Glyph): Double {
        var best = 0.0
        for (dy in -1..1) for (dx in -1..1) {
            var match = 0; var total = 0
            val cLo = minOf(0, dx); val cHi = maxOf(a.w, b.w + dx)
            val rLo = minOf(0, dy); val rHi = maxOf(a.h, b.h + dy)
            for (r in rLo until rHi) for (c in cLo until cHi) {
                val av = a.get(c, r); val bv = b.get(c - dx, r - dy)
                if (av == 1 || bv == 1) { total++; if (av == bv) match++ }
            }
            val s = if (total == 0) 0.0 else match.toDouble() / total
            if (s > best) best = s
        }
        return best
    }

    /** Best-matching character for a glyph, or '\u0000' if nothing is close enough. The bitmap score
     *  must clear a floor; among those, a small vertical-position term ranks them, so `_`/`-` and `.`/`,`
     *  (identical bitmaps at different baseline heights) are told apart by where they sit on the line. */
    fun match(g: Glyph): Char {
        var bestCh = '\u0000'; var bestMetric = -1.0
        for ((ch, t) in templates) {
            if (kotlin.math.abs(t.w - g.w) > 2 || kotlin.math.abs(t.h - g.h) > 2) continue
            val s = score(g, t)
            if (s < 0.62) continue                                   // bitmap must be a decent match first
            // The ±bottomRel term tells apart same-bitmap glyphs at different heights ('_'/'-', '.'/',').
            // The tiny lowercase bias breaks EXACT bitmap ties — 'l' and 'I' are the identical 1px bar in
            // this font — toward the far-more-common lowercase letter, without overriding any real score gap.
            val metric = s - 0.08 * kotlin.math.abs(g.bottomRel - t.bottomRel) +
                (if (ch in 'a'..'z') 1e-4 else 0.0)
            if (metric > bestMetric) { bestMetric = metric; bestCh = ch }
        }
        return bestCh
    }

    /** True if the field view is scrolled left (firmware draws a leading "..."). */
    fun looksScrolled(fb: ByteArray): Boolean {
        var dots = 0
        for (x in TF_X0 until TF_X0 + 10) if (on(fb, x, TF_Y1 - 2) || on(fb, x, TF_Y1 - 3)) dots++
        var above = 0
        for (x in TF_X0 until TF_X0 + 10) for (y in TF_Y0 until TF_Y1 - 4) if (on(fb, x, y)) above++
        return dots >= 3 && above == 0
    }

    /** Read the visible field text. Handles the pre-selected default name (drawn as a FILLED box with
     *  inverted white-on-black text — we detect that span and read the holes), reconstructs spaces from
     *  the ~2px blank the space advance leaves, and skips the 1×9 cursor bar and unmatched specks. */
    fun read(fb: ByteArray): String {
        ensureLoaded()
        // Detect the pre-selected default box: it's a FILLED rect, so the rows just ABOVE and BELOW the
        // text (y9, y10, y18 — where the glyphs don't reach) are long solid runs. The text itself carves
        // holes through the middle rows, so a column-fill test misses it. A plain field leaves these
        // rows empty, so a long run here means inverted/selected text; its x-extent is the box span.
        var invS = -1; var invE = -1; var invLen = 0
        for (yy in intArrayOf(TF_Y0, TF_Y0 + 1, TF_Y1 - 1)) {
            var rs = -1
            for (x in TF_X0 + 1 until TF_X1 - 1) {
                if (on(fb, x, yy)) { if (rs < 0) rs = x }
                else { if (rs >= 0) { val l = x - rs; if (l > invLen) { invLen = l; invS = rs; invE = x - 1 } }; rs = -1 }
            }
            if (rs >= 0) { val l = (TF_X1 - 1) - rs; if (l > invLen) { invLen = l; invS = rs; invE = TF_X1 - 2 } }
        }
        val inv = invLen >= 15   // a wide solid run above/below the text => inverted/selected box
        fun ink(x: Int, y: Int): Boolean {
            if (inv && x in invS..invE) {
                if (x == invS || x == invE) {
                    // The filled box's rounded corners/edge invert to stray ink on the border column.
                    // Drop it when it's a REMNANT: always on the corner rows (top/bottom of the band), and
                    // on any middle row where the pixel is ISOLATED — its inner neighbour is box-fill, so the
                    // ink can't belong to a glyph. A real glyph stem on the border connects inward (inner
                    // neighbour is a hole), so it's kept. Without this the last glyph in a snug box fuses with
                    // the edge column and misreads ('b'→'N', 'm'→'n').
                    if (y <= TF_Y0 || y >= TF_Y1 - 1) return false
                    val inner = if (x == invS) x + 1 else x - 1
                    if (on(fb, inner, y)) return false
                }
                return !on(fb, x, y)
            }
            return on(fb, x, y)
        }
        fun crop(a: Int, b: Int): Glyph? {
            var minx = 128; var maxx = -1; var miny = 64; var maxy = -1
            for (y in TF_Y0 until TF_Y1) for (x in a until b) if (ink(x, y)) {
                if (x < minx) minx = x; if (x > maxx) maxx = x; if (y < miny) miny = y; if (y > maxy) maxy = y
            }
            if (maxx < 0) return null
            val w = maxx - minx + 1; val h = maxy - miny + 1
            if (w > 24 || h > 16) return null
            val rows = IntArray(h)
            for (r in 0 until h) { var m = 0; for (c in 0 until w) if (ink(minx + c, miny + r)) m = m or (1 shl c); rows[r] = m }
            return Glyph(w, h, rows, maxy - BASELINE)   // where this glyph's bottom sits vs the baseline
        }
        val sb = StringBuilder()
        var runStart = -1; var prevEnd = -1
        fun emit(a: Int, b: Int) {
            if (prevEnd >= 0 && a - prevEnd >= 3) sb.append(' ')      // wide gap => a space sat here
            val g = crop(a, b)
            // Skip a 1px-wide bar ≥8 tall — the cursor "|" and the frame/box edge verticals. No keyboard
            // glyph is that tall-and-thin (l, I, i, !, : are all ≤7), so this never drops a real char.
            if (g != null && !(g.w == 1 && g.h >= 8)) {
                val ch = match(g)
                if (ch != '\u0000') sb.append(ch)
            }
            prevEnd = b - 1
        }
        for (x in TF_X0 until TF_X1) {
            var col = 0; for (y in TF_Y0 until TF_Y1) if (ink(x, y)) col++
            if (col > 0) { if (runStart < 0) runStart = x }
            else if (runStart >= 0) { emit(runStart, x); runStart = -1 }
        }
        if (runStart >= 0) emit(runStart, TF_X1)
        val out = sb.toString().trimEnd()
        // A leading run of dots/bars is the firmware's left-scroll marker — always "..." — which our OCR
        // may read as ".." or "..|" (edge artifact). Normalise it so the box shows a clean "...".
        val lead = out.takeWhile { it == '.' || it == '|' || it == ',' }
        return if (lead.length >= 2) "..." + out.substring(lead.length) else out
    }
}
