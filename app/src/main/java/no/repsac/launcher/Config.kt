package no.repsac.launcher

/**
 * ===== YOUR SETTINGS =====
 * Change the values here and press ▶ (Run) in Android Studio to send the new version to the TV.
 * Most of these are only defaults: they can also be changed on the TV with the «Settings» tile.
 */
object Config {

    /**
     * Apps shown on the home screen the first time the launcher starts.
     * After that you change them on the TV: the «Add app» tile, or hold OK on an app.
     * Apps that aren't installed are simply skipped.
     */
    val DEFAULT_FAVORITES = listOf(
        "com.google.android.youtube.tv",           // YouTube
        "com.netflix.ninja",                       // Netflix
        "com.amazon.amazonvideo.livingroom",       // Prime Video
        "com.disney.disneyplus",                   // Disney+
        "com.spotify.tv.android",                  // Spotify
    )

    /** How many cards fit across the screen. More = smaller cards (6 is the original design). */
    const val CARDS_PER_ROW = 6

    /** Change to a random wallpaper every X minutes. 0 = never (default; can be changed on the TV) */
    const val ROTATE_WALLPAPER_MINUTES = 0

    /** Icons in the top right corner (the gear for TV settings is always shown) */
    const val SHOW_NETWORK_ICON = true
    const val SHOW_BLUETOOTH_ICON = true

    /** How much a card grows when it's focused */
    const val FOCUS_SCALE = 1.1f

    /** The animated outline around the focused card */
    val FOCUS_OUTLINE = FocusOutline.Style.RUNNING    // RUNNING, GLOW, PULSE, SOLID or OFF
    const val FOCUS_OUTLINE_WIDTH = 4                 // thickness (in 1920x1080 pixels)
    const val FOCUS_OUTLINE_GAP = 7                   // space between the card and the outline
    const val FOCUS_OUTLINE_COLOR = 0xFFFFFFFF.toInt()

    /** Font for all text except the clock (see Fonts for the keys) */
    const val APP_FONT = "system:sans-serif"

    /** The clock */
    const val CLOCK_FONT = "system:sans-serif-medium"
    const val CLOCK_GLASS = false                     // frosted glass effect

    /** Screensaver: how many seconds each picture is shown */
    const val SCREENSAVER_SECONDS = 30

    /** Corner radius of the cards (in 1920x1080 pixels) */
    const val CORNER_RADIUS = 26

    /** Colors (ARGB) */
    const val BANNER_COLOR = 0xFF1E1A1C.toInt()   // behind app banners
    const val GLASS_COLOR = 0x13FFFFFF            // the «glass» cards in the bottom row
    const val GLASS_FOCUS = 0x38FFFFFF            // glass when focused
    const val GLASS_BORDER = 0x17FFFFFF           // thin border around glass cards
    const val GLASS_ROW_BACKGROUND = false        // glass behind the bottom row's cards (off = only shown when focused)

    /** The rounded background behind the app row (the «dock») */
    const val APP_ROW_DOCK = true                 // default: on
    const val DOCK_COLOR = 0x66000000             // dark and slightly transparent
    const val DOCK_BORDER = 0x1FFFFFFF            // thin light border
    const val DOCK_PADDING = 30                   // space between the cards and the edge of the dock
}
