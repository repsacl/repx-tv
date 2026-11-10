package no.repsac.launcher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Tiny white outline icons on a 24x24 grid, so the app needs no image files.
 * Ported to Kotlin from TV Detox (github.com/ansub/tv-detox, MIT license); some icons added.
 *
 * Each icon is a list of operations (in tenths of the grid, 0–240):
 * 0 = move to x y, 1 = line to x y, 2 = close, 3 = circle cx cy r,
 * 4 = arc cx cy r startDegrees sweepDegrees (0 degrees is 3 o'clock, going clockwise).
 */
class LineIcon(private val ops: IntArray) : Drawable() {

    companion object {
        val PLAY = intArrayOf(0, 60, 45, 1, 60, 195, 1, 190, 120, 2)
        val SETTINGS = intArrayOf(0, 40, 70, 1, 140, 70, 0, 180, 70, 1, 200, 70, 0, 40, 170, 1, 60, 170,
            0, 100, 170, 1, 200, 170, 3, 160, 70, 20, 3, 80, 170, 20)
        val BLUETOOTH = intArrayOf(0, 70, 80, 1, 170, 160, 1, 120, 205, 1, 120, 35, 1, 170, 80, 1, 70, 160)
        /** Gear: outline with eight teeth and a hole in the middle */
        val GEAR = intArrayOf(0, 191, 100, 1, 217, 105, 1, 217, 135, 1, 191, 140, 1, 185, 156, 1, 199, 178, 1, 178, 199, 1, 156, 185, 1, 140, 191, 1, 135, 217, 1, 105, 217, 1, 100, 191, 1, 84, 185, 1, 62, 199, 1, 41, 178, 1, 55, 156, 1, 49, 140, 1, 23, 135, 1, 23, 105, 1, 49, 100, 1, 55, 84, 1, 41, 62, 1, 62, 41, 1, 84, 55, 1, 100, 49, 1, 105, 23, 1, 135, 23, 1, 140, 49, 1, 156, 55, 1, 178, 41, 1, 199, 62, 1, 185, 84, 2, 3, 120, 120, 30)
        /** Text: a big T */
        val TEXT = intArrayOf(0, 50, 50, 1, 190, 50, 0, 120, 50, 1, 120, 195)
        /** Clock: circle with two hands */
        val CLOCK = intArrayOf(3, 120, 120, 90, 0, 120, 120, 1, 120, 62, 0, 120, 120, 1, 160, 145)
        /** Sparkle: four-pointed star, used for animation */
        val SPARKLE = intArrayOf(0, 120, 30, 1, 140, 100, 1, 210, 120, 1, 140, 140, 1, 120, 210, 1, 100, 140,
            1, 30, 120, 1, 100, 100, 2)
        /** Moon: used for the screensaver */
        val MOON = intArrayOf(4, 120, 120, 85, 50, 260, 4, 215, 120, 76, 122, 117)
        /** Music: two beamed notes */
        val MUSIC = intArrayOf(3, 75, 175, 25, 3, 165, 155, 25, 0, 100, 175, 1, 100, 55, 1, 190, 35, 1, 190, 155,
            0, 100, 85, 1, 190, 65)
        val PLUS = intArrayOf(0, 120, 50, 1, 120, 190, 0, 50, 120, 1, 190, 120)
        val WIFI = intArrayOf(4, 120, 195, 135, 225, 90, 4, 120, 195, 92, 225, 90, 4, 120, 195, 50, 225, 90,
            3, 120, 195, 4)
        val PICTURE = intArrayOf(0, 30, 50, 1, 210, 50, 1, 210, 165, 1, 30, 165, 2, 0, 120, 165, 1, 120, 200,
            0, 80, 200, 1, 160, 200)
        val GRID = intArrayOf(
            0, 45, 45, 1, 105, 45, 1, 105, 105, 1, 45, 105, 2,
            0, 135, 45, 1, 195, 45, 1, 195, 105, 1, 135, 105, 2,
            0, 45, 135, 1, 105, 135, 1, 105, 195, 1, 45, 195, 2,
            0, 135, 135, 1, 195, 135, 1, 195, 195, 1, 135, 195, 2,
        )
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xEBFFFFFF.toInt()
    }
    private val path = Path()
    private val arc = RectF()

    override fun draw(canvas: Canvas) {
        val s = bounds.width() / 240f
        paint.strokeWidth = 16 * s
        path.reset()
        var i = 0
        while (i < ops.size) {
            when (ops[i]) {
                0 -> { path.moveTo(ops[i + 1] * s, ops[i + 2] * s); i += 3 }
                1 -> { path.lineTo(ops[i + 1] * s, ops[i + 2] * s); i += 3 }
                2 -> { path.close(); i += 1 }
                3 -> { path.addCircle(ops[i + 1] * s, ops[i + 2] * s, ops[i + 3] * s, Path.Direction.CW); i += 4 }
                else -> {
                    val cx = ops[i + 1] * s
                    val cy = ops[i + 2] * s
                    val r = ops[i + 3] * s
                    arc.set(cx - r, cy - r, cx + r, cy + r)
                    path.addArc(arc, ops[i + 4].toFloat(), ops[i + 5].toFloat())
                    i += 6
                }
            }
        }
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = 44
    override fun getIntrinsicHeight() = 44
}
