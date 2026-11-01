package no.repsac.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

/**
 * The animated outline around the focused card. It's drawn slightly OUTSIDE the card (gap),
 * so it also shows on apps with a white banner.
 * First the outline is traced around the card, then it switches to the chosen style.
 * The animation only runs on the focused card, and stops when the home screen isn't visible.
 */
class FocusOutline(
    private val style: Style,
    private val width: Float,
    private val gap: Float,              // space between the card and the outline
    private val color: Int,
    private val radius: () -> Float,     // follows the card's corners, which round off a bit more when focused
) : Drawable() {

    enum class Style(val label: Int /* string resource */) {
        RUNNING(R.string.outline_running),
        GLOW(R.string.outline_glow),
        PULSE(R.string.outline_pulse),
        SOLID(R.string.outline_solid),
        OFF(R.string.outline_off),
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        color = this@FocusOutline.color
    }
    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width * 1.5f
    }
    // Glow: a wide, blurry stroke that breathes slowly
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width * 3f
        color = this@FocusOutline.color
        maskFilter = BlurMaskFilter(width * 5f, BlurMaskFilter.Blur.NORMAL)
    }
    private val rect = RectF()
    private val path = Path()
    private val segment = Path()
    private val measure = PathMeasure()
    private val matrix = Matrix()
    private var lightShader: SweepGradient? = null

    private var traced = 0f        // how much of the outline has been drawn (0–1)
    private var phase = 0f         // position in the repeating animation (0–1)
    private var visibility = 0f    // for fading out
    private val animators = mutableListOf<ValueAnimator>()
    private var generation = 0     // so a cancelled animation doesn't start the next step

    /** The card got focus: trace the outline around it, then start the style */
    fun show() {
        stop()
        if (style == Style.OFF) return
        visibility = 1f
        traced = 0f
        val mine = generation
        animate(0f, 1f, 380, DecelerateInterpolator(1.6f), onEnd = {
            if (mine == generation) startLoop()
        }) { traced = it }
    }

    /** The card lost focus: fade out */
    fun hide() {
        stop()
        if (visibility == 0f) return
        animate(visibility, 0f, 120, LinearInterpolator()) { visibility = it }
    }

    /** Stop all animation (e.g. when an app is opened) */
    fun stop() {
        generation++
        animators.toList().forEach { it.cancel() }
        animators.clear()
    }

    private fun startLoop() {
        val duration = when (style) {
            Style.RUNNING -> 2600L
            Style.GLOW -> 2400L
            Style.PULSE -> 1600L
            else -> return
        }
        animate(0f, 1f, duration, LinearInterpolator(), repeat = true) { phase = it }
    }

    private fun animate(
        from: Float, to: Float, ms: Long, interpolator: TimeInterpolator,
        repeat: Boolean = false, onEnd: (() -> Unit)? = null, set: (Float) -> Unit,
    ): ValueAnimator {
        val a = ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            this.interpolator = interpolator
            if (repeat) repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                set(it.animatedValue as Float)
                invalidateSelf()
            }
            if (onEnd != null) addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = onEnd()
            })
        }
        animators += a
        a.start()
        return a
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        // A bright patch that travels around: transparent – white – transparent
        val solid = color or 0xFF000000.toInt()
        val clear = color and 0x00FFFFFF
        lightShader = SweepGradient(
            bounds.exactCenterX(), bounds.exactCenterY(),
            intArrayOf(clear, clear, solid, clear),
            floatArrayOf(0f, 0.62f, 0.82f, 1f),
        )
        light.shader = lightShader
    }

    override fun draw(canvas: Canvas) {
        if (style == Style.OFF || visibility <= 0f) return
        val b = bounds
        val out = gap + width / 2   // the middle of the stroke sits this far outside the card
        val r = radius() + out
        rect.set(b.left - out, b.top - out, b.right + out, b.bottom + out)
        path.reset()
        path.addRoundRect(rect, r, r, Path.Direction.CW)

        // Glow: fades in instead of being traced
        if (style == Style.GLOW) {
            val pulse = if (traced < 1f) traced else 0.7f + 0.3f * ((sin(2 * PI * phase).toFloat() + 1f) / 2f)
            glow.alpha = (230 * pulse * visibility).toInt()
            canvas.drawPath(path, glow)
            stroke.alpha = (110 * pulse * visibility).toInt()
            canvas.drawPath(path, stroke)
            return
        }

        // 1) The outline is traced around the card
        if (traced < 1f) {
            measure.setPath(path, true)
            segment.reset()
            measure.getSegment(0f, measure.length * traced, segment, true)
            stroke.alpha = (255 * visibility).toInt()
            canvas.drawPath(segment, stroke)
            return
        }

        // 2) The style
        when (style) {
            Style.SOLID -> {
                stroke.alpha = (255 * visibility).toInt()
                canvas.drawPath(path, stroke)
            }
            Style.PULSE -> {
                val pulse = 0.55f + 0.45f * ((sin(2 * PI * phase).toFloat() + 1f) / 2f)
                stroke.alpha = (255 * pulse * visibility).toInt()
                canvas.drawPath(path, stroke)
            }
            Style.RUNNING -> {
                stroke.alpha = (150 * visibility).toInt()
                canvas.drawPath(path, stroke)
                matrix.setRotate(phase * 360f, b.exactCenterX(), b.exactCenterY())
                lightShader?.setLocalMatrix(matrix)
                light.alpha = (255 * visibility).toInt()
                canvas.drawPath(path, light)
            }
            Style.GLOW, Style.OFF -> Unit
        }
    }

    override fun setAlpha(alpha: Int) { stroke.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        light.colorFilter = colorFilter
        glow.colorFilter = colorFilter
    }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
