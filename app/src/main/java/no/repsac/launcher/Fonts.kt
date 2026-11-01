package no.repsac.launcher

import android.content.Context
import android.graphics.Typeface
import java.io.File

/**
 * The fonts you can choose: the system fonts on the TV, Google Sans if it's there,
 * and your own fonts in app/src/main/assets/fonts/ (.ttf or .otf).
 *
 * Keys: «system:sans-serif-thin», «path:/system/fonts/X.ttf» or «asset:MyFont.ttf».
 */
object Fonts {

    class Font(val key: String, val name: String)

    private val SYSTEM = listOf(
        "system:sans-serif-medium" to R.string.font_default,
        "system:sans-serif" to R.string.font_regular,
        "system:sans-serif-light" to R.string.font_light,
        "system:sans-serif-thin" to R.string.font_thin,
        "system:sans-serif-black" to R.string.font_black,
        "system:sans-serif-condensed" to R.string.font_condensed,
        "system:serif" to R.string.font_serif,
        "system:monospace" to R.string.font_mono,
        "system:casual" to R.string.font_casual,
        "system:cursive" to R.string.font_cursive,
    )

    // Fonts found on many Google TV devices; only listed if the file actually exists
    private val MAYBE = listOf(
        "/product/fonts/GoogleSans-Regular.ttf" to "Google Sans",
        "/product/fonts/GoogleSans-Medium.ttf" to "Google Sans Medium",
        "/system/fonts/GoogleSans-Regular.ttf" to "Google Sans",
        "/system/fonts/GoogleSans-Medium.ttf" to "Google Sans Medium",
    )

    private val cache = HashMap<String, Typeface>()

    fun all(context: Context): List<Font> {
        val list = SYSTEM.map { Font(it.first, context.getString(it.second)) }.toMutableList()
        MAYBE.filter { File(it.first).exists() }
            .distinctBy { it.second }
            .forEach { list += Font("path:${it.first}", it.second) }
        (context.assets.list("fonts") ?: emptyArray())
            .filter { it.lowercase().endsWith(".ttf") || it.lowercase().endsWith(".otf") }
            .sorted()
            .forEach { list += Font("asset:$it", it.substringBeforeLast('.')) }
        return list
    }

    fun name(context: Context, key: String): String =
        all(context).find { it.key == key }?.name ?: context.getString(R.string.font_default)

    fun typeface(context: Context, key: String): Typeface = cache.getOrPut(key) {
        try {
            when {
                key.startsWith("system:") -> Typeface.create(key.removePrefix("system:"), Typeface.NORMAL)
                key.startsWith("path:") -> Typeface.createFromFile(key.removePrefix("path:"))
                key.startsWith("asset:") -> Typeface.createFromAsset(context.assets, "fonts/" + key.removePrefix("asset:"))
                else -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        } catch (e: Exception) {
            Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }
}
