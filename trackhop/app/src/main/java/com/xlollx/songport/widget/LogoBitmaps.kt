package com.xlollx.songport.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import com.xlollx.songport.providers.Providers
import com.xlollx.songport.providers.ServiceMarks

/**
 * The two services' marks of a sync, source then target, overlapping as on the sync cards: drawn into
 * one bitmap for a widget, which cannot draw the app's vector marks. A clear gap rings the second
 * circle, so the pair reads on any wallpaper or widget colour.
 */
object LogoBitmaps {
    private val cache = HashMap<String, Bitmap>()

    fun pair(source: String, target: String, sizePx: Int): Bitmap = synchronized(cache) {
        cache.getOrPut("$source>$target@$sizePx") {
            val step = (sizePx * 0.62f).toInt()
            val bmp = Bitmap.createBitmap(step + sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            circle(c, source, 0f, sizePx.toFloat())
            val gap = sizePx * 0.08f
            c.drawCircle(step + sizePx / 2f, sizePx / 2f, sizePx / 2f + gap, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
            circle(c, target, step.toFloat(), sizePx.toFloat())
            bmp
        }
    }

    private fun circle(c: Canvas, serviceId: String, x: Float, size: Float) {
        val p = Providers.byId(serviceId)
        val bg = p?.brandColor?.toInt() ?: 0xFF6E6A7C.toInt()
        val fg = if (luminance(bg) > 0.45) 0xFF1C1B22.toInt() else Color.WHITE
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = bg
        c.drawCircle(x + size / 2f, size / 2f, size / 2f, paint)
        paint.color = fg
        val glyph = (p?.serviceId ?: serviceId).let { ServiceMarks.pathData(it) }
        if (glyph != null) {
            val path = runCatching { PathParser.createPathFromPathData(glyph) }.getOrNull()
            if (path != null) {
                val inner = size * 0.56f
                val m = Matrix().apply { setScale(inner / 24f, inner / 24f); postTranslate(x + (size - inner) / 2f, (size - inner) / 2f) }
                path.transform(m)
                c.drawPath(path, paint)
                return
            }
        }
        // No mark of its own: its initial, as in the app.
        paint.textSize = size * 0.46f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        val letter = (p?.displayName ?: serviceId).firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        c.drawText(letter, x + size / 2f, y, paint)
    }

    private fun luminance(color: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(Color.red(color)) + 0.7152 * ch(Color.green(color)) + 0.0722 * ch(Color.blue(color))
    }
}
