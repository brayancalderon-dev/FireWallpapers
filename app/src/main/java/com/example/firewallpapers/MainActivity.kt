package com.example.firewallpapers

import android.app.WallpaperManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.content.Context
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.HttpException
import retrofit2.converter.gson.GsonConverterFactory
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────────────────────────────────────────
//  CONFIGURACIÓN
// ─────────────────────────────────────────────────────────────
object ApiConfig {
    const val WALLHAVEN_API_KEY = "aRHd6dk2A77cUPRSo4IGm41o4Mzps03R"
}

// ─────────────────────────────────────────────────────────────
//  API DE WALLHAVEN
// ─────────────────────────────────────────────────────────────
data class WallhavenResponse(val data: List<WallhavenItem>)
data class WallhavenItem(val path: String)

interface WallpaperApi {
    @GET("api/v1/search")
    suspend fun searchWallpapers(
        @Query("apikey") apiKey: String? = null,
        @Query("q") query: String,
        @Query("categories") categories: String = "110",
        @Query("purity") purity: String = "100",
        @Query("ratios") ratios: String = "16x9",
        @Query("atleast") atleast: String = "1920x1080",
        @Query("sorting") sorting: String = "random"
    ): WallhavenResponse
}

object ApiClient {
    val service: WallpaperApi = Retrofit.Builder()
        .baseUrl("https://wallhaven.cc/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(WallpaperApi::class.java)
}

/**
 * Categoría -> (términos de búsqueda, código de categorías de Wallhaven).
 * Wallhaven no soporta "OR", así que se elige un término al azar en cada carga.
 */
val CATEGORY_QUERIES: Map<String, Pair<List<String>, String>> = mapOf(
    "Anime" to (listOf("anime", "anime landscape", "manga", "anime city") to "010"),
    "Películas" to (listOf("movie", "cinema", "sci-fi movie", "hollywood") to "100"),
    "Series" to (listOf("tv series", "tv show", "television", "netflix") to "100"),
    "Videojuegos" to (listOf("video games", "gaming", "cyberpunk 2077", "retro games") to "100"),
    "Cantantes" to (listOf("concert", "music", "singer", "guitar") to "100"),
    "Paisajes" to (listOf("nature", "landscape", "mountains", "sunset", "forest") to "110"),
    "Animales" to (listOf("wildlife", "animals", "tiger", "wolf", "eagle") to "100")
)

/** Categoría especial: mezcla fondos de TODAS las demás en cada carga. */
const val RANDOM_CATEGORY = "Al Azar"

/** Temas extra que también participan en "Al Azar". */
private val EXTRA_QUERY: Pair<List<String>, String> =
    listOf("space", "architecture", "cyberpunk", "city", "abstract") to "110"

private suspend fun searchOne(terms: List<String>, code: String): List<String> {
    val query = terms.random()
    val key = ApiConfig.WALLHAVEN_API_KEY.takeIf { it.isNotBlank() }
    val response = try {
        ApiClient.service.searchWallpapers(apiKey = key, query = query, categories = code)
    } catch (e: HttpException) {
        // Key inválida o revocada (401): reintenta sin key, que basta para contenido SFW
        if (e.code() == 401 && key != null) {
            ApiClient.service.searchWallpapers(apiKey = null, query = query, categories = code)
        } else {
            throw e
        }
    }
    return response.data.map { it.path }
}

/** Traduce el error técnico a un mensaje que explique qué pasó. */
fun describeError(e: Throwable): String = when (e) {
    is HttpException -> "Wallhaven respondió con el error ${e.code()}"
    is UnknownHostException -> "Sin internet o DNS: no encuentra wallhaven.cc"
    is SocketTimeoutException -> "Se agotó el tiempo de espera al conectar"
    is SSLException -> "Error de seguridad SSL. Revisa la fecha y hora del Fire TV (${e.message})"
    is ConnectException -> "No se pudo conectar con wallhaven.cc"
    is SecurityException -> "Falta el permiso INTERNET en el AndroidManifest.xml"
    else -> "${e::class.java.simpleName}: ${e.message}"
}

/**
 * Devuelve las URLs de una categoría. Para "Al Azar" consulta 4 categorías distintas
 * (elegidas al azar entre todas) en paralelo y mezcla los resultados.
 */
suspend fun fetchWallpaperUrls(category: String): List<String> {
    if (category != RANDOM_CATEGORY) {
        val (terms, code) = CATEGORY_QUERIES.getValue(category)
        return searchOne(terms, code)
    }

    val pool = CATEGORY_QUERIES.values.toList() + listOf(EXTRA_QUERY)
    val picks = pool.shuffled().take(4)

    val results = coroutineScope {
        picks.map { (terms, code) -> async { runCatching { searchOne(terms, code) } } }.awaitAll()
    }

    val urls = results.flatMap { it.getOrDefault(emptyList()) }.distinct().shuffled()
    if (urls.isEmpty()) {
        // Si fallaron todas las consultas, propaga el error para mostrar el aviso
        results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
    }
    return urls
}

// ─────────────────────────────────────────────────────────────
//  UI
// ─────────────────────────────────────────────────────────────
/**
 * Guarda en un archivo el error que cierre la app, para poder mostrarlo en pantalla
 * la próxima vez que se abra (sin necesitar ADB ni una PC).
 */
object CrashLogger {
    private const val FILE = "last_crash.txt"
    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(ctx.filesDir, FILE).writeText("Hilo: ${thread.name}\n$sw")
            } catch (_: Throwable) {
                // Si no se puede guardar, no hacemos nada más
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun consume(ctx: Context): String? {
        val file = File(ctx.filesDir, FILE)
        if (!file.exists()) return null
        val text = try { file.readText() } catch (_: Throwable) { null }
        file.delete()
        if (text == null) return null
        return if (text.length > 3500) text.take(1500) + "\n[...]\n" + text.takeLast(2000) else text
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLogger.install(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val lastCrash = CrashLogger.consume(applicationContext)
        if (lastCrash != null) showCrashScreen(lastCrash) else startApp()
    }

    private fun startApp() {
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                FireTvApp()
            }
        }
    }

    /** Pantalla simple (sin Compose) con el error de la vez anterior. OK/Seleccionar continúa. */
    private fun showCrashScreen(report: String) {
        val pad = (32 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            this.text = "La app se cerró la vez anterior. Toma una foto de esta pantalla " +
                    "y envíala para poder corregirlo.\n\n$report\n\n" +
                    ">>> Presiona OK / Seleccionar para continuar <<<"
            textSize = 13f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(pad, pad, pad, pad)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setOnClickListener { startApp() }
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(text)
        })
        text.requestFocus()
    }
}

private class ToastHolder { var toast: Toast? = null }

@Composable
fun FireTvApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val screenFocusRequester = remember { FocusRequester() }
    val toastHolder = remember { ToastHolder() }

    val categories = CATEGORY_QUERIES.keys.toList() + RANDOM_CATEGORY
    val intervals = listOf(15 to "15 seg", 30 to "30 seg", 60 to "1 min", 300 to "5 min")

    var selectedCategory by remember { mutableStateOf(Prefs.category(context)) }
    var intervalSeconds by remember { mutableIntStateOf(Prefs.interval(context)) }
    var showMenu by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }

    var wallpaperUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Filas del menú: 0 = categorías, 1 = intervalo, 2 = aplicar
    var focusedRow by remember { mutableIntStateOf(0) }
    var focusedCol by remember { mutableIntStateOf(0) }

    var currentTime by remember { mutableStateOf("") }
    var currentDate by remember { mutableStateOf("") }

    // Reloj (los formatters se crean una sola vez)
    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, d 'de' MMMM", Locale.getDefault())
        while (true) {
            val date = Date()
            currentTime = timeFormat.format(date)
            currentDate = dateFormat.format(date).replaceFirstChar { it.uppercase() }
            delay(1000L)
        }
    }

    fun showToast(message: String) {
        toastHolder.toast?.cancel()
        toastHolder.toast = Toast.makeText(context, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    fun loadWallpapers(category: String) {
        loadJob?.cancel()
        loadJob = scope.launch {
            isLoading = true
            try {
                val urls = fetchWallpaperUrls(category)
                if (urls.isNotEmpty()) {
                    wallpaperUrls = urls
                    currentIndex = 0
                    statusMessage = null
                } else {
                    showToast("No se encontraron resultados para $category")
                    if (wallpaperUrls.isNotEmpty()) currentIndex = 0
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val reason = describeError(e)
                statusMessage = reason
                showToast("No se pudieron cargar fondos: $reason")
                // Si ya hay fondos, vuelve al inicio de la lista en vez de quedarse trabado
                if (wallpaperUrls.isNotEmpty()) currentIndex = 0
            } finally {
                if (isActive) {
                    isLoading = false
                    reloadTick++
                }
            }
        }
    }

    fun applySystemWallpaper(url: String?) {
        if (url == null) return
        scope.launch(Dispatchers.IO) {
            try {
                val wm = WallpaperManager.getInstance(context)
                val supported = Build.VERSION.SDK_INT < 24 ||
                        (wm.isWallpaperSupported && wm.isSetWallpaperAllowed)
                if (!supported) {
                    withContext(Dispatchers.Main) {
                        showToast("Este dispositivo no permite cambiar el fondo desde apps")
                    }
                    return@launch
                }

                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(1920, 1080)
                    .allowHardware(false)   // necesario para poder obtener un Bitmap
                    .build()
                val result = context.imageLoader.execute(request)
                if (result !is SuccessResult) throw IllegalStateException("No se pudo descargar la imagen")

                wm.setBitmap(result.drawable.toBitmap())
                withContext(Dispatchers.Main) { showToast("¡Fondo aplicado al Fire TV!") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showToast("Error al fijar fondo") }
            }
        }
    }

    // Carga inicial y cada vez que cambia la categoría
    LaunchedEffect(selectedCategory) {
        loadWallpapers(selectedCategory)
    }

    // Guarda los ajustes para que el protector de pantalla (DreamService) use los mismos
    LaunchedEffect(selectedCategory, intervalSeconds) {
        Prefs.save(context, selectedCategory, intervalSeconds)
    }

    // Pre-carga la siguiente imagen para que la transición no salga en negro
    LaunchedEffect(wallpaperUrls, currentIndex) {
        wallpaperUrls.getOrNull(currentIndex + 1)?.let { next ->
            context.imageLoader.enqueue(
                ImageRequest.Builder(context).data(next).size(1920, 1080).build()
            )
        }
    }

    // Temporizador de cambio automático (y reintento si no hay fondos)
    LaunchedEffect(intervalSeconds, wallpaperUrls, currentIndex, reloadTick) {
        if (wallpaperUrls.isEmpty()) {
            delay(15_000L)
            loadWallpapers(selectedCategory)
        } else {
            delay(intervalSeconds * 1000L)
            if (currentIndex + 1 < wallpaperUrls.size) {
                currentIndex++
            } else {
                loadWallpapers(selectedCategory)
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(150L)
        try {
            screenFocusRequester.requestFocus()
        } catch (_: Exception) {
            // Si aún no está listo, no pasa nada: el usuario puede pulsar una tecla
        }
    }

    val currentUrl = wallpaperUrls.getOrNull(currentIndex)

    fun maxCol(row: Int) = when (row) {
        0 -> categories.size - 1
        1 -> intervals.size - 1
        else -> 0
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false

        if (!showMenu) {
            return when (event.key) {
                Key.Back -> false // deja que el sistema cierre la app
                Key.DirectionRight -> {
                    if (wallpaperUrls.isNotEmpty()) currentIndex = (currentIndex + 1) % wallpaperUrls.size
                    true
                }
                Key.DirectionLeft -> {
                    if (wallpaperUrls.isNotEmpty()) {
                        currentIndex = (currentIndex - 1 + wallpaperUrls.size) % wallpaperUrls.size
                    }
                    true
                }
                else -> {
                    showMenu = true
                    true
                }
            }
        }

        return when (event.key) {
            Key.DirectionRight -> {
                if (focusedCol < maxCol(focusedRow)) focusedCol++
                true
            }
            Key.DirectionLeft -> {
                if (focusedCol > 0) focusedCol--
                true
            }
            Key.DirectionDown -> {
                if (focusedRow < 2) {
                    focusedRow++
                    focusedCol = focusedCol.coerceAtMost(maxCol(focusedRow))
                }
                true
            }
            Key.DirectionUp -> {
                if (focusedRow > 0) {
                    focusedRow--
                    focusedCol = focusedCol.coerceAtMost(maxCol(focusedRow))
                }
                true
            }
            Key.Enter, Key.DirectionCenter, Key.Spacebar -> {
                when (focusedRow) {
                    0 -> selectedCategory = categories[focusedCol]
                    1 -> intervalSeconds = intervals[focusedCol].first
                    2 -> {
                        applySystemWallpaper(currentUrl)
                        showMenu = false
                    }
                }
                true
            }
            Key.Escape, Key.Back -> {
                showMenu = false
                true
            }
            else -> false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(screenFocusRequester)
            .focusable()
            .onKeyEvent { handleKey(it) }
    ) {
        Crossfade(targetState = currentUrl, animationSpec = tween(1000), label = "wallpaper") { url ->
            if (url != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(url).size(1920, 1080).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Degradado superior para que se lea el reloj
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
                    )
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    text = currentTime,
                    color = Color.White,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    style = LocalTextStyle.current.copy(
                        shadow = Shadow(color = Color.Black, blurRadius = 8f)
                    )
                )
                Text(
                    text = currentDate,
                    color = Color.LightGray,
                    fontSize = 16.sp,
                    style = LocalTextStyle.current.copy(
                        shadow = Shadow(color = Color.Black, blurRadius = 6f)
                    )
                )
            }

            // OJO: este clima es un valor fijo de ejemplo (no es real).
            Surface(
                color = Color.Black.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "🌤️ 19°C",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Parcialmente nublado",
                        color = Color.LightGray,
                        fontSize = 14.sp
                    )
                }
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        val status = statusMessage
        if (status != null && wallpaperUrls.isEmpty() && !isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "⚠ $status\nReintentando automáticamente…",
                        color = Color.White,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }
        }

        if (showMenu) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Fire TV Wallpapers — Izq/Der: Cambiar fondo | Arriba/Abajo: Abrir menú",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Text("Categorías:", color = Color.LightGray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            categories.forEachIndexed { index, cat ->
                                TvButtonUI(
                                    text = cat,
                                    isSelected = selectedCategory == cat,
                                    isFocused = focusedRow == 0 && focusedCol == index
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text("Transición automática:", color = Color.LightGray, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            intervals.forEachIndexed { index, (sec, label) ->
                                TvButtonUI(
                                    text = label,
                                    isSelected = intervalSeconds == sec,
                                    isFocused = focusedRow == 1 && focusedCol == index
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvButtonUI(
                                text = "Aplicar al Sistema y Ocultar Menú",
                                isSelected = true,
                                isFocused = focusedRow == 2 && focusedCol == 0
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TvButtonUI(text: String, isSelected: Boolean, isFocused: Boolean) {
    val bgColor = if (isSelected) Color(0xFF6750A4) else Color(0xFF333333)
    val border = if (isFocused) BorderStroke(3.dp, Color.Yellow) else null

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(8.dp),
        border = border,
        modifier = Modifier.padding(4.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = if (isFocused || isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}