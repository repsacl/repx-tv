package no.repsac.launcher

/**
 * The color wallpapers: a gradient from top to bottom, plus a glow from the top left and one from
 * the bottom right. The colors come from TV Detox (github.com/ansub/tv-detox, MIT license).
 *
 * Each entry: top, middle, bottom, glow top left, glow bottom right (ARGB).
 * Feel free to add your own. Your own pictures in assets/wallpapers/ are listed before these.
 */
object ColorWallpapers {

    class Wallpaper(val id: String, val name: Int /* string resource */, val colors: IntArray)

    val ALL: List<Wallpaper> = listOf(
        Wallpaper("Dusk", R.string.color_dusk, colors(0xFF15100F, 0xFF0A0809, 0xFF050405, 0x36FF8048, 0x2EAA3C8C)),
        Wallpaper("Midnight", R.string.color_midnight, colors(0xFF0E1220, 0xFF080A13, 0xFF040509, 0x384A6CFF, 0x2E5A3CC8)),
        Wallpaper("Plum", R.string.color_plum, colors(0xFF170E19, 0xFF0C070D, 0xFF050306, 0x38C050B4, 0x2E6440D2)),
        Wallpaper("Forest", R.string.color_forest, colors(0xFF0C150F, 0xFF070C09, 0xFF030604, 0x3050B478, 0x28289682)),
        Wallpaper("Graphite", R.string.color_graphite, colors(0xFF151617, 0xFF0B0C0D, 0xFF050506, 0x1CFFFFFF, 0x16A0B4C8)),
        Wallpaper("Ocean", R.string.color_ocean, colors(0xFF0A1517, 0xFF060C0E, 0xFF030607, 0x3426B4C8, 0x2E2A64C0)),
        Wallpaper("Ember", R.string.color_ember, colors(0xFF190D08, 0xFF0D0605, 0xFF060303, 0x40FF5A24, 0x32C8283C)),
        Wallpaper("Sunset", R.string.color_sunset, colors(0xFF8C3A5E, 0xFF5E2348, 0xFF22101E, 0xA0FF8A4C, 0x80E0457A)),
        Wallpaper("Lagoon", R.string.color_lagoon, colors(0xFF14588E, 0xFF15366E, 0xFF0A1632, 0x9030C8E0, 0x807A4CE0)),
        Wallpaper("Aurora", R.string.color_aurora, colors(0xFF0F4048, 0xFF0F2E3A, 0xFF08141C, 0x9038E0A0, 0x90A050F0)),
        Wallpaper("Coral", R.string.color_coral, colors(0xFFC0604E, 0xFF8A3456, 0xFF2E1230, 0x90FFB070, 0x70E04090)),
        Wallpaper("Iris", R.string.color_iris, colors(0xFF5A3CAE, 0xFF3A2A84, 0xFF16123A, 0x90C080FF, 0x804A7CFF)),
        Wallpaper("Meadow", R.string.color_meadow, colors(0xFF2E6E4C, 0xFF1C4A40, 0xFF0A201E, 0x90A8E070, 0x6030A8A8)),
        Wallpaper("Gold", R.string.color_gold, colors(0xFFB07624, 0xFF86401E, 0xFF2A1410, 0x90FFD060, 0x70FF5040)),
    )

    fun find(id: String): Wallpaper? = ALL.find { it.id == id }

    /** For moving settings from the first (Norwegian) version */
    fun idFromNorwegian(name: String): String? = mapOf(
        "Skumring" to "Dusk", "Midnatt" to "Midnight", "Plomme" to "Plum", "Skog" to "Forest",
        "Grafitt" to "Graphite", "Hav" to "Ocean", "Glør" to "Ember", "Solnedgang" to "Sunset",
        "Lagune" to "Lagoon", "Nordlys" to "Aurora", "Korall" to "Coral", "Iris" to "Iris",
        "Eng" to "Meadow", "Gull" to "Gold",
    )[name]

    private fun colors(vararg v: Long) = IntArray(v.size) { v[it].toInt() }
}
