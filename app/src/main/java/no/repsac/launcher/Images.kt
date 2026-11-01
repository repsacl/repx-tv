package no.repsac.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable

/** Your pictures in assets/wallpapers/ and the color wallpapers. Used by both the home screen and the screensaver. */
object Images {

    private val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    private var files: List<String>? = null

    /** The pictures in app/src/main/assets/wallpapers/ (read only once) */
    fun files(context: Context): List<String> = files ?: (context.assets.list("wallpapers") ?: emptyArray())
        .filter { it.lowercase().substringAfterLast('.') in EXTENSIONS }
        .sorted()
        .also { files = it }

    /**
     * Loads a picture at a sensible size, so large phone photos don't use up the memory.
     * With hardware = true the picture lives in graphics memory instead of the app's memory.
     */
    fun load(context: Context, file: String, maxWidth: Int, hardware: Boolean = false): Bitmap? = try {
        val path = "wallpapers/$file"
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            if (hardware) inPreferredConfig = Bitmap.Config.HARDWARE
        }
        context.assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
    } catch (e: Exception) {
        null
    }

    /** Full-screen bitmap for a wallpaper key («image:file» or «color:Id») */
    fun wallpaper(context: Context, key: String): Bitmap? = when {
        key.startsWith("image:") -> load(context, key.removePrefix("image:"), 1920, hardware = true)
        key.startsWith("color:") -> ColorWallpapers.find(key.removePrefix("color:"))?.let { gradient(context, it.colors) }
        else -> null
    }

    /**
     * A small, blurry copy of a wallpaper, for the glass effect on the clock.
     * The picture is shrunk a lot and enlarged a little again, which gives a soft blur.
     */
    fun blurred(context: Context, key: String): Bitmap? = try {
        val source = when {
            key.startsWith("image:") -> load(context, key.removePrefix("image:"), 96)
            key.startsWith("color:") -> ColorWallpapers.find(key.removePrefix("color:"))?.let { gradient(context, it.colors, 96) }
            else -> null
        }
        source?.let {
            val ratio = it.height.toFloat() / it.width
            val tiny = Bitmap.createScaledBitmap(it, 24, maxOf(1, (24 * ratio).toInt()), true)
            Bitmap.createScaledBitmap(tiny, 160, maxOf(1, (160 * ratio).toInt()), true)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Draws a color wallpaper (see ColorWallpapers) once into a screen-sized bitmap.
     * With width, a small version is drawn instead (regular memory, not graphics memory).
     */
    fun gradient(context: Context, c: IntArray, width: Int? = null): Bitmap? {
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val w = width ?: screenW
        val h = if (width != null) maxOf(1, width * screenH / screenW) else screenH
        val scale = w / 1920f
        val base = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c[0], c[1], c[2]))
        val top = radial(c[3], 0.08f, -0.12f, 1250 * scale)
        val bottom = radial(c[4], 1.0f, 1.1f, 1000 * scale)
        listOf(base, top, bottom).forEach { @Suppress("DEPRECATION") it.setDither(true) }
        val layers = LayerDrawable(arrayOf<Drawable>(base, top, bottom))
        return try {
            val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            layers.setBounds(0, 0, w, h)
            layers.draw(Canvas(bm))
            if (width != null) bm else bm.copy(Bitmap.Config.HARDWARE, false)?.also { bm.recycle() } ?: bm
        } catch (e: Throwable) {
            null
        }
    }

    private fun radial(color: Int, cx: Float, cy: Float, r: Float) = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        colors = intArrayOf(color, color and 0x00FFFFFF)
        setGradientCenter(cx, cy)
        gradientRadius = r
    }
}
