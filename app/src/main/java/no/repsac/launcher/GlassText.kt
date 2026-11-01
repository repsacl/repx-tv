package no.repsac.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.widget.TextView

/**
 * Text that can look like frosted glass: the wallpaper shows through the letters, blurred,
 * with a faint white tint and a bright edge at the top (like light hitting glass).
 * With glass off it behaves like a regular TextView.
 */
class GlassText(context: Context) : TextView(context) {

    var glass = false
        set(value) {
            field = value
            invalidate()
        }

    private var blurShader: BitmapShader? = null
    private var blurWidth = 1
    private var blurHeight = 1
    private val matrix = Matrix()
    private val location = IntArray(2)
    private var edgeShader: LinearGradient? = null
    private var edgeHeight = -1

    // Makes the blurred wallpaper a little lighter, like frosted glass
    private val lighter = ColorMatrixColorFilter(ColorMatrix().apply {
        val s = 1.15f
        val o = 40f
        set(floatArrayOf(
            s, 0f, 0f, 0f, o,
            0f, s, 0f, 0f, o,
            0f, 0f, s, 0f, o,
            0f, 0f, 0f, 1f, 0f,
        ))
    })

    /** A small, blurry copy of the wallpaper (see Images.blurred). null = just a white glass tint. */
    fun setBackdrop(bitmap: Bitmap?) {
        blurShader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        blurWidth = bitmap?.width ?: 1
        blurHeight = bitmap?.height ?: 1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val text = getLayout()
        if (!glass || text == null) {
            super.onDraw(canvas)
            return
        }
        val p = paint
        val shadow = arrayOf(shadowRadius, shadowDx, shadowDy)
        val shadowCol = shadowColor

        canvas.save()
        canvas.translate(compoundPaddingLeft.toFloat(), extendedPaddingTop.toFloat())
        p.style = Paint.Style.FILL

        // 1) A soft shadow behind the glass, so the digits lift off the background
        p.shader = null
        p.color = 0x18000000
        p.setShadowLayer(textSize * 0.06f, 0f, textSize * 0.02f, 0x55000000)
        text.draw(canvas)
        p.clearShadowLayer()

        // 2) The blurred wallpaper, placed exactly where it is on the screen
        val backdrop = blurShader
        if (backdrop != null) {
            getLocationOnScreen(location)
            val screenW = resources.displayMetrics.widthPixels.toFloat()
            val screenH = resources.displayMetrics.heightPixels.toFloat()
            matrix.setScale(screenW / blurWidth, screenH / blurHeight)
            matrix.postTranslate(-location[0] - compoundPaddingLeft.toFloat(), -location[1] - extendedPaddingTop.toFloat())
            backdrop.setLocalMatrix(matrix)
            p.shader = backdrop
            p.colorFilter = lighter
            p.color = 0xFFFFFFFF.toInt()
            text.draw(canvas)
            p.colorFilter = null
        }

        // 3) White glass tint
        p.shader = null
        p.color = if (backdrop != null) 0x38FFFFFF else 0x66FFFFFF
        text.draw(canvas)

        // 4) Bright edge: strongest at the top, fainter at the bottom
        if (edgeHeight != text.height) {
            edgeHeight = text.height
            edgeShader = LinearGradient(
                0f, 0f, 0f, text.height.toFloat(),
                0xE6FFFFFF.toInt(), 0x33FFFFFF, Shader.TileMode.CLAMP,
            )
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = maxOf(1f, textSize * 0.012f)
        p.shader = edgeShader
        p.color = 0xFFFFFFFF.toInt()
        text.draw(canvas)

        // Put the paint back the way TextView expects it
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = currentTextColor
        if (shadow[0] > 0f) p.setShadowLayer(shadow[0], shadow[1], shadow[2], shadowCol)
        canvas.restore()
    }
}
