package com.example.firewallpapers

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Preferencias compartidas entre la pantalla de ajustes (MainActivity)
 * y el protector de pantalla (WallpaperDreamService).
 */
object Prefs {
    private const val FILE = "fire_wallpapers_prefs"
    private const val KEY_CATEGORY = "category"
    private const val KEY_INTERVAL = "interval"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun category(ctx: Context): String {
        val saved = prefs(ctx).getString(KEY_CATEGORY, "Anime") ?: "Anime"
        return if (saved in CATEGORY_QUERIES || saved == RANDOM_CATEGORY) saved else "Anime"
    }

    fun interval(ctx: Context): Int = prefs(ctx).getInt(KEY_INTERVAL, 30).coerceAtLeast(5)

    fun save(ctx: Context, category: String, intervalSeconds: Int) {
        prefs(ctx).edit()
            .putString(KEY_CATEGORY, category)
            .putInt(KEY_INTERVAL, intervalSeconds)
            .apply()
    }
}

/**
 * Protector de pantalla ("Daydream") de Android. Es la única forma de que el sistema
 * lo lance solo cuando la TV queda inactiva. Usa Views clásicas (no Compose) porque
 * un DreamService no es un LifecycleOwner y así es mucho más estable en Fire OS.
 */
class WallpaperDreamService : DreamService() {

    private var scope: CoroutineScope? = null
    private lateinit var imageA: ImageView
    private lateinit var imageB: ImageView
    private lateinit var timeText: TextView
    private lateinit var dateText: TextView
    private var showingA = false

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        CrashLogger.install(applicationContext)
        isInteractive = false   // cualquier tecla del control lo cierra
        isFullscreen = true
        isScreenBright = true

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        fun newImage() = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        imageA = newImage()
        imageB = newImage()
        root.addView(imageA)
        root.addView(imageB)

        timeText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 42f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }
        dateText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 16f
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }
        val clock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(32), dp(32), dp(32))
            addView(timeText)
            addView(dateText)
        }
        root.addView(
            clock,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )

        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = newScope
        newScope.launch { runClock() }
        newScope.launch { runSlideshow() }
    }

    override fun onDreamingStopped() {
        scope?.cancel()
        scope = null
        super.onDreamingStopped()
    }

    private suspend fun runClock() {
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, d 'de' MMMM", Locale.getDefault())
        while (currentCoroutineContext().isActive) {
            val now = Date()
            timeText.text = timeFormat.format(now)
            dateText.text = dateFormat.format(now).replaceFirstChar { it.uppercase() }
            delay(1000L)
        }
    }

    private suspend fun runSlideshow() {
        var urls = emptyList<String>()
        var index = 0

        while (currentCoroutineContext().isActive) {
            if (index >= urls.size) {
                urls = try {
                    fetchWallpaperUrls(Prefs.category(this))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                index = 0
                if (urls.isEmpty()) {
                    delay(15_000L)   // sin internet o sin resultados: reintenta luego
                    continue
                }
            }

            val drawable = loadDrawable(urls[index++])
            if (drawable == null) {
                delay(1000L)
                continue
            }
            showDrawable(drawable)
            delay(Prefs.interval(this) * 1000L)
        }
    }

    private suspend fun loadDrawable(url: String): Drawable? {
        val request = ImageRequest.Builder(this)
            .data(url)
            .size(1920, 1080)
            .build()
        return (imageLoader.execute(request) as? SuccessResult)?.drawable
    }

    private fun showDrawable(drawable: Drawable) {
        val incoming = if (showingA) imageB else imageA
        val outgoing = if (showingA) imageA else imageB
        incoming.setImageDrawable(drawable)
        incoming.animate().alpha(1f).setDuration(1000L).start()
        outgoing.animate().alpha(0f).setDuration(1000L).start()
        showingA = !showingA
    }
}