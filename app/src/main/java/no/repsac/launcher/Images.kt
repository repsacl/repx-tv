package no.repsac.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Your pictures in assets/wallpapers/. Used by both the home screen and the screensaver. */
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

    /** Full-screen bitmap for a wallpaper key («image:file») */
    fun wallpaper(context: Context, key: String): Bitmap? = when {
        key.startsWith("image:") -> load(context, key.removePrefix("image:"), 1920, hardware = true)
        else -> null
    }

    /**
     * A small, blurry copy of a wallpaper, for the glass effect on the clock.
     * The picture is shrunk a lot and enlarged a little again, which gives a soft blur.
     */
    fun blurred(context: Context, key: String): Bitmap? = try {
        val source = when {
            key.startsWith("image:") -> load(context, key.removePrefix("image:"), 96)
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
}
