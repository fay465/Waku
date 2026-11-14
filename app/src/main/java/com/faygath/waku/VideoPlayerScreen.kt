package com.faygath.waku

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.faygath.waku.ui.theme.*
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * DataSource personalizado con OkHttp que limpia segmentos HLS camuflados como .html / .jpg / .png
 * e incluye reintentos automáticos ante micro-cortes de red o errores 5xx de Cloudflare/CDN.
 */
@OptIn(UnstableApi::class)
private class WakuSegmentSanitizingDataSource(
    private val client: OkHttpClient,
    private val defaultHeaders: Map<String, String>,
    private val onDiagnosticInfo: (String) -> Unit
) : BaseDataSource(true) {

    private var response: Response? = null
    private var inputStream: InputStream? = null
    private var currentUri: Uri? = null
    private var bytesRemaining: Long = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val urlStr = dataSpec.uri.toString()
        val lowerUrl = urlStr.lowercase()
        currentUri = dataSpec.uri

        val isMp4File = lowerUrl.contains(".mp4") && !lowerUrl.contains(".m3u8")
        val activeClient = if (lowerUrl.contains("mp4upload")) {
            WakuRepository.streamHttpClient
        } else {
            client
        }

        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun buildBaseRequest(customHeaders: Map<String, String>): Request {
            val builder = Request.Builder()
                .url(urlStr)
                .header("User-Agent", userAgent)

            for ((k, v) in customHeaders) {
                builder.header(k, v)
            }
            for ((k, v) in dataSpec.httpRequestHeaders) {
                if (!k.equals("Accept-Encoding", ignoreCase = true)) {
                    builder.header(k, v)
                }
            }
            if (isMp4File || dataSpec.position > 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                if (isMp4File || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    val end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                        (dataSpec.position + dataSpec.length - 1).toString()
                    } else ""
                    builder.header("Range", "bytes=${dataSpec.position}-$end")
                }
            }
            return builder.build()
        }

        fun executeSingleAttempt(): Response {
            // Intento 1: Cabeceras originales del extractor
            var resp = activeClient.newCall(buildBaseRequest(defaultHeaders)).execute()

            // Intento 2 (si da 403/401): Política de navegador strict-origin-when-cross-origin
            if (!resp.isSuccessful && (resp.code == 403 || resp.code == 401)) {
                resp.close()
                val refOrigin = defaultHeaders["Origin"] ?: runCatching {
                    val u = Uri.parse(defaultHeaders["Referer"].orEmpty())
                    "${u.scheme}://${u.host}"
                }.getOrDefault("https://player.zilla-networks.com")

                val crossHeaders = HashMap(defaultHeaders).apply {
                    put("Referer", "$refOrigin/")
                    put("Origin", refOrigin)
                    put("Sec-Fetch-Dest", "empty")
                    put("Sec-Fetch-Mode", "cors")
                    put("Sec-Fetch-Site", "cross-site")
                }
                resp = activeClient.newCall(buildBaseRequest(crossHeaders)).execute()
            }

            // Intento 3 (si sigue 403/401): Origin del propio servidor del segmento
            if (!resp.isSuccessful && (resp.code == 403 || resp.code == 401)) {
                resp.close()
                val segOrigin = runCatching {
                    "${dataSpec.uri.scheme}://${dataSpec.uri.host}"
                }.getOrDefault("https://player.zilla-networks.com")

                val selfHeaders = mapOf(
                    "Referer" to "$segOrigin/",
                    "Origin" to segOrigin,
                    "Accept" to "*/*"
                )
                resp = activeClient.newCall(buildBaseRequest(selfHeaders)).execute()
            }

            // Intento 4 (si sigue 403/401): Petición limpia sin Referer ni Origin
            if (!resp.isSuccessful && (resp.code == 403 || resp.code == 401)) {
                resp.close()
                resp = activeClient.newCall(buildBaseRequest(mapOf("Accept" to "*/*"))).execute()
            }

            return resp
        }

        var lastException: IOException? = null

        // Hasta 3 intentos por segmento ante hipos temporales de red o errores 5xx/429 del CDN
        for (attempt in 1..3) {
            try {
                val resp = executeSingleAttempt()

                if (!resp.isSuccessful) {
                    val code = resp.code
                    resp.close()
                    if ((code == 429 || code >= 500) && attempt < 3) {
                        runCatching { Thread.sleep(350L * attempt) }
                        continue
                    }
                    onDiagnosticInfo("HTTP $code en ${urlStr.takeLast(45)}")
                    throw IOException("HTTP $code al abrir $urlStr")
                }

                val body = resp.body ?: run {
                    resp.close()
                    throw IOException("Body vacío en $urlStr")
                }

                if (isMp4File) {
                    response = resp
                    currentUri = Uri.parse(resp.request.url.toString())
                    inputStream = body.byteStream()
                    bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                        dataSpec.length
                    } else {
                        val contentLength = body.contentLength()
                        if (contentLength != -1L) contentLength else C.LENGTH_UNSET.toLong()
                    }
                } else {
                    val finalUrlStr = resp.request.url.toString()
                    val rawBytes = try {
                        body.bytes()
                    } finally {
                        body.close()
                        resp.close()
                    }

                    if (rawBytes.isEmpty() && attempt < 3) {
                        runCatching { Thread.sleep(300L * attempt) }
                        continue
                    }

                    currentUri = Uri.parse(finalUrlStr)
                    val cleanedBytes = sanitizeHlsPayload(rawBytes, finalUrlStr)
                    val startOffset = dataSpec.position.toInt().coerceIn(0, cleanedBytes.size)
                    val available = (cleanedBytes.size - startOffset).coerceAtLeast(0)
                    val lengthToRead = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                        minOf(available.toLong(), dataSpec.length).toInt()
                    } else {
                        available
                    }

                    inputStream = ByteArrayInputStream(cleanedBytes, startOffset, lengthToRead)
                    bytesRemaining = lengthToRead.toLong()
                }

                opened = true
                transferStarted(dataSpec)
                return bytesRemaining
            } catch (e: IOException) {
                if (e is java.io.InterruptedIOException || Thread.currentThread().isInterrupted) {
                    throw e
                }
                lastException = e
                if (attempt < 3) {
                    runCatching { Thread.sleep(350L * attempt) }
                }
            }
        }

        throw (lastException ?: IOException("No se pudo abrir $urlStr"))
    }

    private fun sanitizeHlsPayload(bytes: ByteArray, baseUrl: String): ByteArray {
        if (bytes.isEmpty()) return bytes

        val headSample = String(bytes, 0, minOf(bytes.size, 256), Charsets.UTF_8).trimStart()
        if (headSample.startsWith("#EXTM3U") || headSample.contains("#EXTINF")) {
            return bytes
        }

        if (bytes.size >= 188 * 3 &&
            bytes[0] == 0x47.toByte() &&
            bytes[188] == 0x47.toByte() &&
            bytes[376] == 0x47.toByte()
        ) {
            return bytes
        }

        if (bytes.size >= 188 * 3) {
            val maxScan = minOf(bytes.size - (188 * 3), 65536)
            for (offset in 1 until maxScan) {
                if (bytes[offset] == 0x47.toByte() &&
                    bytes[offset + 188] == 0x47.toByte() &&
                    bytes[offset + 376] == 0x47.toByte()
                ) {
                    return bytes.copyOfRange(offset, bytes.size)
                }
            }
        }

        if (bytes.size >= 32) {
            val maxScanMp4 = minOf(bytes.size - 8, 16384)
            for (offset in 4 until maxScanMp4) {
                val c0 = bytes[offset].toInt().toChar()
                val c1 = bytes[offset + 1].toInt().toChar()
                val c2 = bytes[offset + 2].toInt().toChar()
                val c3 = bytes[offset + 3].toInt().toChar()
                if ((c0 == 'f' && c1 == 't' && c2 == 'y' && c3 == 'p') ||
                    (c0 == 's' && c1 == 't' && c2 == 'y' && c3 == 'p') ||
                    (c0 == 'm' && c1 == 'o' && c2 == 'o' && c3 == 'f') ||
                    (c0 == 's' && c1 == 'i' && c2 == 'd' && c3 == 'x')
                ) {
                    val boxStart = (offset - 4).coerceAtLeast(0)
                    return if (boxStart > 0) bytes.copyOfRange(boxStart, bytes.size) else bytes
                }
            }
        }

        return bytes
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val stream = inputStream ?: return C.RESULT_END_OF_INPUT
        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(bytesRemaining, length.toLong()).toInt()
        }

        val read = stream.read(buffer, offset, bytesToRead)
        if (read == -1) return C.RESULT_END_OF_INPUT

        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= read.toLong()
        }
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        runCatching { inputStream?.close() }
        runCatching { response?.close() }
        inputStream = null
        response = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

@OptIn(UnstableApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VideoPlayerScreen(
    slug: String,
    initialEpisodeNumber: Int,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid

    val playerPrefs = remember {
        context.getSharedPreferences("waku_player_prefs_v1", Context.MODE_PRIVATE)
    }

    var currentEpisodeNumber by remember { mutableIntStateOf(initialEpisodeNumber) }
    var streamsData by remember { mutableStateOf<EpisodeStreamsData?>(null) }
    var isLoadingServers by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Idioma ("SUB" o "DUB") y Servidor seleccionado (0 = HLS por defecto salvo preferencia por anime)
    var selectedLang by remember { mutableStateOf("SUB") }
    var selectedServerIndex by remember { mutableIntStateOf(0) }
    var preferredServerName by remember { mutableStateOf<String?>(null) }
    var showServerPickerModal by remember { mutableStateOf(false) }

    // URL real (.m3u8 / .mp4) y cabeceras para ExoPlayer
    var resolvedStreamUrl by remember { mutableStateOf<String?>(null) }
    var resolvedHeaders by remember { mutableStateOf(emptyMap<String, String>()) }
    var isSniffingStream by remember { mutableStateOf(false) }
    var snifferTargetUrl by remember { mutableStateOf<String?>(null) }
    var serverFailedMessage by remember { mutableStateOf<String?>(null) }
    var lastDiagnosticText by remember { mutableStateOf("") }

    // Estados de reproducción de ExoPlayer
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var seekNoticeText by remember { mutableStateOf<String?>(null) }

    // Control de continuidad y auto-recuperación ante cortes de red a mitad del capítulo
    var savedInitialFraction by remember { mutableFloatStateOf(0f) }
    var hasRestoredPosition by remember { mutableStateOf(false) }
    var pendingSeekPositionMs by remember { mutableLongStateOf(-1L) }
    var lastKnownValidPositionMs by remember { mutableLongStateOf(0L) }
    var lastKnownValidDurationMs by remember { mutableLongStateOf(0L) }
    var autoRecoveryAttempts by remember { mutableIntStateOf(0) }
    var watchedSet by remember { mutableStateOf<Set<Int>>(emptySet()) }

    // 1. Modo Horizontal (Landscape) y Pantalla Inmersiva
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val window = activity?.window
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // 1B. Mantener la pantalla encendida SOLO mientras el video se está reproduciendo o cargando.
    // Si el usuario pausa el video o sale del reproductor, permite el apagado normal de pantalla.
    val shouldKeepScreenOn = (isPlaying || isBuffering || isLoadingServers || isSniffingStream) &&
            errorMessage == null && serverFailedMessage == null

    DisposableEffect(shouldKeepScreenOn) {
        val window = context.findActivity()?.window
        if (shouldKeepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 2. Instancia única de ExoPlayer
    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .build().apply {
                playWhenReady = true
            }
    }

    // Helper: Guarda las preferencias de idioma y servidor para ESTE anime específico
    fun saveAnimePlayerPreferences(newLang: String, newServerName: String?) {
        playerPrefs.edit().apply {
            putString("lang_$slug", newLang)
            if (!newServerName.isNullOrBlank()) {
                putString("srv_$slug", newServerName)
            }
            apply()
        }
        val animeId = streamsData?.animeId ?: 0
        if (uid != null && animeId > 0) {
            val updates = mutableMapOf<String, Any>("preferredLang" to newLang)
            if (!newServerName.isNullOrBlank()) {
                updates["preferredServer"] = newServerName
            }
            db.collection("usuarios").document(uid)
                .collection("mi_lista").document(animeId.toString())
                .set(updates, SetOptions.merge())
        }
    }

    // Helper: Captura el segundo actual (-1s) antes de cambiar de servidor o de audio
    fun capturePositionForContinuity() {
        val currentPos = maxOf(exoPlayer.currentPosition, lastKnownValidPositionMs)
        if (currentPos > 1500L) {
            pendingSeekPositionMs = (currentPos - 1000L).coerceAtLeast(0L)
        }
    }

    // Helper: Obtiene la posición y duración reales sin riesgo de sobrescribir con 00:00
    fun getSafeProgressSnapshot(): Pair<Long, Long> {
        val safePos = when {
            pendingSeekPositionMs > 0L -> pendingSeekPositionMs
            exoPlayer.currentPosition > 2000L -> exoPlayer.currentPosition
            lastKnownValidPositionMs > 2000L -> lastKnownValidPositionMs
            else -> 0L
        }
        val safeDur = if (exoPlayer.duration > 5000L) exoPlayer.duration else lastKnownValidDurationMs
        return safePos to safeDur
    }

    // 3. Cargar lista de servidores del episodio + progreso y preferencias previas del anime
    LaunchedEffect(slug, currentEpisodeNumber) {
        isLoadingServers = true
        errorMessage = null
        serverFailedMessage = null
        resolvedStreamUrl = null
        hasRestoredPosition = false
        savedInitialFraction = 0f
        pendingSeekPositionMs = -1L
        lastKnownValidPositionMs = 0L
        lastKnownValidDurationMs = 0L
        autoRecoveryAttempts = 0
        currentPositionMs = 0L
        durationMs = 0L
        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        val res = WakuRepository.fetchEpisodeStreams(context, slug, currentEpisodeNumber)
        res.onSuccess { data ->
            var savedLangPref = playerPrefs.getString("lang_$slug", null)
            var savedServerPref = playerPrefs.getString("srv_$slug", null)

            if (uid != null && data.animeId > 0) {
                val snap = withContext(Dispatchers.IO) {
                    runCatching {
                        Tasks.await(
                            db.collection("usuarios").document(uid)
                                .collection("mi_lista").document(data.animeId.toString())
                                .get(),
                            2500,
                            TimeUnit.MILLISECONDS
                        )
                    }.getOrNull()
                }

                if (snap != null && snap.exists()) {
                    val rawWatched = snap.get("watchedEpisodes") as? List<*>
                    watchedSet = rawWatched?.mapNotNull { (it as? Number)?.toInt() }?.toSet()
                        ?: emptySet()

                    val savedEp = snap.getLong("episodioActual")?.toInt() ?: -1
                    val savedProg = (snap.getDouble("progress")?.toFloat() ?: 0f).coerceIn(0f, 1f)
                    if (savedEp == currentEpisodeNumber && savedProg in 0.03f..0.88f) {
                        savedInitialFraction = savedProg
                    }

                    val remoteLang = snap.getString("preferredLang")?.trim()
                    val remoteSrv = snap.getString("preferredServer")?.trim()
                    if (savedLangPref.isNullOrBlank() && !remoteLang.isNullOrBlank()) {
                        savedLangPref = remoteLang
                        playerPrefs.edit().putString("lang_$slug", remoteLang).apply()
                    }
                    if (savedServerPref.isNullOrBlank() && !remoteSrv.isNullOrBlank()) {
                        savedServerPref = remoteSrv
                        playerPrefs.edit().putString("srv_$slug", remoteSrv).apply()
                    }
                }
            }

            // Resolver idioma: respeta la preferencia del anime, y si el anime es solo DUB (ej. Dragon Ball) usa DUB
            val desiredLang = savedLangPref ?: "SUB"
            val resolvedLang = when {
                desiredLang == "DUB" && data.dubServers.isNotEmpty() -> "DUB"
                desiredLang == "SUB" && data.subServers.isNotEmpty() -> "SUB"
                data.subServers.isEmpty() && data.dubServers.isNotEmpty() -> "DUB"
                data.dubServers.isEmpty() && data.subServers.isNotEmpty() -> "SUB"
                else -> "SUB"
            }

            val targetPool = if (resolvedLang == "DUB") data.dubServers else data.subServers
            val matchedServerIndex = if (!savedServerPref.isNullOrBlank()) {
                targetPool.indexOfFirst { it.name.equals(savedServerPref, ignoreCase = true) }
            } else -1

            selectedLang = resolvedLang
            preferredServerName = savedServerPref
            selectedServerIndex = if (matchedServerIndex >= 0) matchedServerIndex else 0
            streamsData = data
            isLoadingServers = false
        }.onFailure { err ->
            errorMessage = err.localizedMessage ?: "No se pudieron obtener los servidores del episodio"
            isLoadingServers = false
        }
    }

    val activeServerList = remember(streamsData, selectedLang) {
        val data = streamsData ?: return@remember emptyList()
        when {
            selectedLang == "DUB" && data.dubServers.isNotEmpty() -> data.dubServers
            selectedLang == "SUB" && data.subServers.isNotEmpty() -> data.subServers
            data.dubServers.isNotEmpty() -> data.dubServers
            else -> data.subServers
        }
    }

    val currentServer = activeServerList.getOrNull(selectedServerIndex) ?: activeServerList.firstOrNull()

    // 4. Extraer el stream cuando cambia el servidor o el idioma seleccionado
    LaunchedEffect(currentServer?.embedUrl, selectedLang) {
        val embedUrl = currentServer?.embedUrl ?: return@LaunchedEffect
        resolvedStreamUrl = null
        serverFailedMessage = null
        lastDiagnosticText = ""
        autoRecoveryAttempts = 0
        snifferTargetUrl = null
        isSniffingStream = true
        controlsVisible = true
        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        val directResult = WakuRepository.tryExtractDirectVideoUrl(embedUrl)
        if (directResult != null) {
            resolvedHeaders = directResult.second
            resolvedStreamUrl = directResult.first
            isSniffingStream = false
        } else {
            snifferTargetUrl = embedUrl
            delay(15000)
            if (resolvedStreamUrl == null && isSniffingStream) {
                isSniffingStream = false
                serverFailedMessage = "No se detectó señal directa en ${currentServer.name}. Prueba otro servidor arriba a la derecha."
            }
        }
    }

    // 5. Inyectar el stream en ExoPlayer con política de reintento extendida (hasta 6 reintentos por segmento)
    LaunchedEffect(resolvedStreamUrl) {
        val streamUrl = resolvedStreamUrl ?: return@LaunchedEffect
        isBuffering = true
        serverFailedMessage = null

        val dataSourceFactory = DataSource.Factory {
            WakuSegmentSanitizingDataSource(
                client = WakuRepository.httpClient,
                defaultHeaders = resolvedHeaders,
                onDiagnosticInfo = { info -> lastDiagnosticText = info }
            )
        }

        val resilientLoadPolicy = DefaultLoadErrorHandlingPolicy(6)
        val lowerUrl = streamUrl.lowercase()
        val isMp4 = lowerUrl.contains(".mp4") && !lowerUrl.contains(".m3u8")

        val mediaSource = if (isMp4) {
            val mediaItem = MediaItem.fromUri(streamUrl)
            DefaultMediaSourceFactory(dataSourceFactory)
                .setLoadErrorHandlingPolicy(resilientLoadPolicy)
                .createMediaSource(mediaItem)
        } else {
            val hlsExtractorFactory = DefaultHlsExtractorFactory(9, true)
            val mediaItem = MediaItem.Builder()
                .setUri(streamUrl)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
            HlsMediaSource.Factory(dataSourceFactory)
                .setExtractorFactory(hlsExtractorFactory)
                .setLoadErrorHandlingPolicy(resilientLoadPolicy)
                .setAllowChunklessPreparation(false)
                .createMediaSource(mediaItem)
        }

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // 6. Escuchar estado de ExoPlayer, restaurar minuto exacto y auto-recuperarse de micro-cortes
    DisposableEffect(exoPlayer, currentServer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    serverFailedMessage = null
                    autoRecoveryAttempts = 0
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                    if (durationMs > 5000L) {
                        lastKnownValidDurationMs = durationMs
                    }

                    // Prioridad 1: Venimos de un cambio de servidor/audio o auto-recuperación (-1s)
                    if (pendingSeekPositionMs >= 0L && durationMs > 0L) {
                        val targetMs = pendingSeekPositionMs.coerceAtMost((durationMs - 1000L).coerceAtLeast(0L))
                        pendingSeekPositionMs = -1L
                        hasRestoredPosition = true
                        exoPlayer.seekTo(targetMs)
                        currentPositionMs = targetMs
                        lastKnownValidPositionMs = targetMs
                        seekNoticeText = "✦ Continuando en ${formatTimeMs(targetMs)}"
                    }
                    // Prioridad 2: Primera carga del episodio retomando progreso guardado en Firestore
                    else if (!hasRestoredPosition && savedInitialFraction > 0.03f && durationMs > 0L) {
                        hasRestoredPosition = true
                        val targetMs = (durationMs * savedInitialFraction).toLong()
                        exoPlayer.seekTo(targetMs)
                        currentPositionMs = targetMs
                        lastKnownValidPositionMs = targetMs
                        seekNoticeText = "Reanudando en ${formatTimeMs(targetMs)}"
                    } else if (!hasRestoredPosition) {
                        hasRestoredPosition = true
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Si ya estaba reproduciendo (ej. minuto 17) y hubo un hipo del CDN, reconectamos 1 vez en silencio
                if (autoRecoveryAttempts < 1 && lastKnownValidPositionMs > 3000L) {
                    autoRecoveryAttempts += 1
                    capturePositionForContinuity()
                    isBuffering = true
                    exoPlayer.prepare()
                    exoPlayer.play()
                    return
                }

                isBuffering = false
                controlsVisible = true
                val causeDetail = error.cause?.localizedMessage ?: error.localizedMessage ?: error.errorCodeName
                val extra = if (lastDiagnosticText.isNotBlank()) " ($lastDiagnosticText)" else ""
                serverFailedMessage = "Error en ${currentServer?.name.orEmpty()}: $causeDetail$extra"
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // Pausar y guardar progreso seguro al minimizar o salir de la pantalla
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                exoPlayer.pause()
                val (safePos, safeDur) = getSafeProgressSnapshot()
                if (safePos > 2000L && safeDur > 5000L) {
                    streamsData?.let { data ->
                        savePlaybackProgressToFirestore(
                            db = db,
                            uid = uid,
                            data = data,
                            currentPosMs = safePos,
                            totalDurationMs = safeDur,
                            currentWatched = watchedSet,
                            preferredLang = selectedLang,
                            preferredServer = currentServer?.name
                        )
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val (safePos, safeDur) = getSafeProgressSnapshot()
            if (safePos > 2000L && safeDur > 5000L) {
                streamsData?.let { data ->
                    savePlaybackProgressToFirestore(
                        db = db,
                        uid = uid,
                        data = data,
                        currentPosMs = safePos,
                        totalDurationMs = safeDur,
                        currentWatched = watchedSet,
                        preferredLang = selectedLang,
                        preferredServer = currentServer?.name
                    )
                }
            }
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    // Actualizar el reloj cada 500ms y sincronizar progreso con Firestore cada 5s
    LaunchedEffect(exoPlayer, isPlaying, resolvedStreamUrl) {
        var tick = 0
        while (true) {
            if (resolvedStreamUrl != null) {
                val rawPos = exoPlayer.currentPosition.coerceAtLeast(0L)
                val rawDur = exoPlayer.duration.coerceAtLeast(0L)

                if (pendingSeekPositionMs < 0L && hasRestoredPosition) {
                    currentPositionMs = rawPos
                    if (rawPos > 1500L) {
                        lastKnownValidPositionMs = rawPos
                    }
                }
                if (rawDur > 5000L) {
                    durationMs = rawDur
                    lastKnownValidDurationMs = rawDur
                }

                tick++
                if (tick % 10 == 0 &&
                    pendingSeekPositionMs < 0L &&
                    hasRestoredPosition &&
                    durationMs > 5000L &&
                    currentPositionMs > 2500L
                ) {
                    streamsData?.let { data ->
                        watchedSet = savePlaybackProgressToFirestore(
                            db = db,
                            uid = uid,
                            data = data,
                            currentPosMs = currentPositionMs,
                            totalDurationMs = durationMs,
                            currentWatched = watchedSet,
                            preferredLang = selectedLang,
                            preferredServer = currentServer?.name
                        )
                    }
                }
            }
            delay(500)
        }
    }

    // Auto-ocultar controles después de 4 segundos mientras reproduce
    LaunchedEffect(controlsVisible, isPlaying, showServerPickerModal, serverFailedMessage) {
        if (controlsVisible && isPlaying && !showServerPickerModal &&
            resolvedStreamUrl != null && serverFailedMessage == null
        ) {
            delay(4200)
            controlsVisible = false
        }
    }

    LaunchedEffect(seekNoticeText) {
        if (seekNoticeText != null) {
            delay(2200)
            seekNoticeText = null
        }
    }

    BackHandler {
        val (safePos, safeDur) = getSafeProgressSnapshot()
        if (safePos > 2000L && safeDur > 5000L) {
            streamsData?.let { data ->
                savePlaybackProgressToFirestore(
                    db = db,
                    uid = uid,
                    data = data,
                    currentPosMs = safePos,
                    totalDurationMs = safeDur,
                    currentWatched = watchedSet,
                    preferredLang = selectedLang,
                    preferredServer = currentServer?.name
                )
            }
        }
        exoPlayer.stop()
        onBackClick()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // =====================================================================
        // CAPA 0: SNIFFER SILENCIOSO (Solo para servidores que no se extraen por HTTP directo)
        // =====================================================================
        if (snifferTargetUrl != null && isSniffingStream && resolvedStreamUrl == null) {
            SilentStreamSnifferWebView(
                embedUrl = snifferTargetUrl!!,
                refererUrl = currentServer?.embedUrl ?: "https://animeav1.com/",
                onStreamIntercepted = { streamUrl, cleanHeaders ->
                    if (resolvedStreamUrl == null) {
                        resolvedHeaders = cleanHeaders
                        resolvedStreamUrl = streamUrl
                        isSniffingStream = false
                    }
                }
            )
        }

        // =====================================================================
        // CAPA 1: REPRODUCTOR NATIVO EXOPLAYER
        // =====================================================================
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { offset ->
                            val screenW = size.width
                            if (offset.x < screenW * 0.4f) {
                                val newPos = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                exoPlayer.seekTo(newPos)
                                currentPositionMs = newPos
                                lastKnownValidPositionMs = newPos
                                seekNoticeText = "⏪ -10s"
                            } else if (offset.x > screenW * 0.6f) {
                                val maxDur = exoPlayer.duration.coerceAtLeast(0L)
                                val newPos = (exoPlayer.currentPosition + 10_000L).coerceAtMost(maxDur)
                                exoPlayer.seekTo(newPos)
                                currentPositionMs = newPos
                                lastKnownValidPositionMs = newPos
                                seekNoticeText = "⏩ +10s"
                            } else {
                                controlsVisible = !controlsVisible
                            }
                        }
                    )
                },
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = shouldKeepScreenOn
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    player = exoPlayer
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view ->
                view.keepScreenOn = shouldKeepScreenOn
                if (view.player != exoPlayer) {
                    view.player = exoPlayer
                }
            }
        )

        // =====================================================================
        // CAPA 2: INDICADORES DE CARGA Y DIAGNÓSTICO
        // =====================================================================
        if (isLoadingServers || isSniffingStream || (resolvedStreamUrl != null && isBuffering && serverFailedMessage == null)) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = WakuPrimary,
                        strokeWidth = 3.5.dp,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = when {
                            isLoadingServers -> "Cargando episodio $currentEpisodeNumber..."
                            isSniffingStream -> "Extrayendo señal de ${currentServer?.name.orEmpty()}..."
                            else -> "Cargando buffer de video (${currentServer?.name.orEmpty()})..."
                        },
                        color = WakuText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        if (errorMessage != null || serverFailedMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF1A1412).copy(alpha = 0.94f))
                        .border(1.dp, Color(0xFF3A3331), RoundedCornerShape(18.dp))
                        .padding(20.dp)
                ) {
                    Text(
                        text = errorMessage ?: serverFailedMessage.orEmpty(),
                        color = WakuPink,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                capturePositionForContinuity()
                                serverFailedMessage = null
                                exoPlayer.prepare()
                                exoPlayer.play()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2F2926))
                        ) {
                            Text("Reintentar", color = WakuText, fontSize = 12.sp)
                        }
                        Button(
                            onClick = { showServerPickerModal = true },
                            colors = ButtonDefaults.buttonColors(containerColor = WakuPrimary)
                        ) {
                            Text("Cambiar Servidor", color = WakuText, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = seekNoticeText != null,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFF16110F).copy(alpha = 0.88f))
                    .border(1.dp, WakuGold.copy(alpha = 0.5f), CircleShape)
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(
                    text = seekNoticeText.orEmpty(),
                    color = WakuGold,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // =====================================================================
        // CAPA 3: HUD DE CONTROLES KURO CRIMSON
        // =====================================================================
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { controlsVisible = false }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF120D0B).copy(alpha = 0.88f), Color.Transparent)
                            )
                        )
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xFF120D0B).copy(alpha = 0.92f))
                            )
                        )
                )

                // BARRA SUPERIOR: Botón Volver + Título y Episodio + Selector SUB/DUB + Selector de Servidor
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = {
                                val (safePos, safeDur) = getSafeProgressSnapshot()
                                if (safePos > 2000L && safeDur > 5000L) {
                                    streamsData?.let { data ->
                                        savePlaybackProgressToFirestore(
                                            db = db,
                                            uid = uid,
                                            data = data,
                                            currentPosMs = safePos,
                                            totalDurationMs = safeDur,
                                            currentWatched = watchedSet,
                                            preferredLang = selectedLang,
                                            preferredServer = currentServer?.name
                                        )
                                    }
                                }
                                exoPlayer.stop()
                                onBackClick()
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF211B18).copy(alpha = 0.82f))
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Salir del reproductor",
                                tint = WakuText,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = streamsData?.animeTitle ?: "Cargando...",
                                color = WakuText,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "EPISODIO $currentEpisodeNumber • ${streamsData?.episodeTitle.orEmpty()}",
                                color = WakuGold,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                letterSpacing = 0.6.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val hasSub = streamsData?.subServers?.isNotEmpty() == true
                        val hasDub = streamsData?.dubServers?.isNotEmpty() == true
                        val canToggleLang = hasSub && hasDub

                        if (hasDub || hasSub) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color(0xFF211B18).copy(alpha = 0.90f))
                                    .border(1.dp, WakuGold.copy(alpha = 0.45f), CircleShape)
                                    .clickable {
                                        if (canToggleLang) {
                                            capturePositionForContinuity()
                                            val newLang = if (selectedLang == "SUB") "DUB" else "SUB"
                                            val targetPool = if (newLang == "DUB") {
                                                streamsData?.dubServers.orEmpty()
                                            } else {
                                                streamsData?.subServers.orEmpty()
                                            }
                                            val preferredName = preferredServerName ?: currentServer?.name
                                            val matchedIdx = if (!preferredName.isNullOrBlank()) {
                                                targetPool.indexOfFirst {
                                                    it.name.equals(preferredName, ignoreCase = true)
                                                }
                                            } else -1

                                            selectedLang = newLang
                                            selectedServerIndex = if (matchedIdx >= 0) matchedIdx else 0
                                            saveAnimePlayerPreferences(
                                                newLang = newLang,
                                                newServerName = targetPool.getOrNull(selectedServerIndex)?.name
                                            )
                                        } else {
                                            seekNoticeText = if (hasDub) {
                                                "Solo disponible en Audio Latino"
                                            } else {
                                                "Solo disponible en Subtitulado (JP)"
                                            }
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Subtitles,
                                    contentDescription = null,
                                    tint = WakuGold,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (selectedLang == "DUB") "AUDIO: LATINO" else "AUDIO: SUB (JP)",
                                    color = WakuGold,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0xFF211B18).copy(alpha = 0.90f))
                                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                                .clickable { showServerPickerModal = true }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Dns,
                                contentDescription = null,
                                tint = WakuPink,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = currentServer?.name?.uppercase() ?: "HLS",
                                color = WakuText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                        }
                    }
                }

                // CONTROLES CENTRALES: -10s | Play/Pause | +10s
                if (resolvedStreamUrl != null && serverFailedMessage == null && !isBuffering) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(36.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                val target = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                exoPlayer.seekTo(target)
                                currentPositionMs = target
                                lastKnownValidPositionMs = target
                                seekNoticeText = "⏪ -10s"
                            },
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF16110F).copy(alpha = 0.75f))
                                .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Replay10,
                                contentDescription = "Retroceder 10 segundos",
                                tint = WakuText,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                            modifier = Modifier
                                .size(66.dp)
                                .shadow(
                                    elevation = 24.dp,
                                    shape = CircleShape,
                                    ambientColor = WakuPrimary,
                                    spotColor = WakuPrimary
                                )
                                .clip(CircleShape)
                                .background(WakuPrimary)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = "Reproducir o Pausar",
                                tint = WakuText,
                                modifier = Modifier.size(34.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                val maxDur = exoPlayer.duration.coerceAtLeast(0L)
                                val target = (exoPlayer.currentPosition + 10_000L).coerceAtMost(maxDur)
                                exoPlayer.seekTo(target)
                                currentPositionMs = target
                                lastKnownValidPositionMs = target
                                seekNoticeText = "⏩ +10s"
                            },
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF16110F).copy(alpha = 0.75f))
                                .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Forward10,
                                contentDescription = "Adelantar 10 segundos",
                                tint = WakuText,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }

                // BARRA INFERIOR: Tiempos + "Saltar Intro +85s" + "Sig. Ep." + Scrub Bar #CE351C
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatTimeMs(currentPositionMs)}  /  ${formatTimeMs(durationMs)}",
                            color = WakuText,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (resolvedStreamUrl != null) {
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(Color(0xFF211B18).copy(alpha = 0.90f))
                                        .border(1.dp, WakuGold.copy(alpha = 0.55f), CircleShape)
                                        .clickable {
                                            val maxDur = exoPlayer.duration.coerceAtLeast(0L)
                                            val target = (exoPlayer.currentPosition + 85_000L).coerceAtMost(maxDur)
                                            exoPlayer.seekTo(target)
                                            currentPositionMs = target
                                            lastKnownValidPositionMs = target
                                            seekNoticeText = "✦ +85s Intro"
                                        }
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "Saltar Intro +85s",
                                        color = WakuGold,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (streamsData?.hasNextEpisode == true) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .shadow(12.dp, CircleShape, ambientColor = WakuPrimary, spotColor = WakuPrimary)
                                        .clip(CircleShape)
                                        .background(WakuPrimary)
                                        .clickable {
                                            streamsData?.let { data ->
                                                watchedSet = savePlaybackProgressToFirestore(
                                                    db = db,
                                                    uid = uid,
                                                    data = data,
                                                    currentPosMs = durationMs.coerceAtLeast(10_000L),
                                                    totalDurationMs = durationMs.coerceAtLeast(10_000L),
                                                    currentWatched = watchedSet,
                                                    preferredLang = selectedLang,
                                                    preferredServer = currentServer?.name
                                                )
                                            }
                                            currentEpisodeNumber += 1
                                        }
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SkipNext,
                                        contentDescription = null,
                                        tint = WakuText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Sig. Ep. ${currentEpisodeNumber + 1}",
                                        color = WakuText,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    val sliderFraction = if (durationMs > 0L) {
                        (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    Slider(
                        value = sliderFraction,
                        onValueChange = { newFraction ->
                            if (durationMs > 0L) {
                                val newPos = (newFraction * durationMs).toLong()
                                currentPositionMs = newPos
                                lastKnownValidPositionMs = newPos
                                exoPlayer.seekTo(newPos)
                            }
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = WakuText,
                            activeTrackColor = WakuPrimary,
                            inactiveTrackColor = WakuText.copy(alpha = 0.22f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                    )
                }
            }
        }

        // =====================================================================
        // CAPA 4: MODAL DE SELECCIÓN DE SERVIDOR
        // =====================================================================
        if (showServerPickerModal) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .clickable { showServerPickerModal = false },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 460.dp)
                        .fillMaxWidth(0.82f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFF1E1614))
                        .border(1.dp, Color(0xFF3A3331), RoundedCornerShape(22.dp))
                        .clickable(enabled = false) {}
                        .padding(20.dp)
                ) {
                    Text(
                        text = "Seleccionar Servidor",
                        color = WakuText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tu elección se recordará para los capítulos de este anime",
                        color = Color(0xFFE4BEB7).copy(alpha = 0.75f),
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(activeServerList) { index, srv ->
                            val isSelected = index == selectedServerIndex
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (isSelected) WakuPrimary else Color(0xFF2C2420))
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) WakuPrimary else Color(0xFF3A3331),
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        if (index != selectedServerIndex || serverFailedMessage != null) {
                                            capturePositionForContinuity()
                                            selectedServerIndex = index
                                            preferredServerName = srv.name
                                            saveAnimePlayerPreferences(
                                                newLang = selectedLang,
                                                newServerName = srv.name
                                            )
                                        }
                                        showServerPickerModal = false
                                    }
                                    .padding(horizontal = 16.dp, vertical = 9.dp)
                            ) {
                                Text(
                                    text = srv.name,
                                    color = if (isSelected) WakuText else Color(0xFFE4BEB7),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SilentStreamSnifferWebView(
    embedUrl: String,
    refererUrl: String,
    onStreamIntercepted: (String, Map<String, String>) -> Unit
) {
    val hasCaptured = remember(embedUrl) { AtomicBoolean(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val adDomains = remember {
        listOf(
            "doubleclick", "googlesyndication", "popads", "popcash", "adsterra",
            "exoclick", "juicyads", "aarems.org", "propellerads", "clickadu",
            "hilltopads", "adskeeper", "mgid.com", "bet365", "1xbet", "yandex",
            "dismayingbemoanassume", "phruk.org"
        )
    }

    DisposableEffect(embedUrl) {
        onDispose {
            webViewRef?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.onPause()
                    wv.removeAllViews()
                    wv.destroy()
                }
            }
            webViewRef = null
        }
    }

    LaunchedEffect(embedUrl) {
        delay(1800)
        repeat(6) {
            if (hasCaptured.get()) return@LaunchedEffect
            webViewRef?.let { wv ->
                wv.evaluateJavascript(
                    """
                    (function() {
                        document.querySelectorAll('video, audio').forEach(function(v) {
                            v.muted = true; v.volume = 0;
                        });
                        var btn = document.querySelector('button, [role="button"], .jw-icon-display, svg');
                        if (btn) {
                            var r = btn.getBoundingClientRect();
                            if (r.width > 0 && r.height > 0) {
                                return (r.left + r.width / 2) + ',' + (r.top + r.height / 2);
                            }
                        }
                        return '';
                    })();
                    """.trimIndent()
                ) { result ->
                    if (hasCaptured.get() || wv.width <= 0 || wv.height <= 0) return@evaluateJavascript
                    val clean = result?.trim('"').orEmpty()
                    val parts = clean.split(",")
                    val density = wv.resources.displayMetrics.density
                    val targetX = parts.getOrNull(0)?.toFloatOrNull()?.times(density) ?: (wv.width / 2f)
                    val targetY = parts.getOrNull(1)?.toFloatOrNull()?.times(density) ?: (wv.height / 2f)

                    val now = android.os.SystemClock.uptimeMillis()
                    val downEvent = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, targetX, targetY, 0)
                    val upEvent = android.view.MotionEvent.obtain(now, now + 55, android.view.MotionEvent.ACTION_UP, targetX, targetY, 0)
                    wv.dispatchTouchEvent(downEvent)
                    wv.dispatchTouchEvent(upEvent)
                    downEvent.recycle()
                    upEvent.recycle()
                }
            }
            delay(2000)
        }
    }

    AndroidView(
        modifier = Modifier.size(480.dp, 270.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                webViewRef = this
                alpha = 0.01f
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    userAgentString =
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                    setSupportMultipleWindows(true)
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?
                    ): Boolean = false
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val targetUrl = request?.url?.toString().orEmpty()
                        if (!targetUrl.startsWith("http")) return true
                        return adDomains.any { targetUrl.contains(it, ignoreCase = true) }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString().orEmpty()
                        val lower = reqUrl.lowercase()

                        if (adDomains.any { lower.contains(it) } ||
                            lower.contains("test-videos.co.uk") ||
                            lower.contains("bigbuckbunny")
                        ) {
                            return WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }

                        val isHlsPlaylist = (lower.contains(".m3u8") ||
                                lower.contains("/m3u8/") ||
                                lower.contains("master.txt") ||
                                lower.contains("playlist.txt")) &&
                                !lower.endsWith(".ts") &&
                                !lower.endsWith(".html") &&
                                !lower.endsWith(".js") &&
                                !lower.endsWith(".css")

                        val isDirectMp4 = lower.contains(".mp4") &&
                                !lower.contains("blank") &&
                                !lower.contains("preroll") &&
                                !lower.endsWith(".js")

                        if ((isHlsPlaylist || isDirectMp4) && hasCaptured.compareAndSet(false, true)) {
                            val reqHeaders = request?.requestHeaders.orEmpty()
                            val referer = reqHeaders["Referer"] ?: reqHeaders["referer"] ?: embedUrl
                            val origin = reqHeaders["Origin"] ?: reqHeaders["origin"] ?: runCatching {
                                val u = Uri.parse(referer)
                                "${u.scheme}://${u.host}"
                            }.getOrNull()

                            val cleanHeaders = HashMap<String, String>()
                            cleanHeaders["Referer"] = referer
                            if (!origin.isNullOrBlank() && !lower.contains("mp4upload")) {
                                cleanHeaders["Origin"] = origin
                            }
                            cleanHeaders["Accept"] = "*/*"

                            val cookie = CookieManager.getInstance().getCookie(reqUrl)
                                ?: CookieManager.getInstance().getCookie(referer)
                            if (!cookie.isNullOrBlank()) {
                                cleanHeaders["Cookie"] = cookie
                            }

                            view?.post {
                                runCatching {
                                    view.stopLoading()
                                    view.loadUrl("about:blank")
                                }
                                onStreamIntercepted(reqUrl, cleanHeaders)
                            }

                            return WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }

                        return super.shouldInterceptRequest(view, request)
                    }
                }

                loadUrl(embedUrl, mapOf("Referer" to refererUrl))
            }
        },
        onRelease = { webView ->
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.onPause()
                webView.removeAllViews()
                webView.destroy()
            }
        }
    )
}

private fun formatTimeMs(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

private fun savePlaybackProgressToFirestore(
    db: FirebaseFirestore,
    uid: String?,
    data: EpisodeStreamsData,
    currentPosMs: Long,
    totalDurationMs: Long,
    currentWatched: Set<Int>,
    preferredLang: String? = null,
    preferredServer: String? = null
): Set<Int> {
    if (uid == null || data.animeId <= 0 || totalDurationMs <= 5_000L) return currentWatched

    val fraction = (currentPosMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0.02f, 1.0f)
    val remainingMins = (((totalDurationMs - currentPosMs).coerceAtLeast(0L)) / 60_000L).toInt().coerceAtLeast(1)

    val updatedWatched = currentWatched.toMutableSet()
    val isEpisodeCompleted = fraction >= 0.88f

    if (isEpisodeCompleted) {
        updatedWatched.add(data.episodeNumber)
    }

    // Solo se da por finalizada la serie si se conoce el total real (> 0) y ya se completó ese último episodio
    val allSeriesCompleted = data.totalEpisodes > 0 &&
            (!data.isAiring || data.episodeNumber >= data.totalEpisodes) &&
            updatedWatched.size >= data.totalEpisodes &&
            data.episodeNumber >= data.totalEpisodes &&
            isEpisodeCompleted

    val payload = HashMap<String, Any>().apply {
        put("animeId", data.animeId)
        put("slug", data.slug)
        put("title", data.animeTitle)
        if (data.posterUrl.isNotBlank()) put("posterUrl", data.posterUrl)
        put("totalEpisodes", data.totalEpisodes)
        put("isAiring", data.isAiring)
        put("watchedEpisodes", updatedWatched.toList())
        val thumbEp = if (isEpisodeCompleted && data.hasNextEpisode) data.episodeNumber + 1 else data.episodeNumber
        put("imageUrl", "https://cdn.animeav1.com/screenshots/${data.animeId}/$thumbEp.jpg")
        put("updatedAt", System.currentTimeMillis())

        if (!preferredLang.isNullOrBlank()) {
            put("preferredLang", preferredLang)
        }
        if (!preferredServer.isNullOrBlank()) {
            put("preferredServer", preferredServer)
        }

        if (allSeriesCompleted) {
            put("estado", "FINALIZADO")
            put("episodioActual", data.totalEpisodes)
            put("episodeTitle", data.episodeTitle)
            put("progress", 1.0f)
            put("badgeText", "Completado")
            put("isNextEpisode", false)
        } else if (isEpisodeCompleted && data.hasNextEpisode) {
            put("estado", "VIENDO")
            put("episodioActual", data.episodeNumber + 1)
            put("episodeTitle", "Episodio ${data.episodeNumber + 1}")
            put("progress", 0.02f)
            put("badgeText", "Sig. Ep. ${data.episodeNumber + 1}")
            put("isNextEpisode", true)
        } else if (isEpisodeCompleted && data.isAiring) {
            // Está al día con el último capítulo emitido de un anime en emisión
            put("estado", "VIENDO")
            put("episodioActual", data.episodeNumber)
            put("episodeTitle", data.episodeTitle)
            put("progress", 1.0f)
            put("badgeText", "Al día • Ep. ${data.episodeNumber}")
            put("isNextEpisode", false)
        } else {
            put("estado", "VIENDO")
            put("episodioActual", data.episodeNumber)
            put("episodeTitle", data.episodeTitle)
            put("progress", fraction)
            put("badgeText", "Quedan ${remainingMins}m")
            put("isNextEpisode", false)
        }
    }

    db.collection("usuarios").document(uid)
        .collection("mi_lista").document(data.animeId.toString())
        .set(payload, SetOptions.merge())

    return updatedWatched
}