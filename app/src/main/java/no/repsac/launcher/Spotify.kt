package no.repsac.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * «Now playing» from your Spotify account, on any of your devices (phone, computer, the TV …).
 *
 * Uses the Spotify Web API with your own (free) Spotify developer app. You log in once from a
 * computer with tools/spotify-login.py, which hands a refresh token to the TV over adb.
 * The token is only stored on the TV, never in this project.
 */
object Spotify {

    class NowPlaying(
        val title: String,
        val artist: String,
        val device: String?,
        val isPlaying: Boolean,
        val progressMs: Long,
        val durationMs: Long,
        val artUrl: String?,
    )

    private var accessToken: String? = null
    private var expiresAt = 0L
    private val artCache = LinkedHashMap<String, Bitmap>()

    fun isConnected(prefs: Prefs) = !prefs.spotifyClientId.isNullOrBlank() && !prefs.spotifyRefreshToken.isNullOrBlank()

    fun disconnect(prefs: Prefs) {
        prefs.spotifyRefreshToken = null
        prefs.spotifyClientId = null
        synchronized(this) { accessToken = null }
    }

    /** What's playing right now, or null if nothing (or not connected). Call from a background thread. */
    fun nowPlaying(prefs: Prefs): NowPlaying? {
        if (!isConnected(prefs)) return null
        var token = token(prefs) ?: return null
        var (code, body) = get("https://api.spotify.com/v1/me/player?additional_types=episode", token)
        if (code == 401) {   // the access token ran out early: get a new one and try once more
            synchronized(this) { accessToken = null }
            token = token(prefs) ?: return null
            val retry = get("https://api.spotify.com/v1/me/player?additional_types=episode", token)
            code = retry.first; body = retry.second
        }
        if (code != 200 || body.isNullOrBlank()) return null   // 204 = nothing is playing

        val json = JSONObject(body)
        val item = json.optJSONObject("item") ?: return null
        val artists = item.optJSONArray("artists")
        val artist = if (artists != null) {
            (0 until artists.length()).joinToString(", ") { artists.getJSONObject(it).optString("name") }
        } else {
            item.optJSONObject("show")?.optString("name") ?: ""   // podcast episode
        }
        val images = item.optJSONObject("album")?.optJSONArray("images")
            ?: item.optJSONArray("images")
            ?: item.optJSONObject("show")?.optJSONArray("images")
        // Spotify lists the biggest first (640 px); the second one (300 px) is plenty
        val artUrl = images?.let { if (it.length() > 1) it.getJSONObject(1) else it.optJSONObject(0) }?.optString("url")

        return NowPlaying(
            title = item.optString("name"),
            artist = artist,
            device = json.optJSONObject("device")?.optString("name")?.takeIf { it.isNotBlank() },
            isPlaying = json.optBoolean("is_playing"),
            progressMs = json.optLong("progress_ms"),
            durationMs = item.optLong("duration_ms"),
            artUrl = artUrl,
        )
    }

    /** Album art, cached. Call from a background thread. */
    fun art(url: String): Bitmap? {
        synchronized(artCache) { artCache[url]?.let { return it } }
        val bitmap = try {
            (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 5000
                readTimeout = 5000
                inputStream.use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Exception) {
            null
        } ?: return null
        synchronized(artCache) {
            artCache[url] = bitmap
            while (artCache.size > 8) artCache.remove(artCache.keys.first())
        }
        return bitmap
    }

    /** A valid access token, using the saved refresh token when needed */
    @Synchronized
    private fun token(prefs: Prefs): String? {
        accessToken?.let { if (System.currentTimeMillis() < expiresAt) return it }
        val clientId = prefs.spotifyClientId ?: return null
        val refresh = prefs.spotifyRefreshToken ?: return null
        val form = "grant_type=refresh_token" +
            "&refresh_token=" + URLEncoder.encode(refresh, "UTF-8") +
            "&client_id=" + URLEncoder.encode(clientId, "UTF-8")
        val (code, body) = post("https://accounts.spotify.com/api/token", form)
        if (code != 200 || body == null) return null
        val json = JSONObject(body)
        accessToken = json.getString("access_token")
        expiresAt = System.currentTimeMillis() + (json.optLong("expires_in", 3600) - 60) * 1000
        // Spotify may hand out a new refresh token each time; the old one then stops working
        json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { prefs.spotifyRefreshToken = it }
        return accessToken
    }

    private fun get(url: String, token: String): Pair<Int, String?> = request(url) {
        setRequestProperty("Authorization", "Bearer $token")
    }

    private fun post(url: String, form: String): Pair<Int, String?> = request(url) {
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        outputStream.use { it.write(form.toByteArray()) }
    }

    private fun request(url: String, setup: HttpURLConnection.() -> Unit): Pair<Int, String?> = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        c.setup()
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect()
        code to body
    } catch (e: Exception) {
        -1 to null
    }
}

/**
 * Receives the login from tools/spotify-login.py. Protected by the DUMP permission, which only
 * adb (the shell) has, so other apps on the TV can't send it.
 */
class SpotifyLoginReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val clientId = intent.getStringExtra("client_id")
        val refreshToken = intent.getStringExtra("refresh_token")
        if (clientId.isNullOrBlank() || refreshToken.isNullOrBlank()) {
            resultData = "missing client_id or refresh_token"
            return
        }
        val prefs = Prefs(context)
        Spotify.disconnect(prefs)
        prefs.spotifyClientId = clientId
        prefs.spotifyRefreshToken = refreshToken
        resultCode = Activity.RESULT_OK
        resultData = "saved"
    }
}

/** Asks Spotify what's playing every few seconds while started, and reports it on the main thread. */
class NowPlayingPoller(private val context: Context, private val onUpdate: (Spotify.NowPlaying?, Bitmap?) -> Unit) {

    private val prefs = Prefs(context)
    private val handler = Handler(Looper.getMainLooper())
    private var worker: ExecutorService? = null

    fun start() {
        stop()
        if (!Spotify.isConnected(prefs)) {
            onUpdate(null, null)
            return
        }
        worker = Executors.newSingleThreadExecutor()
        tick.run()
    }

    fun stop() {
        handler.removeCallbacks(tick)
        worker?.shutdownNow()
        worker = null
    }

    private val tick = object : Runnable {
        override fun run() {
            worker?.execute {
                val np = try { Spotify.nowPlaying(prefs) } catch (e: Exception) { null }
                val art = np?.artUrl?.let { Spotify.art(it) }
                handler.post { if (worker != null) onUpdate(np, art) }
            }
            handler.postDelayed(this, 5000)
        }
    }
}

/**
 * The «now playing» card: album art, title, artist, which device it's playing on, and a progress bar.
 * Shown only while music is playing; fades in and out. Sizes are in 1920x1080 pixels, scaled by «scale».
 */
class NowPlayingView(context: Context, private val scale: Float, font: Typeface) : FrameLayout(context) {

    private val art = ImageView(context)
    private val label: TextView
    private val title: TextView
    private val artist: TextView
    private val bar = View(context)
    private val barTrack = FrameLayout(context)
    private val handler = Handler(Looper.getMainLooper())

    private var current: Spotify.NowPlaying? = null
    private var receivedAt = 0L

    init {
        alpha = 0f
        visibility = GONE
        background = GradientDrawable().apply {
            cornerRadius = px(26).toFloat()
            setColor(0x8C000000.toInt())
            setStroke(max(1, px(2)), 0x1FFFFFFF)
        }
        setPadding(px(18), px(18), px(26), px(18))

        art.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = px(14).toFloat()
                setColor(0x33FFFFFF)
            }
            clipToOutline = true
        }
        label = text(19, 0xA8FFFFFF.toInt(), font).apply { letterSpacing = 0.08f }
        title = text(28, Color.WHITE, Typeface.create(font, 600, false))
        artist = text(22, 0xCCFFFFFF.toInt(), font)
        barTrack.background = GradientDrawable().apply {
            cornerRadius = px(2).toFloat()
            setColor(0x33FFFFFF)
        }
        bar.background = GradientDrawable().apply {
            cornerRadius = px(2).toFloat()
            setColor(0xE6FFFFFF.toInt())
        }
        barTrack.addView(bar, LayoutParams(0, LayoutParams.MATCH_PARENT))

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label)
            addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4) })
            addView(artist, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(2) })
            addView(barTrack, LinearLayout.LayoutParams(-1, px(4)).apply { topMargin = px(14) })
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(art, LinearLayout.LayoutParams(px(100), px(100)))
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = px(20) })
        }
        addView(row, LayoutParams(px(520), -2))
    }

    /** Shows the song, or hides the card when nothing is playing (or the music is paused) */
    fun show(np: Spotify.NowPlaying?, artwork: Bitmap?) {
        val playing = np != null && np.isPlaying
        if (!playing) {
            current = null
            handler.removeCallbacks(progressTick)
            if (visibility == VISIBLE) animate().alpha(0f).setDuration(400).withEndAction { visibility = GONE }.start()
            return
        }
        np!!
        val changed = current?.title != np.title || current?.artist != np.artist
        current = np
        receivedAt = System.currentTimeMillis()
        title.text = np.title
        artist.text = np.artist
        label.text = (np.device?.let { context.getString(R.string.np_playing_on, it) }
            ?: context.getString(R.string.np_now_playing)).uppercase()
        if (changed || art.drawable == null) art.setImageBitmap(artwork)
        updateProgress()
        handler.removeCallbacks(progressTick)
        handler.postDelayed(progressTick, 1000)
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            animate().alpha(1f).setDuration(500).start()
        }
    }

    fun setFont(font: Typeface) {
        label.typeface = font
        artist.typeface = font
        title.typeface = Typeface.create(font, 600, false)
    }

    // Moves the progress bar every second between the updates from Spotify
    private val progressTick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 1000)
        }
    }

    private fun updateProgress() {
        val np = current ?: return
        if (np.durationMs <= 0) return
        val pos = np.progressMs + (System.currentTimeMillis() - receivedAt)
        val fraction = (pos.toFloat() / np.durationMs).coerceIn(0f, 1f)
        barTrack.post {
            bar.layoutParams = bar.layoutParams.apply { width = (barTrack.width * fraction).roundToInt() }
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(progressTick)
        super.onDetachedFromWindow()
    }

    private fun text(size: Int, color: Int, font: Typeface) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size).toFloat())
        setTextColor(color)
        typeface = font
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun px(value: Int) = (value * scale).roundToInt()
}
