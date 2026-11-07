package com.xlollx.songport.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.text.TextPaint
import com.xlollx.songport.sync.CoverArt
import java.util.Random
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Playlist covers drawn on the phone with android.graphics: no AI, no library, nothing sent
 * anywhere. Five styles, square, from the playlist's title, its top artists and (when there are
 * any) its covers, whose colours make the palette; without covers the palette comes from the
 * title. Same playlist, same style, same [variant]: the same picture; another variant changes the
 * seed ("another cover"). The title is fitted to its box ([CoverArt.fitTitle]): as big as it can
 * be, up to three lines, never cut off by the edge, on a ground it reads on.
 */
object GeneratedCovers {
    enum class Style { MOSAIC, GRADIENT, GEOMETRIC, TYPOGRAPHIC, VINYL }

    const val SIDE = 1080

    /** What a cover is drawn from: the title, the artists that appear most (first = most) and some covers, best first. */
    class Input(val title: String, val artists: List<String>, val covers: List<Bitmap>)

    fun draw(style: Style, input: Input, variant: Int = 0, side: Int = SIDE): Bitmap {
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val pal = palette(input, variant)
        val rnd = Random(CoverArt.seed(input.title, variant) xor (style.ordinal.toLong() * 7919L))
        val s = side / 1080f
        when (style) {
            Style.MOSAIC -> mosaic(c, side, s, input, pal, rnd, variant)
            Style.GRADIENT -> gradient(c, side, s, input, pal, rnd)
            Style.GEOMETRIC -> geometric(c, side, s, input, pal, rnd)
            Style.TYPOGRAPHIC -> typographic(c, side, s, input, pal, rnd)
            Style.VINYL -> vinyl(c, side, s, input, pal, rnd)
        }
        return bmp
    }

    /** The five colours of [input]'s cover: from its covers' pixels, else from its title. */
    fun palette(input: Input, variant: Int = 0): IntArray {
        val px = input.covers.take(6).map { sample(it) }
        val all = IntArray(px.sumOf { it.size }).also { var o = 0; for (a in px) { a.copyInto(it, o); o += a.size } }
        return CoverArt.palette(CoverArt.dominant(all, 5), input.title, variant)
    }

    private fun sample(b: Bitmap): IntArray = runCatching {
        val small = Bitmap.createScaledBitmap(b, 24, 24, true)
        IntArray(24 * 24).also { small.getPixels(it, 0, 24, 0, 0, 24, 24) }
    }.getOrDefault(IntArray(0))

    // ------------------------------------------------------------------ text

    private fun textPaint(color: Int, bold: Boolean = true, condensed: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        typeface = if (condensed) Typeface.create("sans-serif-condensed", Typeface.BOLD) else Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private const val LINE = 1.16f

    /**
     * The title fitted into [box] and drawn there, against its top or (when [fromBottom]) its
     * bottom. White text gets a soft shadow so it holds on any part of a picture. Returns the
     * vertical span used (top, bottom).
     */
    private fun title(
        c: Canvas, text: String, box: RectF, maxLines: Int, maxSize: Float, minSize: Float, color: Int,
        align: Paint.Align = Paint.Align.LEFT, fromBottom: Boolean = false, condensed: Boolean = false, shadow: Boolean = true,
    ): Pair<Float, Float> {
        val p = textPaint(color, condensed = condensed)
        val fit = CoverArt.fitTitle(text, box.width(), box.height(), maxLines, maxSize, minSize, LINE) { str, size -> p.textSize = size; p.measureText(str) }
        p.textSize = fit.size
        p.textAlign = align
        if (shadow && color == CoverArt.WHITE) p.setShadowLayer(fit.size * 0.10f, 0f, fit.size * 0.03f, 0x73000000)
        val lh = fit.size * LINE
        val total = fit.lines.size * lh
        val top = if (fromBottom) box.bottom - total else box.top
        val x = when (align) { Paint.Align.LEFT -> box.left; Paint.Align.CENTER -> box.centerX(); else -> box.right }
        fit.lines.forEachIndexed { i, l -> c.drawText(l, x, top + i * lh - p.ascent() * 0.98f, p) }
        return top to top + total
    }

    /** One line, cut with "…" to [width]; [baseline] is the text's baseline. */
    private fun oneLine(c: Canvas, text: String, x: Float, baseline: Float, width: Float, p: TextPaint) {
        if (text.isBlank()) return
        val lines = CoverArt.fitTitle(text, width, p.textSize * LINE, 1, p.textSize, p.textSize, LINE) { str, size -> p.textSize = size; p.measureText(str) }.lines
        c.drawText(lines.first(), x, baseline, p)
    }

    private fun artistsLine(input: Input, n: Int = 3) = input.artists.take(n).joinToString("  ·  ")

    // ------------------------------------------------------------------ styles

    /** Covers in a grid (3×3, 2×2, or fewer larger tiles), a veil of the palette's colour, the title big at the bottom. */
    private fun mosaic(c: Canvas, side: Int, s: Float, input: Input, pal: IntArray, rnd: Random, variant: Int) {
        val w = side.toFloat()
        c.drawColor(pal[4])
        val gap = 6f * s
        val covers = input.covers.toMutableList()
        if (variant > 0) covers.shuffle(rnd)
        val cells: List<RectF> = when {
            covers.size >= 9 -> grid(w, 3, gap)
            covers.size >= 4 -> grid(w, 2, gap)
            covers.size == 3 -> listOf(RectF(0f, 0f, w / 2 - gap / 2, w), RectF(w / 2 + gap / 2, 0f, w, w / 2 - gap / 2), RectF(w / 2 + gap / 2, w / 2 + gap / 2, w, w))
            covers.size == 2 -> listOf(RectF(0f, 0f, w / 2 - gap / 2, w), RectF(w / 2 + gap / 2, 0f, w, w))
            covers.size == 1 -> listOf(RectF(0f, 0f, w, w))
            else -> grid(w, 3, gap)
        }
        cells.forEachIndexed { i, r ->
            val b = covers.getOrNull(i)
            if (b != null) fill(c, b, r)
            else c.drawRect(r, Paint().apply { color = CoverArt.mix(pal[1 + Math.floorMod(i + rnd.nextInt(3), 3)], pal[4], 0.15f * (i % 3)) })
        }
        // The veil: a wash of the palette's colour all over, deepening to the bottom where the title sits.
        c.drawRect(0f, 0f, w, w, Paint().apply { color = pal[0]; alpha = 80 })
        c.drawRect(0f, 0f, w, w, Paint().apply {
            shader = LinearGradient(0f, w * 0.30f, 0f, w, intArrayOf(withAlpha(pal[0], 0), withAlpha(pal[0], 170), withAlpha(pal[4], 245)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
        val m = 72f * s
        val (top, _) = title(c, input.title, RectF(m, w * 0.50f, w - m, w - m), 3, 190f * s, 52f * s, CoverArt.WHITE, fromBottom = true)
        val small = textPaint(CoverArt.WHITE, bold = true).apply { textSize = 34f * s; alpha = 225; letterSpacing = 0.06f }
        oneLine(c, artistsLine(input).uppercase(), m, top - 22f * s, w - 2 * m, small)
    }

    private fun grid(w: Float, n: Int, gap: Float): List<RectF> {
        val cell = (w - gap * (n - 1)) / n
        return (0 until n * n).map { i -> val x = (i % n) * (cell + gap); val y = (i / n) * (cell + gap); RectF(x, y, x + cell, y + cell) }
    }

    /** [b] scaled to cover [r], centred, cut at its edges. */
    private fun fill(c: Canvas, b: Bitmap, r: RectF) {
        val scale = max(r.width() / b.width, r.height() / b.height)
        val dw = b.width * scale; val dh = b.height * scale
        c.save(); c.clipRect(r)
        c.drawBitmap(b, null, RectF(r.centerX() - dw / 2, r.centerY() - dh / 2, r.centerX() + dw / 2, r.centerY() + dh / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
    }

    private fun withAlpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    /** Soft blobs of the palette's colours melting into each other, a light grain, the title in the middle. */
    private fun gradient(c: Canvas, side: Int, s: Float, input: Input, pal: IntArray, rnd: Random) {
        val w = side.toFloat()
        c.drawColor(pal[0])
        val colours = intArrayOf(pal[1], pal[3], pal[2], pal[1], pal[4])
        for (i in colours.indices) {
            val cx = w * (-0.15f + rnd.nextFloat() * 1.3f)
            val cy = w * (-0.15f + rnd.nextFloat() * 1.3f)
            val r = w * (0.55f + rnd.nextFloat() * 0.45f)
            c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx, cy, r, intArrayOf(withAlpha(colours[i], 235), withAlpha(colours[i], 110), withAlpha(colours[i], 0)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            })
        }
        grain(c, w, rnd)
        // A soft shade behind the words, so white reads on the brightest blob too.
        c.drawRect(0f, 0f, w, w, Paint().apply {
            shader = RadialGradient(w / 2, w / 2, w * 0.62f, intArrayOf(0x66000000, 0x33000000, 0x00000000), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        })
        val m = 90f * s
        val p = textPaint(CoverArt.WHITE)
        val fit = CoverArt.fitTitle(input.title, w - 2 * m, w * 0.52f, 3, 170f * s, 54f * s, LINE) { str, size -> p.textSize = size; p.measureText(str) }
        val h = fit.lines.size * fit.size * LINE
        val top = (w - h) / 2 - 20f * s
        title(c, input.title, RectF(m, top, w - m, top + h + 1f), 3, fit.size, fit.size, CoverArt.WHITE, Paint.Align.CENTER)
        val small = textPaint(CoverArt.WHITE, bold = false).apply { textSize = 34f * s; alpha = 230; textAlign = Paint.Align.CENTER; setShadowLayer(6f * s, 0f, 2f * s, 0x66000000) }
        oneLine(c, artistsLine(input), w / 2, top + h + 64f * s, w - 2 * m, small)
    }

    private fun grain(c: Canvas, w: Float, rnd: Random) {
        val light = Paint().apply { color = 0x14FFFFFF }
        val dark = Paint().apply { color = 0x14000000 }
        val dot = max(1f, w / 900f)
        repeat(14_000) {
            val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * w
            c.drawRect(x, y, x + dot, y + dot, if (it % 2 == 0) light else dark)
        }
    }

    /** A 4×4 grid of shapes picked by the title's seed (quarter circles, triangles, discs, half discs, waves), the title on a band at the bottom. */
    private fun geometric(c: Canvas, side: Int, s: Float, input: Input, pal: IntArray, rnd: Random) {
        val w = side.toFloat()
        c.drawColor(pal[0])
        val n = 4
        val cell = w / n
        val inks = intArrayOf(pal[1], pal[2], pal[3], CoverArt.mix(pal[3], CoverArt.WHITE, 0.55f), pal[4])
        fun ink(not: Int? = null): Int { var k: Int; do { k = inks[rnd.nextInt(inks.size)] } while (k == not && inks.size > 1); return k }
        for (i in 0 until n * n) {
            val x = (i % n) * cell; val y = (i / n) * cell
            val r = RectF(x, y, x + cell, y + cell)
            val ground = if (rnd.nextInt(3) == 0) ink() else pal[0]
            c.drawRect(r, Paint().apply { color = ground })
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink(ground) }
            c.save(); c.clipRect(r)
            when (rnd.nextInt(6)) {
                0 -> { // quarter circle from a corner
                    val cx = if (rnd.nextBoolean()) r.left else r.right; val cy = if (rnd.nextBoolean()) r.top else r.bottom
                    c.drawCircle(cx, cy, cell, p)
                }
                1 -> { // triangle on a diagonal
                    val path = Path()
                    when (rnd.nextInt(4)) {
                        0 -> { path.moveTo(r.left, r.top); path.lineTo(r.right, r.top); path.lineTo(r.left, r.bottom) }
                        1 -> { path.moveTo(r.right, r.top); path.lineTo(r.right, r.bottom); path.lineTo(r.left, r.top) }
                        2 -> { path.moveTo(r.left, r.bottom); path.lineTo(r.right, r.bottom); path.lineTo(r.right, r.top) }
                        else -> { path.moveTo(r.left, r.top); path.lineTo(r.left, r.bottom); path.lineTo(r.right, r.bottom) }
                    }
                    path.close(); c.drawPath(path, p)
                }
                2 -> c.drawCircle(r.centerX(), r.centerY(), cell * 0.38f, p)
                3 -> { // half disc on an edge
                    when (rnd.nextInt(4)) {
                        0 -> c.drawCircle(r.centerX(), r.top, cell / 2, p)
                        1 -> c.drawCircle(r.centerX(), r.bottom, cell / 2, p)
                        2 -> c.drawCircle(r.left, r.centerY(), cell / 2, p)
                        else -> c.drawCircle(r.right, r.centerY(), cell / 2, p)
                    }
                }
                4 -> { // waves
                    p.style = Paint.Style.STROKE; p.strokeWidth = cell * 0.07f; p.strokeCap = Paint.Cap.ROUND
                    for (k in 0 until 4) {
                        val path = Path()
                        val base = r.top + cell * (0.2f + k * 0.2f)
                        path.moveTo(r.left, base)
                        var t = 0f
                        while (t <= 1.001f) { path.lineTo(r.left + t * cell, base + sin(t * 2 * PI).toFloat() * cell * 0.06f); t += 0.05f }
                        c.drawPath(path, p)
                    }
                }
                else -> { // rings
                    p.style = Paint.Style.STROKE; p.strokeWidth = cell * 0.06f
                    c.drawCircle(r.centerX(), r.centerY(), cell * 0.36f, p)
                    c.drawCircle(r.centerX(), r.centerY(), cell * 0.18f, p)
                }
            }
            c.restore()
        }
        // The band: as tall as the title needs, at most a third of the cover.
        val m = 64f * s
        val p = textPaint(CoverArt.WHITE)
        val fit = CoverArt.fitTitle(input.title, w - 2 * m, w * 0.30f, 3, 130f * s, 50f * s, LINE) { str, size -> p.textSize = size; p.measureText(str) }
        val small = textPaint(CoverArt.WHITE, bold = false).apply { textSize = 32f * s; alpha = 220 }
        val artists = artistsLine(input)
        val titleH = fit.lines.size * fit.size * LINE
        val bandH = titleH + 2 * m * 0.75f + (if (artists.isNotEmpty()) small.fontSpacing + 6f * s else 0f)
        c.drawRect(0f, w - bandH, w, w, Paint().apply { color = pal[4]; alpha = 238 })
        c.drawRect(0f, w - bandH, w, w - bandH + 8f * s, Paint().apply { color = pal[3] })
        val top = w - bandH + m * 0.75f
        title(c, input.title, RectF(m, top, w - m, top + titleH + 1f), 3, fit.size, fit.size, CoverArt.WHITE, shadow = false)
        oneLine(c, artists, m, top + titleH + 6f * s - small.ascent(), w - 2 * m, small)
    }

    /** A gig poster: one flat colour, the title huge in capitals, a rule, the top artists in a column. */
    private fun typographic(c: Canvas, side: Int, s: Float, input: Input, pal: IntArray, rnd: Random) {
        val w = side.toFloat()
        val cream = 0xFFF2EDE4.toInt()
        val bg = when (rnd.nextInt(3)) { 0 -> pal[3]; 1 -> pal[0]; else -> cream }
        c.drawColor(bg)
        grain(c, w, rnd)
        val ink = CoverArt.textOn(bg)
        // The accent: a palette colour that stands out on the ground, else the ink itself.
        val accent = listOf(pal[3], pal[1], pal[2], pal[0]).firstOrNull { it != bg && CoverArt.contrast(it, bg) >= 3.0 } ?: ink
        val m = 72f * s
        val center = rnd.nextInt(3) == 0
        val align = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        c.drawRect(if (center) w / 2 - 70f * s else m, m, if (center) w / 2 + 70f * s else m + 140f * s, m + 16f * s, Paint().apply { color = accent })
        val (_, bottom) = title(c, input.title.uppercase(), RectF(m, m + 56f * s, w - m, w * 0.68f), 3, 320f * s, 60f * s, ink, align, condensed = true, shadow = false)
        var y = bottom + 30f * s
        c.drawRect(m, y, w - m, y + 8f * s, Paint().apply { color = accent })
        y += 8f * s + 26f * s
        val x = if (center) w / 2 else m
        input.artists.take(5).forEachIndexed { i, a ->
            val p = textPaint(if (i == 0) accent else ink, condensed = true).apply { textSize = (if (i == 0) 66f else 46f) * s; textAlign = align; if (i > 0) alpha = 225 }
            if (y - p.ascent() + p.descent() > w - m) return@forEachIndexed
            oneLine(c, a.uppercase(), x, y - p.ascent(), w - 2 * m, p)
            y += p.fontSpacing * 1.02f
        }
    }

    /** A record with grooves and a coloured label (the title small on it), on the palette's colours, the title big below. */
    private fun vinyl(c: Canvas, side: Int, s: Float, input: Input, pal: IntArray, rnd: Random) {
        val w = side.toFloat()
        c.drawRect(0f, 0f, w, w, Paint().apply { shader = LinearGradient(0f, 0f, w, w, intArrayOf(pal[0], pal[4]), null, Shader.TileMode.CLAMP) })
        c.drawCircle(w * 0.2f, w * 0.15f, w * 0.7f, Paint().apply { shader = RadialGradient(w * 0.2f, w * 0.15f, w * 0.7f, intArrayOf(withAlpha(pal[1], 150), withAlpha(pal[1], 0)), null, Shader.TileMode.CLAMP) })
        val r = w * 0.40f
        val cx = w * 0.5f
        val cy = w * 0.40f
        // The disc, its shadow, its grooves and two sheens.
        c.drawCircle(cx, cy + 10f * s, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 })
        c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111114.toInt() })
        val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * s }
        var rr = r * 0.40f; var k = 0
        while (rr < r * 0.97f) { groove.color = if (k % 3 == 0) 0x24FFFFFF else 0x10FFFFFF; c.drawCircle(cx, cy, rr, groove); rr += 6.5f * s; k++ }
        val angle = rnd.nextInt(90).toFloat()
        c.save(); c.rotate(angle, cx, cy)
        c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = SweepGradient(cx, cy, intArrayOf(0x00FFFFFF, 0x33FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x33FFFFFF, 0x00FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.08f, 0.17f, 0.5f, 0.58f, 0.67f, 1f))
        })
        c.restore()
        val lr = r * 0.34f
        val label = pal[3]
        c.drawCircle(cx, cy, lr, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = label })
        c.drawCircle(cx, cy, lr * 0.86f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CoverArt.mix(label, 0xFF000000.toInt(), 0.18f); style = Paint.Style.STROKE; strokeWidth = 2f * s })
        val onLabel = CoverArt.textOn(label)
        val lp = textPaint(onLabel).apply { textSize = lr * 0.17f; textAlign = Paint.Align.CENTER }
        oneLine(c, input.title, cx, cy - lr * 0.32f, lr * 1.3f, lp)
        val lp2 = textPaint(onLabel, bold = false).apply { textSize = lr * 0.12f; textAlign = Paint.Align.CENTER; alpha = 210 }
        oneLine(c, input.artists.firstOrNull().orEmpty(), cx, cy + lr * 0.48f, lr * 1.2f, lp2)
        c.drawCircle(cx, cy, 9f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal[4] })
        // The words over a shade at the bottom.
        c.drawRect(0f, w * 0.55f, w, w, Paint().apply {
            shader = LinearGradient(0f, w * 0.55f, 0f, w, intArrayOf(withAlpha(pal[4], 0), withAlpha(pal[4], 215), withAlpha(pal[4], 250)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
        val m = 72f * s
        val (top, _) = title(c, input.title, RectF(m, w * 0.68f, w - m, w - m), 3, 130f * s, 48f * s, CoverArt.WHITE, fromBottom = true)
        val small = textPaint(CoverArt.WHITE, bold = true).apply { textSize = 32f * s; alpha = 220; letterSpacing = 0.06f }
        oneLine(c, artistsLine(input).uppercase(), m, top - 20f * s, w - 2 * m, small)
    }
}
