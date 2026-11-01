package no.repsac.launcher

import android.content.Context
import android.content.SharedPreferences

/** Remembers favorites, wallpaper and settings between restarts. */
class Prefs(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    init {
        migrateFromNorwegianKeys()
    }

    /** null = never changed, Config.DEFAULT_FAVORITES is used */
    var favorites: List<String>?
        get() = prefs.getString("favorites", null)?.split(",")?.filter { it.isNotBlank() }
        set(value) = prefs.edit().putString("favorites", value?.joinToString(",")).apply()

    /** How many cards fit across the screen (sets the size of every card) */
    var cardsPerRow: Int
        get() = prefs.getInt("cards_per_row", Config.CARDS_PER_ROW)
        set(value) = prefs.edit().putInt("cards_per_row", value).apply()

    /** Whether the app row sits in a rounded, dark background (the «dock») */
    var appRowDock: Boolean
        get() = prefs.getBoolean("app_row_dock", Config.APP_ROW_DOCK)
        set(value) = prefs.edit().putBoolean("app_row_dock", value).apply()

    /** Whether the cards in the bottom row have a glass background */
    var glassRowBackground: Boolean
        get() = prefs.getBoolean("glass_row_background", Config.GLASS_ROW_BACKGROUND)
        set(value) = prefs.edit().putBoolean("glass_row_background", value).apply()

    /** Style of the animated outline around the focused card */
    var focusOutline: FocusOutline.Style
        get() = FocusOutline.Style.entries.find { it.name == prefs.getString("focus_outline", null) }
            ?: Config.FOCUS_OUTLINE
        set(value) = prefs.edit().putString("focus_outline", value.name).apply()

    /** Space between the card and its outline (in 1920x1080 pixels) */
    var outlineGap: Int
        get() = prefs.getInt("outline_gap", Config.FOCUS_OUTLINE_GAP)
        set(value) = prefs.edit().putInt("outline_gap", value).apply()

    /** Font of the clock (see Fonts) */
    var clockFont: String
        get() = prefs.getString("clock_font", null) ?: Config.CLOCK_FONT
        set(value) = prefs.edit().putString("clock_font", value).apply()

    /** Font for all other text */
    var appFont: String
        get() = prefs.getString("app_font", null) ?: Config.APP_FONT
        set(value) = prefs.edit().putString("app_font", value).apply()

    /** Frosted glass effect on the clock */
    var clockGlass: Boolean
        get() = prefs.getBoolean("clock_glass", Config.CLOCK_GLASS)
        set(value) = prefs.edit().putBoolean("clock_glass", value).apply()

    // ----- Screensaver

    /** Seconds each picture is shown */
    var screensaverSeconds: Int
        get() = prefs.getInt("ss_seconds", Config.SCREENSAVER_SECONDS)
        set(value) = prefs.edit().putInt("ss_seconds", value).apply()

    var screensaverShuffle: Boolean
        get() = prefs.getBoolean("ss_shuffle", true)
        set(value) = prefs.edit().putBoolean("ss_shuffle", value).apply()

    /** Slow zoom on the pictures */
    var screensaverZoom: Boolean
        get() = prefs.getBoolean("ss_zoom", true)
        set(value) = prefs.edit().putBoolean("ss_zoom", value).apply()

    var screensaverClock: Boolean
        get() = prefs.getBoolean("ss_clock", true)
        set(value) = prefs.edit().putBoolean("ss_clock", value).apply()

    /** The screensaver that was selected before ours, so it can be restored */
    var previousScreensaver: String?
        get() = prefs.getString("ss_previous", null)
        set(value) = prefs.edit().putString("ss_previous", value).apply()

    /** «image:file.jpg» or «color:Name» */
    var wallpaper: String?
        get() = prefs.getString("wallpaper", null)
        set(value) = prefs.edit().putString("wallpaper", value).apply()

    /** Resets the look to the defaults (favorites, wallpaper and screensaver are kept) */
    fun resetAppearance() {
        prefs.edit()
            .remove("cards_per_row").remove("app_row_dock").remove("glass_row_background")
            .remove("focus_outline").remove("outline_gap")
            .remove("clock_font").remove("clock_glass").remove("app_font")
            .apply()
    }

    /**
     * The first versions of this launcher stored settings under Norwegian names.
     * Moves them to the English names once, so nobody loses their setup when updating.
     */
    private fun migrateFromNorwegianKeys() {
        if (prefs.getBoolean("migrated_v1", false)) return
        val old = prefs.all
        val e = prefs.edit()
        fun move(from: String, to: String, convert: (Any?) -> Any? = { it }) {
            if (!old.containsKey(from)) return
            when (val v = convert(old[from])) {
                is String -> e.putString(to, v)
                is Int -> e.putInt(to, v)
                is Boolean -> e.putBoolean(to, v)
            }
            if (from != to) e.remove(from)
        }
        val fontPrefix = { v: Any? -> (v as? String)?.replace("fil:", "asset:")?.replace("sti:", "path:") }
        move("favoritter", "favorites")
        move("per_rad", "cards_per_row")
        move("app_rad_bakgrunn", "app_row_dock")
        move("glass_bakgrunn", "glass_row_background")
        move("fokus_ramme", "focus_outline") { v ->
            mapOf("LOPENDE" to "RUNNING", "GLOD" to "GLOW", "PULS" to "PULSE", "FAST" to "SOLID", "AV" to "OFF")[v as? String ?: ""]
        }
        move("ramme_avstand", "outline_gap")
        move("klokke_font", "clock_font", fontPrefix)
        move("app_font", "app_font", fontPrefix)
        move("klokke_glass", "clock_glass")
        move("ss_sekunder", "ss_seconds")
        move("ss_tilfeldig", "ss_shuffle")
        move("ss_klokke", "ss_clock")
        move("ss_forrige", "ss_previous")
        move("bakgrunn_valg", "wallpaper") { v ->
            val s = v as? String
            when {
                s == null -> null
                s.startsWith("bilde:") -> "image:" + s.removePrefix("bilde:")
                s.startsWith("farge:") -> "color:" + (ColorWallpapers.idFromNorwegian(s.removePrefix("farge:")) ?: "Dusk")
                else -> s
            }
        }
        e.remove("bakgrunn") // a very early version stored an index here
        e.putBoolean("migrated_v1", true).apply()
    }
}
