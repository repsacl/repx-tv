package no.repsac.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The screensaver: shows your pictures (assets/wallpapers/) one at a time, with a soft crossfade,
 * slow zoom and the clock in the bottom left. Without pictures of your own, only the clock is shown.
 */
class ScreensaverView(context: Context) : FrameLayout(context) {

    private val prefs = Prefs(context)
    private val handler = Handler(Looper.getMainLooper())
    private var worker: ExecutorService? = null
    private val dateTime = DateTime(context)
    private val scale = resources.displayMetrics.widthPixels / 1920f

    // Two pictures on top of each other, so the new one can fade in over the old one
    private val layers = Array(2) {
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
    }
    private var front = 0
    private val date: TextView
    private val clock: GlassText
    private val clockColumn: LinearLayout
    private val nowPlaying = NowPlayingView(context, scale, Fonts.typeface(context, prefs.appFont))
    private val nowPlayingPoller = NowPlayingPoller(context) { np, art -> nowPlaying.show(np, art) }

    private var keys: List<String> = emptyList()   // «image:file»
    private var index = -1

    init {
        setBackgroundColor(Color.BLACK)
        layers.forEach { addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }

        date = text(30, 0xCCFFFFFF.toInt()).apply { typeface = Fonts.typeface(context, prefs.appFont) }
        clock = GlassText(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(150).toFloat())
            setTextColor(Color.WHITE)
            if (!prefs.clockGlass) setShadowLayer(px(20).toFloat(), 0f, 0f, 0x88000000.toInt())
            glass = prefs.clockGlass
            letterSpacing = -0.028f
            fontFeatureSettings = "tnum"
            includeFontPadding = false
            typeface = Fonts.typeface(context, prefs.clockFont)
        }
        clockColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(date)
            addView(clock, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(-16) })
            visibility = if (prefs.screensaverClock) VISIBLE else GONE
        }
        addView(clockColumn, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = px(96)
            bottomMargin = px(80)
        })
        // What's playing on Spotify, in the bottom right corner
        addView(nowPlaying, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = px(96)
            bottomMargin = px(80)
        })
    }

    fun start() {
        stop()
        worker = Executors.newSingleThreadExecutor()
        keys = Images.files(context).map { "image:$it" }   // no pictures: just the clock on black
        if (prefs.screensaverShuffle) keys = keys.shuffled()
        index = -1
        updateClock.run()
        nextPicture.run()
        if (prefs.nowPlayingScreensaver) nowPlayingPoller.start()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        nowPlayingPoller.stop()
        worker?.shutdownNow()
        worker = null
    }

    private val updateClock = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = dateTime.time(now)
            date.text = dateTime.date(now)
            handler.postDelayed(this, DateTime.untilNextMinute(now))
        }
    }

    private val nextPicture = object : Runnable {
        override fun run() {
            if (keys.isEmpty()) return
            index = (index + 1) % keys.size
            val key = keys[index]
            val ctx = context
            worker?.execute {
                val bitmap = Images.wallpaper(ctx, key)
                val blurred = if (prefs.clockGlass) Images.blurred(ctx, key) else null
                post {
                    if (bitmap != null) {
                        showPicture(bitmap)
                        clock.setBackdrop(blurred)
                    }
                }
            }
            handler.postDelayed(this, prefs.screensaverSeconds * 1000L)
        }
    }

    private fun showPicture(bitmap: Bitmap) {
        val incoming = layers[1 - front]
        val outgoing = layers[front]
        front = 1 - front

        incoming.animate().cancel()
        incoming.setImageBitmap(bitmap)
        incoming.alpha = 0f
        incoming.scaleX = 1f
        incoming.scaleY = 1f
        incoming.bringToFront()
        clockColumn.bringToFront()
        nowPlaying.bringToFront()

        val fade = 1500L
        incoming.animate().alpha(1f).setStartDelay(0).setDuration(fade).setInterpolator(DecelerateInterpolator()).start()
        // The old picture is hidden only once the new one is fully visible
        outgoing.animate().alpha(0f).setStartDelay(fade).setDuration(10).start()

        // Slow zoom («Ken Burns») towards a random point, over the whole time the picture is shown
        if (prefs.screensaverZoom) {
            incoming.pivotX = width * Random.nextFloat()
            incoming.pivotY = height * Random.nextFloat()
            incoming.animate().scaleX(1.08f).scaleY(1.08f)
                .setStartDelay(0)
                .setDuration(prefs.screensaverSeconds * 1000L + fade)
                .setInterpolator(LinearInterpolator())
                .start()
        }

        // Move the clock a little each time, so it doesn't burn into the screen
        clockColumn.animate()
            .translationX(px(Random.nextInt(-24, 24)).toFloat())
            .translationY(px(Random.nextInt(-24, 24)).toFloat())
            .setDuration(fade).start()
    }

    private fun text(size: Int, color: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size).toFloat())
        setTextColor(color)
        setShadowLayer(px(20).toFloat(), 0f, 0f, 0x88000000.toInt())
    }

    private fun px(value: Int) = (value * scale).roundToInt()
}

/** The system screensaver (an Android «Dream»). Turned on from Settings → Screensaver in the launcher. */
class Screensaver : DreamService() {

    private var view: ScreensaverView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false   // any button on the remote ends the screensaver
        isFullscreen = true
        view = ScreensaverView(this).also { setContentView(it) }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        view?.start()
    }

    override fun onDreamingStopped() {
        view?.stop()
        super.onDreamingStopped()
    }
}

/** Preview from the settings. Press any button to go back. */
class ScreensaverPreview : Activity() {

    private lateinit var view: ScreensaverView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = ScreensaverView(this)
        setContentView(view)
    }

    override fun onResume() {
        super.onResume()
        view.start()
    }

    override fun onPause() {
        view.stop()
        super.onPause()
    }

    // The OK press that opened the preview shouldn't close it again right away
    private var pressedHere = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) pressedHere = true
        if (event.action == KeyEvent.ACTION_UP && pressedHere) finish()
        return true
    }
}
