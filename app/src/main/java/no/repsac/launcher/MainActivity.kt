package no.repsac.launcher

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/** An installed app */
data class App(val pkg: String, val name: String, val banner: Drawable?, val icon: Drawable?)

/** A choice in the side panel */
class Choice(val name: String, val icon: Drawable? = null, val font: Typeface? = null, val action: () -> Unit)

/**
 * The home screen. The design builds on TV Detox (github.com/ansub/tv-detox, MIT license):
 * a big clock, a row of app cards, a row of «glass» cards at the bottom and a side panel for menus.
 * All measurements are drawn for a 1920x1080 screen and scaled with px().
 */
class MainActivity : Activity() {

    private companion object {
        const val MARGIN = 96        // distance from the screen edge
        const val GAP = 36           // between cards
        const val FOCUS_MS = 180L
        const val PLAY_STORE = "com.android.vending"
    }

    private lateinit var wallpaperView: ImageView
    private lateinit var scrim: View
    private lateinit var scroller: ScrollView
    private lateinit var content: FrameLayout
    private lateinit var date: TextView
    private lateinit var clock: GlassText
    private lateinit var prefs: Prefs
    private lateinit var dateTime: DateTime

    private val handler = Handler(Looper.getMainLooper())
    private val ease = DecelerateInterpolator(1.8f)
    // Font for all text (except the clock, which has its own). Chosen under Settings → Text.
    private lateinit var regular: Typeface
    private lateinit var semibold: Typeface

    private val tiles = mutableListOf<View>()
    private val colorAnimators = HashMap<View, ValueAnimator>()
    private val colorBlender = ArgbEvaluator()

    // The app list is only read again when an app is installed, updated or removed
    private var allApps: List<App> = emptyList()
    private var appsStale = true
    private val bannerCache = HashMap<String, Drawable>()   // banners scaled to card size

    // Wallpapers are decoded on a separate thread, so the screen never freezes
    private val worker = Executors.newSingleThreadExecutor()
    private var shownWallpaper: String? = null
    private var wallpaperRequest = 0
    private val thumbnails = HashMap<String, Drawable?>()

    private var visible = false

    /** The outline on the card that's focused right now */
    private var activeOutline: FocusOutline? = null

    private val packagesChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            appsStale = true
            bannerCache.clear()
            if (visible) buildTiles()
        }
    }
    // «Now playing» from Spotify, in the top right corner under the icons
    private lateinit var nowPlaying: NowPlayingView
    private lateinit var nowPlayingPoller: NowPlayingPoller

    private var lastFocus: String? = null
    private var firstTile: View? = null
    private var scale = 1f

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        wallpaperView = findViewById(R.id.wallpaper)
        scrim = findViewById(R.id.scrim)
        scroller = findViewById(R.id.scroller)
        content = findViewById(R.id.content)
        prefs = Prefs(this)
        dateTime = DateTime(this)
        scale = resources.displayMetrics.widthPixels / 1920f
        loadAppFont()
        migrateScreensaverComponent()

        buildClock()
        buildTopIcons()
        buildNowPlaying()
        currentWallpaper()?.let { showWallpaper(it) }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packagesChanged, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(packagesChanged, filter)
    }

    override fun onDestroy() {
        unregisterReceiver(packagesChanged)
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        // Only rebuild the cards if something changed; otherwise everything (and the focus) stays put
        if (appsStale || tiles.isEmpty()) buildTiles() else activeOutline?.show()
        updateClock.run()
        startNowPlaying()
        scheduleWallpaperChange()
    }

    override fun onPause() {
        super.onPause()
        visible = false
        handler.removeCallbacksAndMessages(null)
        activeOutline?.stop()   // no animation while an app is showing
        nowPlayingPoller.stop()
    }

    /** Home pressed while already on the home screen: back to the top and the first app */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        scroller.smoothScrollTo(0, 0)
        firstTile?.requestFocus()
    }

    /** Back shouldn't close the home screen */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- clock

    private fun buildClock() {
        date = text(px(30), 0xA8FFFFFF.toInt(), regular)
        clock = GlassText(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(176).toFloat())
            setTextColor(Color.WHITE)
            typeface = Fonts.typeface(this@MainActivity, prefs.clockFont)
            glass = prefs.clockGlass
            letterSpacing = -0.028f
            fontFeatureSettings = "tnum"
            includeFontPadding = false
        }
        // Soft shadow, so the clock also shows on bright pictures (the glass effect has its own)
        listOf(date, clock).forEach { it.setShadowLayer(px(16).toFloat(), 0f, 0f, 0x55000000) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(date, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(clock, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = px(-20) })
        }
        content.addView(column, at(px(MARGIN), px(92), WRAP, WRAP))
    }

    /** The icons in the top right corner, level with the date */
    private fun buildTopIcons() {
        val icons = mutableListOf<Triple<IntArray, String, () -> Unit>>()
        if (Config.SHOW_BLUETOOTH_ICON) {
            icons += Triple(LineIcon.BLUETOOTH, getString(R.string.bluetooth)) { openBluetooth() }
        }
        if (Config.SHOW_NETWORK_ICON) {
            icons += Triple(LineIcon.WIFI, getString(R.string.network)) { startSafely(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        }
        // The gear on the far right opens the TV's own settings
        icons += Triple(LineIcon.GEAR, getString(R.string.tv_settings)) { startSafely(Intent(Settings.ACTION_SETTINGS)) }
        val button = px(72)
        val spacing = px(12)
        // The right edge of the last icon lines up with the cards below
        var x = resources.displayMetrics.widthPixels - px(MARGIN) + (button - px(40)) / 2 -
            icons.size * button - (icons.size - 1) * spacing
        for ((icon, name, action) in icons) {
            addIcon(LineIcon(icon), name, x, px(76), action)
            x += button + spacing
        }
    }

    /** The «now playing» card, right-aligned with the cards below and just under the top icons */
    private fun buildNowPlaying() {
        nowPlaying = NowPlayingView(this, scale, regular)
        nowPlayingPoller = NowPlayingPoller(this) { np, art -> nowPlaying.show(np, art) }
        content.addView(nowPlaying, FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.END or Gravity.TOP
            rightMargin = px(MARGIN)
            topMargin = px(176)
        })
    }

    private fun startNowPlaying() {
        if (prefs.nowPlayingHome && Spotify.isConnected(prefs)) {
            nowPlayingPoller.start()
        } else {
            nowPlayingPoller.stop()
            nowPlaying.show(null, null)
        }
    }

    private val updateClock = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = dateTime.time(now)
            date.text = dateTime.date(now)
            // Next time exactly when the minute changes
            handler.postDelayed(this, DateTime.untilNextMinute(now))
        }
    }

    // ---------------------------------------------------------------- wallpaper

    /** Changes to a random other picture, like the screensaver, and starts the timer again */
    private val rotateWallpaper = Runnable {
        val others = wallpaperChoices().map { it.first }.filter { it != currentWallpaper() }
        if (others.isNotEmpty()) {
            val next = others.random()
            prefs.wallpaper = next
            prefs.wallpaperChangedAt = System.currentTimeMillis()
            showWallpaper(next, animate = true)
        }
        scheduleWallpaperChange()
    }

    /**
     * Sets the timer for the next wallpaper change. Counts from the last change, so it also
     * changes while you're in an app: if the time has passed, it changes as soon as you're back.
     */
    private fun scheduleWallpaperChange() {
        handler.removeCallbacks(rotateWallpaper)
        val minutes = prefs.wallpaperChangeMinutes
        if (minutes <= 0 || wallpaperChoices().size < 2) return
        val elapsed = System.currentTimeMillis() - prefs.wallpaperChangedAt
        handler.postDelayed(rotateWallpaper, (minutes * 60_000L - elapsed).coerceAtLeast(0))
    }

    /** All wallpapers: (key, name), from your pictures in assets/wallpapers/ */
    private fun wallpaperChoices(): List<Pair<String, String>> =
        Images.files(this).map { "image:$it" to it.substringBeforeLast('.') }

    /** null = no pictures added: the background stays plain black */
    private fun currentWallpaper(): String? {
        val keys = wallpaperChoices().map { it.first }
        val saved = prefs.wallpaper
        return if (saved != null && saved in keys) saved else keys.firstOrNull()
    }

    private fun showWallpaper(key: String, animate: Boolean = false) {
        if (key == shownWallpaper) return
        shownWallpaper = key
        val request = ++wallpaperRequest
        val isImage = key.startsWith("image:")
        // Decode in the background; if you scroll quickly through the menu, only the last choice is shown
        worker.execute {
            val bitmap = Images.wallpaper(this, key) ?: return@execute
            val blurred = Images.blurred(this, key)   // for the glass clock
            wallpaperView.post {
                if (request == wallpaperRequest) {
                    applyWallpaper(bitmap, isImage, animate)
                    clock.setBackdrop(blurred)
                }
            }
        }
    }

    private fun applyWallpaper(bitmap: Bitmap, isImage: Boolean, animate: Boolean) {
        val swap = {
            wallpaperView.setImageBitmap(bitmap)
            scrim.visibility = if (isImage) View.VISIBLE else View.GONE
        }
        if (animate) {
            wallpaperView.animate().alpha(0f).setDuration(400).withEndAction {
                swap()
                wallpaperView.animate().alpha(1f).setDuration(600).start()
            }.start()
        } else {
            swap()
        }
    }

    /** Small preview icon for the wallpaper menu */
    private fun wallpaperIcon(key: String): Drawable? = thumbnails.getOrPut(key) {
        Images.load(this, key.removePrefix("image:"), 96)?.let { BitmapDrawable(resources, it) }
    }

    // ---------------------------------------------------------------- apps

    private fun loadApps(): List<App> {
        val pm = packageManager
        val found = LinkedHashMap<String, App>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            @Suppress("DEPRECATION")
            for (info in pm.queryIntentActivities(intent, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == packageName || found.containsKey(pkg)) continue
                val banner = info.activityInfo.loadBanner(pm)
                    ?: info.activityInfo.applicationInfo.loadBanner(pm)
                found[pkg] = App(pkg, info.loadLabel(pm).toString(), banner, info.loadIcon(pm))
            }
        }
        return found.values.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }

    /** The apps on the home screen, in order */
    private fun favorites(): List<App> {
        val saved = prefs.favorites
        val byPackage = allApps.associateBy { it.pkg }
        val list = (saved ?: Config.DEFAULT_FAVORITES).mapNotNull { byPackage[it] }
        // First run, and none of the default apps are installed: show the first apps
        return if (saved == null && list.isEmpty()) allApps.take(Config.CARDS_PER_ROW) else list
    }

    private fun startApp(pkg: String) {
        lastFocus = pkg
        val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            toast(R.string.cannot_open_app)
            return
        }
        startSafely(intent)
    }

    /**
     * Google TV doesn't have the usual Bluetooth shortcut (android.settings.BLUETOOTH_SETTINGS).
     * Bluetooth lives under «Remotes & Accessories», which is a «slice» provided by Google's
     * Bluetooth service. It's opened through the TV settings' SliceActivity. Tested on Chromecast with Google TV.
     * Needs WRITE_SECURE_SETTINGS (granted once with adb, see README).
     */
    private fun openBluetooth() {
        val candidates = listOf(
            // Remotes & Accessories
            Intent("android.settings.SLICE_SETTINGS")
                .setClassName("com.android.tv.settings", "com.android.tv.settings.slice.SliceActivity")
                .putExtra("slice_uri", "content://com.google.android.tv.btservices.settings.sliceprovider/general"),
            // Fallback: the page for pairing a new accessory
            Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.accessories.AddAccessoryActivity"),
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
        )
        val errors = mutableListOf<String>()
        for (intent in candidates) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // Remember why, and try the next one
                errors += "${intent.component?.shortClassName ?: intent.action}: ${e.javaClass.simpleName}"
            }
        }
        Toast.makeText(this, getString(R.string.bluetooth_failed) + "\n" + errors.joinToString("\n"), Toast.LENGTH_LONG).show()
        startSafely(Intent(Settings.ACTION_SETTINGS))
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast(R.string.not_available)
        }
    }

    // ---------------------------------------------------------------- build the screen

    private fun buildTiles() {
        // Keep focus away while the cards are swapped out
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        tiles.forEach { content.removeView(it) }
        tiles.clear()
        firstTile = null
        activeOutline = null
        if (appsStale) {
            allApps = loadApps()
            appsStale = false
        }
        val favorites = favorites()

        val n = prefs.cardsPerRow
        val m = px(MARGIN)
        val g = px(GAP)
        val w = (resources.displayMetrics.widthPixels - 2 * m - g * (n - 1)) / n
        val h = w * 9 / 16
        // The dock around the app row takes some room; the name under a focused card goes below the dock
        val dockPad = if (prefs.appRowDock && favorites.isNotEmpty()) px(Config.DOCK_PADDING) else 0
        val step = h + px(79) + dockPad   // room for the name under a focused card
        // The cards sit at the bottom of the screen: one row of apps, and the glass row below
        val top = px(973) - step - h
        val bottomRow = top + step
        content.minimumHeight = bottomRow + h + px(MARGIN)

        // Both rows scroll sideways when there are more cards than fit
        val appRow = makeScrollingRow(top, step, favorites.size, w, h, g, prefs.appRowDock)
        favorites.forEachIndexed { i, app ->
            addTile(
                key = app.pkg, name = app.name, lineIcon = null,
                banner = app.banner?.let { scaledBanner(app.pkg, it, w, h) },
                appIcon = if (app.banner == null) app.icon else null,
                x = appRow.pad + i * (w + g), y = appRow.pad, w = w, h = h,
                onClick = { startApp(app.pkg) }, onLongClick = { showAppMenu(app) },
                parent = appRow.row, onFocus = { holder -> scrollIntoView(appRow, holder, w) },
                captionGap = dockPad + px(18),
            )
        }

        // The bottom row: glass cards with line icons
        val tools = mutableListOf<Triple<String, IntArray, () -> Unit>>()
        tools += Triple(getString(R.string.all_apps), LineIcon.GRID) { showAllApps() }
        if (favorites.none { it.pkg == PLAY_STORE } && allApps.any { it.pkg == PLAY_STORE }) {
            tools += Triple(getString(R.string.play_store), LineIcon.PLAY) { startApp(PLAY_STORE) }
        }
        tools += Triple(getString(R.string.wallpaper), LineIcon.PICTURE) { showWallpaperMenu() }
        tools += Triple(getString(R.string.settings), LineIcon.SETTINGS) { showSettings() }   // the home screen's settings
        tools += Triple(getString(R.string.add_app), LineIcon.PLUS) { showAddApp() }

        val glassRow = makeScrollingRow(bottomRow, h, tools.size, w, h, g)
        tools.forEachIndexed { i, (name, icon, action) ->
            addTile(
                key = "tool:$i", name = name, banner = null, lineIcon = icon, appIcon = null,
                noBackground = !prefs.glassRowBackground,
                x = i * (w + g), y = 0, w = w, h = h,
                onClick = action, onLongClick = null,
                parent = glassRow.row, onFocus = { holder -> scrollIntoView(glassRow, holder, w) },
            )
        }

        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val target = lastFocus?.let { content.findViewWithTag<View>(it) } ?: firstTile
        target?.requestFocus()
    }

    /**
     * Draws the banner once at the card's size and remembers it. App banners are often
     * much larger than the card, so this saves both memory and drawing time.
     */
    private fun scaledBanner(pkg: String, banner: Drawable, w: Int, h: Int): Drawable =
        bannerCache.getOrPut("$pkg@$w") {
            try {
                val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val iw = banner.intrinsicWidth.takeIf { it > 0 } ?: w
                val ih = banner.intrinsicHeight.takeIf { it > 0 } ?: h
                val s = maxOf(w.toFloat() / iw, h.toFloat() / ih)
                val dw = (iw * s).roundToInt()
                val dh = (ih * s).roundToInt()
                banner.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh)
                banner.draw(Canvas(bm))
                BitmapDrawable(resources, bm)
            } catch (e: Throwable) {
                banner
            }
        }

    /**
     * One card. With a banner: the picture fills the card, and the name appears below when focused.
     * Without a banner: a «glass» card with icon and name inside.
     */
    private fun addTile(
        key: String, name: String, banner: Drawable?, lineIcon: IntArray?, appIcon: Drawable?,
        x: Int, y: Int, w: Int, h: Int,
        onClick: () -> Unit, onLongClick: (() -> Unit)?,
        parent: ViewGroup = content, onFocus: ((View) -> Unit)? = null,
        noBackground: Boolean = false,
        captionGap: Int = px(22),   // distance from the card down to the name
    ) {
        val glass = banner == null
        val radius = px(Config.CORNER_RADIUS)
        val bg = GradientDrawable().apply { cornerRadius = radius.toFloat() }
        // Two layers: «card» takes focus and grows, and draws the outline just OUTSIDE itself.
        // «surface» is the card itself with rounded corners (clipped, so the banner follows the corners).
        val card = FrameLayout(this)
        val surface = FrameLayout(this)

        if (!glass) {
            bg.setColor(Config.BANNER_COLOR)
            surface.addView(ImageView(this).apply {
                setImageDrawable(banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            if (noBackground) {
                bg.setColor(Color.TRANSPARENT)
            } else {
                bg.setColor(Config.GLASS_COLOR)
                bg.setStroke(max(1, px(2)), Config.GLASS_BORDER)
            }
            val iconSize = if (lineIcon != null) px(44) else px(64)
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(if (lineIcon != null) LineIcon(lineIcon) else appIcon)
                }, LinearLayout.LayoutParams(iconSize, iconSize))
                addView(text(px(24), 0xE0FFFFFF.toInt(), regular).apply {
                    this.text = name
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setPadding(px(16), 0, px(16), 0)
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = px(12) })
            }
            surface.addView(inner, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        surface.background = bg
        surface.clipToOutline = true
        card.addView(surface, FrameLayout.LayoutParams(MATCH, MATCH))
        card.clipChildren = false
        // The shadow under the lifted card follows the rounded corners
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, bg.cornerRadius)
        }
        card.isFocusable = true
        card.tag = key

        // The animated outline around the card when it's focused
        val outline = FocusOutline(
            style = prefs.focusOutline,
            width = max(2, px(Config.FOCUS_OUTLINE_WIDTH)).toFloat(),
            gap = px(prefs.outlineGap).toFloat(),
            color = Config.FOCUS_OUTLINE_COLOR,
            radius = { bg.cornerRadius },
        )
        card.foreground = outline

        val caption = if (glass) null else text(px(24), Color.WHITE, regular).apply {
            this.text = name
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.INVISIBLE
            setShadowLayer(px(12).toFloat(), 0f, 0f, 0x88000000.toInt())
        }

        val rest = when {
            noBackground -> Color.TRANSPARENT   // the glass only shows when the card is focused
            glass -> Config.GLASS_COLOR
            else -> Config.BANNER_COLOR
        }
        val focused = if (glass) Config.GLASS_FOCUS else Config.BANNER_COLOR
        card.setOnFocusChangeListener { v, has ->
            if (has) {
                lastFocus = key
                onFocus?.invoke(v.parent as View)
                activeOutline = outline
                outline.show()
            } else {
                outline.hide()
            }
            lift(v, bg, has, rest, focused, radius, radius + px(10), outlineView = surface)
            caption?.let { t ->
                t.animate().cancel()
                if (has) {
                    t.visibility = View.VISIBLE
                    t.alpha = 0f
                    t.animate().alpha(1f).setDuration(FOCUS_MS).setInterpolator(ease).start()
                } else {
                    t.animate().alpha(0f).setDuration(FOCUS_MS / 2)
                        .withEndAction { t.visibility = View.INVISIBLE }.start()
                }
            }
        }
        card.setOnClickListener { onClick() }
        if (onLongClick != null) card.setOnLongClickListener { onLongClick(); true }

        val holder = FrameLayout(this).apply { clipChildren = false }
        holder.addView(card, FrameLayout.LayoutParams(w, h))
        caption?.let { holder.addView(it, FrameLayout.LayoutParams(w, WRAP).apply { topMargin = h + captionGap }) }
        parent.addView(holder, at(x, y, w, WRAP))
        if (parent === content) tiles += holder
        if (firstTile == null) firstTile = card
    }

    /**
     * A row that scrolls sideways. Returns the scroll view and the row the cards go into.
     * y is where the cards start, height is the room the row needs (card + name below).
     */
    private fun makeScrollingRow(
        y: Int, height: Int, count: Int, w: Int, h: Int, g: Int, withDock: Boolean = false,
    ): Row {
        val m = px(MARGIN)
        val air = px(48)   // room above and below, so a grown card with outline/glow isn't cut off
        val screen = resources.displayMetrics.widthPixels
        val contentWidth = max(0, count * (w + g) - g)

        // With the dock, the cards sit in a rounded, dark background that stretches under
        // all the apps and scrolls with them. «pad» is the space between the cards and the dock's edge.
        val pad = if (withDock && count > 0) px(Config.DOCK_PADDING) else 0

        val scrollView = object : HorizontalScrollView(this) {
            // We handle scrolling ourselves (see scrollIntoView), so the cards stay inside the margins
            override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int = 0
        }.apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            setPadding(m - pad, air, m - pad, air)
        }
        val row = FrameLayout(this).apply { clipChildren = false }
        if (pad > 0) {
            row.addView(View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = (px(Config.CORNER_RADIUS) + pad).toFloat()
                    setColor(Config.DOCK_COLOR)
                    setStroke(max(1, px(2)), Config.DOCK_BORDER)
                }
            }, FrameLayout.LayoutParams(contentWidth + 2 * pad, h + 2 * pad))
        }
        scrollView.addView(row, FrameLayout.LayoutParams(contentWidth + 2 * pad, height + 2 * pad))
        content.addView(scrollView, at(0, y - air - pad, screen, height + 2 * pad + 2 * air))
        tiles += scrollView
        return Row(scrollView, row, pad)
    }

    /** A row that scrolls sideways: the cards go in «row», offset by «pad» */
    private class Row(val scroll: HorizontalScrollView, val row: FrameLayout, val pad: Int)

    /** Scrolls the row just enough for the focused card (and the dock around it) to be inside the margins */
    private fun scrollIntoView(row: Row, holder: View, w: Int) {
        val scrollView = row.scroll
        scrollView.post {
            val visibleWidth = scrollView.width - scrollView.paddingLeft - scrollView.paddingRight
            val x = scrollView.scrollX
            val left = holder.left - row.pad
            val right = holder.left + w + row.pad
            val newX = when {
                left < x -> left
                right > x + visibleWidth -> right - visibleWidth
                else -> x
            }
            if (newX != x) scrollView.smoothScrollTo(newX, 0)
        }
    }

    /** An icon without a card: a glass circle shows behind it only when it's focused */
    private fun addIcon(icon: Drawable, description: String, x: Int, y: Int, action: () -> Unit) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
        }
        val button = FrameLayout(this).apply {
            background = bg
            isFocusable = true
            contentDescription = description
            tag = "icon:$description"
            addView(ImageView(this@MainActivity).apply { setImageDrawable(icon) },
                FrameLayout.LayoutParams(px(40), px(40), Gravity.CENTER))
            setOnFocusChangeListener { v, has ->
                if (has) lastFocus = v.tag as String
                lift(v, bg, has, Color.TRANSPARENT, Config.GLASS_FOCUS, 0, 0)
            }
            setOnClickListener { action() }
        }
        content.addView(button, at(x, y, px(72), px(72)))
    }

    /** The focus lift: the card grows, rises, brightens and rounds off a little */
    private fun lift(
        v: View, bg: GradientDrawable, has: Boolean, rest: Int, focused: Int, r0: Int, r1: Int, outlineView: View = v,
    ) {
        val s = if (has) Config.FOCUS_SCALE else 1f
        v.animate().cancel()
        // No withLayer(): a layer would cut off the outline that's drawn outside the card
        v.animate().scaleX(s).scaleY(s).translationZ(if (has) px(26).toFloat() else 0f)
            .setDuration(FOCUS_MS).setInterpolator(ease).start()

        colorAnimators.remove(v)?.cancel()
        val from = if (has) rest else focused
        val to = if (has) focused else rest
        val ra = if (has) r0 else r1
        val rb = if (has) r1 else r0
        if (from == to && ra == rb) return
        val a = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FOCUS_MS
            interpolator = ease
            addUpdateListener { an ->
                val t = an.animatedValue as Float
                if (from != to) bg.setColor(colorBlender.evaluate(t, from, to) as Int)
                if (ra != rb) {
                    bg.cornerRadius = ra + (rb - ra) * t
                    outlineView.invalidateOutline()
                    if (outlineView !== v) v.invalidateOutline()
                }
            }
        }
        colorAnimators[v] = a
        a.start()
    }

    // ---------------------------------------------------------------- menus

    /** Hold OK on an app */
    private fun showAppMenu(app: App) {
        val fav = favorites().map { it.pkg }.toMutableList()
        val i = fav.indexOf(app.pkg)
        val choices = mutableListOf<Choice>()
        if (i > 0) choices += Choice(getString(R.string.move_left)) { swap(fav, i, i - 1) }
        if (i in 0 until fav.size - 1) choices += Choice(getString(R.string.move_right)) { swap(fav, i, i + 1) }
        choices += Choice(getString(R.string.remove_from_home)) {
            fav.remove(app.pkg)
            prefs.favorites = fav
            lastFocus = null
        }
        choices += Choice(getString(R.string.app_info)) {
            startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
        }
        panel(app.name, choices)
    }

    private fun swap(list: MutableList<String>, i: Int, j: Int) {
        list[i] = list[j].also { list[j] = list[i] }
        prefs.favorites = list
    }

    /** Card sizes: name and how many cards fit across the screen */
    private val cardSizes = listOf(
        R.string.size_xl to 4, R.string.size_l to 5, R.string.size_default to 6,
        R.string.size_m to 7, R.string.size_s to 8, R.string.size_xs to 9,
    )

    /** Distance between the card and its outline (in 1920x1080 pixels) */
    private val outlineGaps = listOf(
        R.string.gap_none to 0, R.string.gap_tight to 4, R.string.gap_normal to 7,
        R.string.gap_airy to 12, R.string.gap_extra to 18,
    )

    /** Seconds per picture in the screensaver */
    private val pictureTimes = listOf(10, 20, 30, 60, 120, 300)

    /** Minutes before the screensaver starts */
    private val waitTimes = listOf(1, 5, 10, 15, 30, 60)

    private fun onOff(b: Boolean) = getString(if (b) R.string.on else R.string.off)
    private fun label(name: Int, value: String) = getString(R.string.label_value, getString(name), value)
    private fun nameFor(list: List<Pair<Int, Int>>, value: Int) =
        list.find { it.second == value }?.let { getString(it.first) } ?: "$value"

    private fun seconds(s: Int) = if (s < 60) resources.getQuantityString(R.plurals.seconds, s, s) else minutes(s / 60)
    private fun minutes(m: Int) = if (m < 60) resources.getQuantityString(R.plurals.minutes, m, m)
        else resources.getQuantityString(R.plurals.hours, m / 60, m / 60)

    /** The home screen's settings (the «Settings» tile in the glass row), split into sections */
    private fun showSettings() {
        panel(getString(R.string.settings), listOf(
            Choice(getString(R.string.section_tiles), LineIcon(LineIcon.GRID)) { showTileSettings() },
            Choice(getString(R.string.section_focus), LineIcon(LineIcon.SPARKLE)) { showFocusSettings() },
            Choice(getString(R.string.section_text), LineIcon(LineIcon.TEXT)) { showTextSettings() },
            Choice(getString(R.string.section_clock), LineIcon(LineIcon.CLOCK)) { showClockSettings() },
            Choice(getString(R.string.section_screensaver), LineIcon(LineIcon.MOON)) { showScreensaverSettings() },
            Choice(getString(R.string.section_spotify), LineIcon(LineIcon.MUSIC)) { showSpotifySettings() },
            Choice(getString(R.string.wallpaper), LineIcon(LineIcon.PICTURE)) { showWallpaperSettings() },
            Choice(getString(R.string.reset_appearance), LineIcon(LineIcon.SETTINGS)) {
                prefs.resetAppearance()
                refreshAppearance()
                showSettings()
            },
        ))
    }

    /** Minutes between wallpaper changes (0 = keep the same picture) */
    private val wallpaperIntervals = listOf(0, 5, 15, 30, 60, 180, 720, 1440)

    private fun intervalName(m: Int) = if (m <= 0) getString(R.string.wp_never) else getString(R.string.wp_every, minutes(m))

    /** Wallpaper: which picture, and whether it changes by itself */
    private fun showWallpaperSettings() {
        val here = ::showWallpaperSettings
        val current = currentWallpaper()?.removePrefix("image:")?.substringBeforeLast('.') ?: getString(R.string.none)
        panel(getString(R.string.wallpaper), listOf(
            Choice(label(R.string.wp_picture, current)) { showWallpaperMenu(back = here) },
            Choice(label(R.string.wp_change, intervalName(prefs.wallpaperChangeMinutes))) {
                pick(getString(R.string.wp_change), wallpaperIntervals.map { intervalName(it) },
                    wallpaperIntervals.indexOf(prefs.wallpaperChangeMinutes), here, preview = false) { i ->
                    prefs.wallpaperChangeMinutes = wallpaperIntervals[i]
                    prefs.wallpaperChangedAt = System.currentTimeMillis()
                    scheduleWallpaperChange()
                }
            },
        ), onBack = ::showSettings)
    }

    /** Spotify «now playing»: connection status and where to show it */
    private fun showSpotifySettings() {
        val here = ::showSpotifySettings
        val connected = Spotify.isConnected(prefs)
        val choices = mutableListOf(
            Choice(getString(if (connected) R.string.spotify_connected else R.string.spotify_not_connected)) {
                if (!connected) Toast.makeText(this, R.string.spotify_howto, Toast.LENGTH_LONG).show()
                showSpotifySettings()
            },
            Choice(label(R.string.np_home, onOff(prefs.nowPlayingHome))) {
                pick(getString(R.string.np_home), listOf(onOff(true), onOff(false)), if (prefs.nowPlayingHome) 0 else 1, here,
                    preview = false) { i -> prefs.nowPlayingHome = i == 0 }
            },
            Choice(label(R.string.np_screensaver, onOff(prefs.nowPlayingScreensaver))) {
                pick(getString(R.string.np_screensaver), listOf(onOff(true), onOff(false)),
                    if (prefs.nowPlayingScreensaver) 0 else 1, here, preview = false) { i -> prefs.nowPlayingScreensaver = i == 0 }
            },
        )
        if (connected) choices += Choice(getString(R.string.spotify_disconnect)) {
            Spotify.disconnect(prefs)
            startNowPlaying()
            showSpotifySettings()
        }
        panel(getString(R.string.section_spotify), choices, onBack = ::showSettings)
    }

    private fun showTileSettings() {
        val here = ::showTileSettings
        panel(getString(R.string.section_tiles), listOf(
            Choice(label(R.string.card_size, nameFor(cardSizes, prefs.cardsPerRow))) {
                pick(getString(R.string.card_size), cardSizes.map { getString(it.first) },
                    cardSizes.indexOfFirst { it.second == prefs.cardsPerRow }, here) { i ->
                    prefs.cardsPerRow = cardSizes[i].second
                }
            },
            Choice(label(R.string.app_row_dock, onOff(prefs.appRowDock))) {
                pick(getString(R.string.app_row_dock), listOf(onOff(true), onOff(false)), if (prefs.appRowDock) 0 else 1, here) { i ->
                    prefs.appRowDock = i == 0
                }
            },
            Choice(label(R.string.glass_row_background, onOff(prefs.glassRowBackground))) {
                pick(getString(R.string.glass_row_background), listOf(onOff(true), onOff(false)),
                    if (prefs.glassRowBackground) 0 else 1, here) { i ->
                    prefs.glassRowBackground = i == 0
                }
            },
        ), onBack = ::showSettings)
    }

    private fun showFocusSettings() {
        val here = ::showFocusSettings
        val styles = FocusOutline.Style.entries
        panel(getString(R.string.section_focus), listOf(
            Choice(label(R.string.style, getString(prefs.focusOutline.label))) {
                pick(getString(R.string.style), styles.map { getString(it.label) }, styles.indexOf(prefs.focusOutline), here) { i ->
                    prefs.focusOutline = styles[i]
                }
            },
            Choice(label(R.string.outline_gap, nameFor(outlineGaps, prefs.outlineGap))) {
                pick(getString(R.string.outline_gap), outlineGaps.map { getString(it.first) },
                    outlineGaps.indexOfFirst { it.second == prefs.outlineGap }, here) { i ->
                    prefs.outlineGap = outlineGaps[i].second
                }
            },
        ), onBack = ::showSettings)
    }

    private fun showTextSettings() {
        val here = ::showTextSettings
        val fonts = Fonts.all(this)
        panel(getString(R.string.section_text), listOf(
            Choice(label(R.string.app_font, Fonts.name(this, prefs.appFont))) {
                pick(getString(R.string.app_font), fonts.map { it.name }, fonts.indexOfFirst { it.key == prefs.appFont }, here,
                    fonts = fonts.map { Fonts.typeface(this, it.key) }) { i ->
                    prefs.appFont = fonts[i].key
                }
            },
        ), onBack = ::showSettings)
    }

    private fun showClockSettings() {
        val here = ::showClockSettings
        val fonts = Fonts.all(this)
        val styles = listOf(getString(R.string.clock_plain), getString(R.string.clock_glass))
        panel(getString(R.string.section_clock), listOf(
            Choice(label(R.string.font, Fonts.name(this, prefs.clockFont))) {
                pick(getString(R.string.font), fonts.map { it.name }, fonts.indexOfFirst { it.key == prefs.clockFont }, here,
                    fonts = fonts.map { Fonts.typeface(this, it.key) }) { i ->
                    prefs.clockFont = fonts[i].key
                }
            },
            Choice(label(R.string.style, styles[if (prefs.clockGlass) 1 else 0])) {
                pick(getString(R.string.style), styles, if (prefs.clockGlass) 1 else 0, here) { i ->
                    prefs.clockGlass = i == 1
                }
            },
        ), onBack = ::showSettings)
    }

    private fun showScreensaverSettings() {
        val here = ::showScreensaverSettings
        val wait = screensaverWait()
        panel(getString(R.string.section_screensaver), listOf(
            Choice(label(R.string.ss_use, onOff(screensaverActive()))) {
                pick(getString(R.string.ss_use), listOf(onOff(true), onOff(false)), if (screensaverActive()) 0 else 1, here,
                    preview = false) { i -> setScreensaver(i == 0) }
            },
            Choice(label(R.string.ss_start_after, wait?.let { minutes(it) } ?: getString(R.string.unknown))) {
                pick(getString(R.string.ss_start_after), waitTimes.map { minutes(it) }, waitTimes.indexOf(wait ?: -1), here,
                    preview = false) { i -> setScreensaverWait(waitTimes[i]) }
            },
            Choice(label(R.string.ss_off_after, screenOffAfter()?.let { offName(it) } ?: getString(R.string.unknown))) {
                pick(getString(R.string.ss_off_after), offTimes.map { offName(it) }, offTimes.indexOf(screenOffAfter() ?: -3), here,
                    preview = false) { i -> setScreenOffAfter(offTimes[i]) }
            },
            Choice(label(R.string.ss_interval, seconds(prefs.screensaverSeconds))) {
                pick(getString(R.string.ss_interval), pictureTimes.map { seconds(it) },
                    pictureTimes.indexOf(prefs.screensaverSeconds), here) { i ->
                    prefs.screensaverSeconds = pictureTimes[i]
                }
            },
            Choice(label(R.string.ss_order, getString(if (prefs.screensaverShuffle) R.string.ss_shuffle else R.string.ss_in_order))) {
                pick(getString(R.string.ss_order), listOf(getString(R.string.ss_shuffle), getString(R.string.ss_in_order)),
                    if (prefs.screensaverShuffle) 0 else 1, here) { i ->
                    prefs.screensaverShuffle = i == 0
                }
            },
            Choice(label(R.string.ss_zoom, onOff(prefs.screensaverZoom))) {
                pick(getString(R.string.ss_zoom), listOf(onOff(true), onOff(false)), if (prefs.screensaverZoom) 0 else 1, here) { i ->
                    prefs.screensaverZoom = i == 0
                }
            },
            Choice(label(R.string.ss_clock, onOff(prefs.screensaverClock))) {
                pick(getString(R.string.ss_clock), listOf(onOff(true), onOff(false)), if (prefs.screensaverClock) 0 else 1, here) { i ->
                    prefs.screensaverClock = i == 0
                }
            },
            Choice(getString(R.string.ss_preview), LineIcon(LineIcon.PLAY)) {
                startSafely(Intent(this, ScreensaverPreview::class.java))
            },
        ), onBack = ::showSettings)
    }

    /**
     * Pick one value. With preview, the home screen updates as you scroll.
     * OK keeps the choice, Back undoes it; both take you back to the section you came from.
     */
    private fun pick(
        title: String, names: List<String>, start: Int, back: () -> Unit,
        preview: Boolean = true, fonts: List<Typeface>? = null, set: (Int) -> Unit,
    ) {
        val from = start.coerceAtLeast(0)
        panel(
            title = title,
            choices = names.mapIndexed { i, n ->
                Choice(if (i == start) "$n  ✓" else n, font = fonts?.getOrNull(i)) {
                    set(i)
                    refreshAppearance()
                    back()
                }
            },
            start = from,
            preview = if (preview && start >= 0) { i -> set(i); refreshAppearance() } else null,
            onBack = back,
        )
    }

    private fun refreshAppearance() {
        applyAppFont()
        applyClockStyle()
        buildTiles()
        startNowPlaying()
    }

    private fun loadAppFont() {
        regular = Fonts.typeface(this, prefs.appFont)
        semibold = Typeface.create(regular, 600, false)   // semibold version for headings
    }

    private fun applyAppFont() {
        loadAppFont()
        date.typeface = regular
        nowPlaying.setFont(regular)
    }

    private fun applyClockStyle() {
        clock.typeface = Fonts.typeface(this, prefs.clockFont)
        clock.glass = prefs.clockGlass
    }

    // ---------------------------------------------------------------- screensaver (system settings)

    private val ourScreensaver get() = ComponentName(this, Screensaver::class.java).flattenToString()

    private fun screensaverActive(): Boolean =
        Settings.Secure.getString(contentResolver, "screensaver_components")?.contains(ourScreensaver) == true

    /** Needs WRITE_SECURE_SETTINGS, which the Bluetooth button already got through adb */
    private fun setScreensaver(on: Boolean) {
        try {
            val cr = contentResolver
            if (on) {
                val current = Settings.Secure.getString(cr, "screensaver_components")
                if (current != null && !current.contains(ourScreensaver)) prefs.previousScreensaver = current
                Settings.Secure.putString(cr, "screensaver_components", ourScreensaver)
                Settings.Secure.putInt(cr, "screensaver_enabled", 1)
            } else {
                Settings.Secure.putString(cr, "screensaver_components", prefs.previousScreensaver ?: "")
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.missing_secure_settings, packageName), Toast.LENGTH_LONG).show()
        }
    }

    /** The first version's screensaver class had a Norwegian name; point the system to the new one */
    private fun migrateScreensaverComponent() {
        val old = "$packageName/.Skjermsparer"
        try {
            val current = Settings.Secure.getString(contentResolver, "screensaver_components") ?: return
            if (current.contains(old)) {
                Settings.Secure.putString(contentResolver, "screensaver_components", current.replace(old, ourScreensaver))
            }
        } catch (e: Exception) {
            // no permission: nothing to do
        }
    }

    /** Minutes before the screensaver starts (Android TV uses «screen_off_timeout» for this) */
    private fun screensaverWait(): Int? = try {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) / 60_000
    } catch (e: Exception) {
        null
    }

    /** Needs permission to change system settings (once through adb, see README) */
    private fun setScreensaverWait(minutes: Int) {
        if (!Settings.System.canWrite(this)) {
            Toast.makeText(this, getString(R.string.missing_write_settings, packageName), Toast.LENGTH_LONG).show()
            return
        }
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, minutes * 60_000)
        // The turn-off time counts from when the screensaver starts, so move it along
        if (prefs.screenOffAfter >= 0) setScreenOffAfter(prefs.screenOffAfter)
    }

    /** How long the screensaver runs before the TV turns off: -1 = never */
    private val offTimes = listOf(-1, 15, 30, 60, 120, 240)

    private fun offName(m: Int) = if (m < 0) getString(R.string.never) else minutes(m)

    /**
     * Minutes the screensaver runs before the TV turns off (-1 = never), read from the system.
     * Android counts «sleep_timeout» from the last button press, and the screensaver starts at
     * «screen_off_timeout», so the difference is how long the screensaver is shown.
     */
    private fun screenOffAfter(): Int? = try {
        val sleep = Settings.Secure.getInt(contentResolver, "sleep_timeout", -1)
        if (sleep <= 0) -1 else {
            val wait = Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
            ((sleep - wait) / 60_000).coerceAtLeast(0)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Turns the Chromecast off after the screensaver has run this many minutes (-1 = never).
     * With HDMI-CEC on, the TV turns off with it. Needs WRITE_SECURE_SETTINGS (see README).
     */
    private fun setScreenOffAfter(minutes: Int) {
        try {
            val wait = Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
            val sleep = if (minutes < 0) -1 else wait + minutes * 60_000
            Settings.Secure.putInt(contentResolver, "sleep_timeout", sleep)
            prefs.screenOffAfter = minutes
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.missing_secure_settings, packageName), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            toast(R.string.not_available)
        }
    }

    private fun showAllApps() {
        panel(getString(R.string.all_apps), allApps.map { app -> Choice(app.name, app.icon) { startApp(app.pkg) } })
    }

    private fun showAddApp() {
        val fav = favorites().map { it.pkg }.toMutableList()
        val others = allApps.filter { it.pkg !in fav }
        if (others.isEmpty()) {
            toast(R.string.all_on_home)
            return
        }
        panel(getString(R.string.add_app), others.map { app ->
            Choice(app.name, app.icon) {
                fav.add(app.pkg)
                prefs.favorites = fav
                lastFocus = app.pkg
            }
        })
    }

    /** The wallpaper menu: the wallpaper changes as you scroll, OK keeps it, Back undoes it */
    private fun showWallpaperMenu(back: (() -> Unit)? = null) {
        val all = wallpaperChoices()
        if (all.isEmpty()) {
            Toast.makeText(this, R.string.no_pictures, Toast.LENGTH_LONG).show()
            return
        }
        val start = all.indexOfFirst { it.first == currentWallpaper() }.coerceAtLeast(0)
        panel(
            title = getString(R.string.wallpaper),
            choices = all.map { (key, name) ->
                Choice(name, wallpaperIcon(key)) {
                    prefs.wallpaper = key
                    prefs.wallpaperChangedAt = System.currentTimeMillis()   // a new picture starts the timer again
                    scheduleWallpaperChange()
                    back?.invoke()
                }
            },
            start = start,
            preview = { i -> showWallpaper(all[i].first) },
            onBack = back,
        )
    }

    /**
     * A side panel on the right in the same style as the cards. Back closes it.
     * With preview, it's called for every choice you scroll to, and with the starting choice again if you cancel.
     */
    private fun panel(
        title: String, choices: List<Choice>, start: Int = 0, preview: ((Int) -> Unit)? = null,
        onBack: (() -> Unit)? = null,   // Back takes you to the previous menu
    ) {
        val dialog = Dialog(this, android.R.style.Theme_Material_Dialog_NoActionBar)
        val iconSize = px(48)

        val list = ListView(this).apply {
            divider = null
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            selector = GradientDrawable().apply {
                cornerRadius = px(Config.CORNER_RADIUS).toFloat()
                setColor(Config.GLASS_FOCUS)
            }
            adapter = object : BaseAdapter() {
                override fun getCount() = choices.size
                override fun getItem(position: Int): Any = choices[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, reuse: View?, parent: ViewGroup?): View {
                    val t = reuse as? TextView ?: text(px(30), 0xE6FFFFFF.toInt(), regular).apply {
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER_VERTICAL
                        minHeight = px(88)
                        setPadding(px(24), 0, px(24), 0)
                        compoundDrawablePadding = px(24)
                    }
                    t.text = choices[position].name
                    t.typeface = choices[position].font ?: regular   // font lists are shown in their own font
                    // A copy of the icon, so it doesn't disturb the icon on the home screen
                    val icon = choices[position].icon?.let { it.constantState?.newDrawable()?.mutate() ?: it }
                    icon?.setBounds(0, 0, iconSize, iconSize)
                    t.setCompoundDrawablesRelative(icon, null, null, null)
                    return t
                }
            }
            setOnItemClickListener { _, _, position, _ ->
                dialog.dismiss()
                choices[position].action()
                buildTiles()
            }
        }

        if (preview != null) {
            list.setSelection(start)
            list.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) = preview(position)
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        dialog.setOnCancelListener {
            preview?.invoke(start)
            onBack?.invoke()
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(48), px(92), px(48), px(48))
            addView(text(px(40), Color.WHITE, semibold).apply {
                this.text = title
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(px(24), 0, px(24), 0)
            })
            addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = px(36) })
        }

        dialog.setContentView(box)
        dialog.window?.apply {
            // While previewing, the panel is solid and the screen behind isn't dimmed
            setBackgroundDrawable(ColorDrawable(if (preview != null) 0xFF161113.toInt() else 0xF7161113.toInt()))
            setLayout(px(640), MATCH)
            setGravity(Gravity.END)
            setDimAmount(if (preview != null) 0f else 0.5f)
        }
        dialog.show()
    }

    // ---------------------------------------------------------------- helpers

    /** Measurements from the 1920x1080 design, scaled to the TV's actual screen */
    private fun px(value: Int): Int = (value * scale).roundToInt()

    private fun text(size: Int, color: Int, font: Typeface) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size.toFloat())
        setTextColor(color)
        typeface = font
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun at(x: Int, y: Int, w: Int, h: Int) =
        FrameLayout.LayoutParams(w, h).apply {
            leftMargin = x
            topMargin = y
        }

    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
}
