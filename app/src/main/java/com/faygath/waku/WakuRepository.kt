package com.faygath.waku

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.io.StringReader
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.text.Normalizer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit


data class HeroAnimeItem(
    val animeId: Int,
    val slug: String,
    val title: String,
    val categoryName: String,
    val genresText: String,
    val synopsis: String,
    val posterUrl: String,
    val clearLogoUrl: String?
)

data class ContinueWatchingItem(
    val animeId: Int = 0,
    val slug: String = "",
    val title: String = "",
    val episodeNumber: Int = 1,
    val episodeTitle: String = "",
    val imageUrl: String = "",
    val progressFraction: Float = 0.5f,
    val badgeText: String = "Quedan 12m",
    val isNextEpisode: Boolean = false
)

data class LatestEpisodeItem(
    val episodeId: Int,
    val animeId: Int,
    val slug: String,
    val animeTitle: String,
    val episodeNumber: Int,
    val episodeTitle: String?,
    val screenshotUrl: String,
    val durationText: String,
    val timeAgoText: String
)

data class HomeData(
    val heroItems: List<HeroAnimeItem>,
    val latestEpisodes: List<LatestEpisodeItem>
)

data class SeasonTabItem(
    val animeId: Int,
    val slug: String,
    val label: String,
    val episodesCount: Int? = null
)

data class AnimeEpisodeItem(
    val episodeId: Int,
    val number: Int,
    val title: String,
    val screenshotUrl: String,
    val durationMinutes: Int,
    val hasDub: Boolean
)

data class AnimeDetailData(
    val animeId: Int,
    val slug: String,
    val title: String,
    val posterUrl: String,
    val scoreText: String,
    val yearText: String,
    val isAiring: Boolean,
    val totalEpisodes: Int,
    val genres: List<String>,
    val synopsis: String,
    val seasonTabs: List<SeasonTabItem>,
    val episodes: List<AnimeEpisodeItem>
)

data class CatalogAnimeItem(
    val animeId: Int,
    val slug: String,
    val title: String,
    val posterUrl: String,
    val topLeftBadge: String,
    val scoreText: String,
    val isAiring: Boolean,
    val primaryGenre: String,
    val allGenres: List<String>,
    val categoryName: String,
    val year: Int,
    val hasDub: Boolean = false,
    val isEnriched: Boolean = false
)

data class CatalogPageData(
    val items: List<CatalogAnimeItem>,
    val totalCount: Int,
    val hasNextPage: Boolean,
    val availableGenres: List<String> = emptyList()
)

enum class ScheduleStatusType {
    AIRED,          // Verde (#9EC294) con puntito: "10:17 am"
    UPCOMING_SOON,  // Dorado (#EECB61): "En 2h • 16:30"
    UPCOMING_LATER, // Crema tenue: "Hoy • 18:00"
    DELAYED         // Rosa Anya (#F5BAB4): "⚠ Retrasado"
}

data class ScheduleAnimeItem(
    val animeId: Int,
    val slug: String,
    val title: String,
    val posterUrl: String,
    val categoryName: String,
    val dayOfWeek: Int, // 1 = Lunes .. 7 = Domingo
    val episodeNumber: Int,
    val totalEpisodes: Int?,
    val isFinale: Boolean,
    val statusType: ScheduleStatusType,
    val statusBadgeText: String,
    val sortMinuteOfDay: Int
)

data class CharacterAvatarItem(
    val characterId: Int,
    val name: String,
    val imageUrl: String
)

data class SeriesAvatarGroup(
    val animeId: Int,
    val seriesTitle: String,
    val characters: List<CharacterAvatarItem>
)

data class EpisodeStreamServer(
    val name: String,
    val embedUrl: String,
    val lang: String // "SUB" o "DUB"
)

data class EpisodeStreamsData(
    val animeId: Int,
    val slug: String,
    val animeTitle: String,
    val posterUrl: String,
    val episodeNumber: Int,
    val episodeTitle: String,
    val totalEpisodes: Int,
    val hasNextEpisode: Boolean,
    val subServers: List<EpisodeStreamServer>,
    val dubServers: List<EpisodeStreamServer>
)

object WakuRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val streamHttpClient: OkHttpClient by lazy {
        runCatching {
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            )
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, trustAllCerts, SecureRandom())
            }
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }.getOrDefault(client)
    }

    val httpClient: OkHttpClient get() = client

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    val DEFAULT_OFFICIAL_GENRES = listOf(
        "Acción", "Artes Marciales", "Aventura", "Ciencia Ficción", "Comedia",
        "Demonios", "Deportes", "Drama", "Ecchi", "Escolares",
        "Espacial", "Fantasía", "Harem", "Histórico", "Josei",
        "Juegos", "Magia", "Mecha", "Militar", "Misterio",
        "Mitología", "Música", "Policial", "Psicológico", "Recuentos de la vida",
        "Romance", "Seinen", "Shoujo", "Shounen", "Sobrenatural",
        "Superpoderes", "Suspenso", "Terror", "Vampiros"
    )

    private data class ScrapedAnimePage(
        val malId: Int?,
        val genres: List<String>,
        val synopsis: String?
    )

    private data class AniListMeta(
        val malId: Int?,
        val posterUrl: String?,
        val isAiring: Boolean?,
        val year: String?,
        val startDateKey: Int?,
        val score: String?,
        val formatCategory: String?
    )

    private data class ParsedMediaNode(
        val animeId: Int,
        val slug: String,
        val title: String,
        val categoryName: String,
        val malId: Int?,
        val year: Int?,
        val episodesCount: Int,
        val relatedSlugs: List<String>,
        val rawHtml: String
    )

    private val pageCache = ConcurrentHashMap<Int, ScrapedAnimePage>()
    private val mediaNodeCache = ConcurrentHashMap<String, ParsedMediaNode>()
    private val franchiseTabsCache = ConcurrentHashMap<String, List<SeasonTabItem>>()
    private val aniZipCache = ConcurrentHashMap<Int, JsonObject>()
    private val aniListMetaCache = ConcurrentHashMap<String, AniListMeta>()
    private val dubSlugCache = ConcurrentHashMap<String, Boolean>()
    private val epTitleEsCache = ConcurrentHashMap<String, String>()

    // --- EXTENSIONES SEGURAS PARA GSON ---
    private fun JsonObject.safeObj(key: String): JsonObject? {
        val el = this.get(key) ?: return null
        return if (el.isJsonObject) el.asJsonObject else null
    }

    private fun JsonObject.safeArray(key: String): JsonArray? {
        val el = this.get(key) ?: return null
        return if (el.isJsonArray) el.asJsonArray else null
    }

    private fun JsonObject.safeString(key: String): String? {
        val el = this.get(key) ?: return null
        return if (el.isJsonPrimitive) runCatching { el.asString }.getOrNull() else null
    }

    private fun JsonObject.safeInt(key: String): Int? {
        val el = this.get(key) ?: return null
        return if (el.isJsonPrimitive) runCatching { el.asInt }.getOrNull() else null
    }

    private fun JsonElement?.asSafeObj(): JsonObject? {
        return if (this != null && this.isJsonObject) this.asJsonObject else null
    }

    private fun isValidGenreName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val trimmed = name.trim()
        if (trimmed.length < 4) return false
        if (trimmed.equals("null", ignoreCase = true) || trimmed.equals(
                "undefined",
                ignoreCase = true
            )
        ) return false
        return trimmed.first().isUpperCase()
    }

    private fun normalizeCategoryType(
        rawCategory: String?,
        aniListFormat: String?,
        title: String
    ): String {
        if (!aniListFormat.isNullOrBlank()) {
            return aniListFormat
        }
        val clean = rawCategory?.trim().orEmpty()
        return when {
            clean.contains("Película", ignoreCase = true) ||
                    clean.contains("Pelicula", ignoreCase = true) ||
                    clean.contains("Movie", ignoreCase = true) -> "Película"

            clean.contains("OVA", ignoreCase = true) ||
                    clean.contains("ONA", ignoreCase = true) -> "OVA"

            clean.contains("Especial", ignoreCase = true) ||
                    clean.contains("Special", ignoreCase = true) -> "Especial"

            clean.contains("TV", ignoreCase = true) ||
                    clean.contains("Anime", ignoreCase = true) -> "TV Anime"

            Regex("""(?i)\b(movie|pel[ií]cula|gekijouban)\b""").containsMatchIn(title) -> "Película"
            Regex("""(?i)\b(ova)\b""").containsMatchIn(title) -> "OVA"
            Regex("""(?i)\b(special|especial)\b""").containsMatchIn(title) -> "Especial"
            else -> "TV Anime"
        }
    }

    // ✅ Déjala así:
    suspend fun fetchHomeData(context: Context): Result<HomeData> = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = context.getSharedPreferences("waku_mal_cache_v3", Context.MODE_PRIVATE)
            val esPrefs = context.getSharedPreferences("waku_ep_es_cache_v1", Context.MODE_PRIVATE)
            val zoneId = java.time.ZoneId.systemDefault()

            // 1. Descargamos en paralelo la portada (/), el calendario (/horario) y el Top de Popularidad en emisión
            val (homeRawHtml, scheduleRawHtml, topPopularTitles) = coroutineScope {
                val homeDeferred = async {
                    val req = Request.Builder()
                        .url("https://animeav1.com/")
                        .header("User-Agent", USER_AGENT)
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("Error HTTP ${resp.code} al conectar con AnimeAV1")
                        resp.body?.string() ?: error("Respuesta vacía de AnimeAV1")
                    }
                }

                val scheduleDeferred = async {
                    runCatching {
                        val req = Request.Builder()
                            .url("https://animeav1.com/horario")
                            .header("User-Agent", USER_AGENT)
                            .build()
                        client.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
                        }
                    }.getOrDefault("")
                }

                val popularDeferred = async {
                    runCatching {
                        val q = """{"query":"{ Page(perPage: 50) { media(status: RELEASING, type: ANIME, sort: POPULARITY_DESC) { title { romaji english } popularity } } }"}"""
                        val body = q.toRequestBody("application/json; charset=utf-8".toMediaType())
                        val req = Request.Builder().url("https://graphql.anilist.co").post(body).build()
                        client.newCall(req).execute().use { resp ->
                            val map = HashMap<String, Int>()
                            if (resp.isSuccessful) {
                                val arr = JsonParser.parseString(resp.body?.string().orEmpty())
                                    .asSafeObj()?.safeObj("data")?.safeObj("Page")?.safeArray("media")
                                arr?.forEach { el ->
                                    val m = el.asSafeObj() ?: return@forEach
                                    val pop = m.safeInt("popularity") ?: 0
                                    val tObj = m.safeObj("title")
                                    tObj?.safeString("romaji")?.lowercase()?.let { map[it] = pop }
                                    tObj?.safeString("english")?.lowercase()?.let { map[it] = pop }
                                }
                            }
                            map
                        }
                    }.getOrDefault(emptyMap())
                }

                Triple(homeDeferred.await(), scheduleDeferred.await(), popularDeferred.await())
            }

            val html = deobfuscateSvelteKitFunctions(homeRawHtml)
            val latestMediaArray = extractJsArray(html, "latestMedia") ?: JsonArray()
            val latestEpisodesArray = extractJsArray(html, "latestEpisodes") ?: JsonArray()

            // 2. Seleccionar 7 animes para el Hero Slider: el más popular de cada día (Lunes = 1 .. Domingo = 7)
            val heroCandidates = mutableListOf<JsonObject>()
            if (scheduleRawHtml.isNotBlank()) {
                val scheduleHtml = deobfuscateSvelteKitFunctions(scheduleRawHtml)
                val scheduleMedia = extractJsArray(scheduleHtml, "media") ?: JsonArray()
                val byDay = HashMap<Int, MutableList<JsonObject>>()

                for (el in scheduleMedia) {
                    val obj = el.asSafeObj() ?: continue
                    val catName = normalizeCategoryType(
                        obj.safeObj("category")?.safeString("name") ?: obj.safeString("category"),
                        null,
                        obj.safeString("title").orEmpty()
                    )
                    if (catName != "TV Anime") continue

                    val createdAtStr = obj.safeObj("latestEpisode")?.safeString("createdAt")
                        ?: obj.safeString("createdAt").orEmpty()
                    if (createdAtStr.isBlank()) continue

                    val dayVal = runCatching {
                        val norm = createdAtStr.trim().replace(" ", "T").let {
                            if (it.endsWith("+00")) "${it}:00" else it
                        }
                        OffsetDateTime.parse(norm, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                            .atZoneSameInstant(zoneId).dayOfWeek.value
                    }.getOrNull() ?: continue

                    byDay.getOrPut(dayVal) { mutableListOf() }.add(obj)
                }

                for (day in 1..7) {
                    val dayList = byDay[day].orEmpty()
                    val bestForDay = dayList.maxByOrNull { cand ->
                        val titleLow = cand.safeString("title").orEmpty().lowercase()
                        val baseTitleLow = titleLow.split(":", " 2nd", " 3rd", " season", " part").first().trim()
                        val popScore = topPopularTitles[titleLow]
                            ?: topPopularTitles.entries.firstOrNull {
                                it.key.contains(baseTitleLow) && baseTitleLow.length >= 4
                            }?.value
                            ?: 0
                        val synBonus = cand.safeString("synopsis").orEmpty().length.coerceAtMost(300)
                        (popScore * 10) + synBonus
                    }
                    if (bestForDay != null && heroCandidates.none { it.safeInt("id") == bestForDay.safeInt("id") }) {
                        heroCandidates.add(bestForDay)
                    }
                }
            }

            // Si algún día no tenía candidato, completamos hasta llegar a 7 con latestMediaArray
            latestMediaArray.forEach { el ->
                val obj = el.asSafeObj()
                if (heroCandidates.size < 7 && obj != null) {
                    val id = obj.safeInt("id") ?: 0
                    val catName = normalizeCategoryType(
                        obj.safeObj("category")?.safeString("name") ?: obj.safeString("category"),
                        null,
                        obj.safeString("title").orEmpty()
                    )
                    if (catName == "TV Anime" && heroCandidates.none { it.safeInt("id") == id }) {
                        heroCandidates.add(obj)
                    }
                }
            }

            val heroItems = coroutineScope {
                heroCandidates.take(7).map { mediaObj ->
                    async {
                        val animeId = mediaObj.safeInt("id") ?: 0
                        val slug = mediaObj.safeString("slug") ?: ""
                        val title = mediaObj.safeString("title") ?: "Anime"
                        val baseSynopsis = mediaObj.safeString("synopsis")?.trim().orEmpty()
                        val categoryName = normalizeCategoryType(
                            mediaObj.safeObj("category")?.safeString("name") ?: mediaObj.safeString("category"),
                            null,
                            title
                        )

                        val pageInfo = getAnimePageInfo(animeId, slug, title, prefs)
                        val aniZip = pageInfo.malId?.let { getAniZipData(it) }
                        val images = extractAniZipImages(aniZip)

                        val hdPoster = images.poster
                            ?: fetchAniListMeta(pageInfo.malId, title)?.posterUrl
                            ?: "https://cdn.animeav1.com/covers/$animeId.jpg"

                        val genresText = if (pageInfo.genres.isNotEmpty()) {
                            pageInfo.genres.take(2).joinToString(" • ")
                        } else {
                            "$categoryName • Estreno"
                        }

                        val finalSynopsis = if (baseSynopsis.length > 40) {
                            baseSynopsis
                        } else {
                            pageInfo.synopsis ?: baseSynopsis.ifBlank { "Estreno de temporada en emisión." }
                        }

                        HeroAnimeItem(
                            animeId = animeId,
                            slug = slug,
                            title = title,
                            categoryName = categoryName,
                            genresText = genresText,
                            synopsis = finalSynopsis,
                            posterUrl = hdPoster,
                            clearLogoUrl = images.clearLogo
                        )
                    }
                }.awaitAll()
            }

            val latestEpisodes = coroutineScope {
                latestEpisodesArray.take(12).mapNotNull { element ->
                    val epObj = element.asSafeObj() ?: return@mapNotNull null
                    async {
                        val epId = epObj.safeInt("id") ?: 0
                        val epNumber = epObj.safeInt("number") ?: 1
                        val publishedAt = epObj.safeString("publishedAt")
                            ?: epObj.safeString("createdAt") ?: ""
                        val mediaObj = epObj.safeObj("media") ?: JsonObject()
                        val animeId = mediaObj.safeInt("id") ?: 0
                        val slug = mediaObj.safeString("slug") ?: ""
                        val animeTitle = mediaObj.safeString("title") ?: "Anime"

                        val pageInfo = getAnimePageInfo(animeId, slug, animeTitle, prefs)
                        val aniZip = pageInfo.malId?.let { getAniZipData(it) }
                        val epDetails = extractAniZipEpisode(aniZip, epNumber, esPrefs)

                        val animeAv1Screenshot = "https://cdn.animeav1.com/screenshots/$animeId/$epNumber.jpg"
                        val screenshotUrl = epDetails.imageUrl ?: animeAv1Screenshot

                        val cleanEpTitle = epDetails.title?.trim()?.takeIf { t ->
                            t.isNotEmpty() &&
                                    !t.equals("Episode $epNumber", ignoreCase = true) &&
                                    !t.equals("Episodio $epNumber", ignoreCase = true) &&
                                    !t.equals("TBA", ignoreCase = true)
                        }

                        LatestEpisodeItem(
                            episodeId = epId,
                            animeId = animeId,
                            slug = slug,
                            animeTitle = animeTitle,
                            episodeNumber = epNumber,
                            episodeTitle = cleanEpTitle,
                            screenshotUrl = screenshotUrl,
                            durationText = "${epDetails.runtime ?: 24}m",
                            timeAgoText = formatTimeAgo(publishedAt)
                        )
                    }
                }.awaitAll()
            }

            HomeData(
                heroItems = heroItems,
                latestEpisodes = latestEpisodes
            )
        }
    }

    suspend fun fetchAnimeDetail(context: Context, slug: String): Result<AnimeDetailData> =
        withContext(Dispatchers.IO) {
            runCatching {
                val prefs = context.getSharedPreferences("waku_mal_cache_v3", Context.MODE_PRIVATE)
                val esPrefs =
                    context.getSharedPreferences("waku_ep_es_cache_v1", Context.MODE_PRIVATE)

                val mainNode = fetchOrGetMediaNode(slug)
                    ?: error("No se pudo cargar la página de $slug")
                val html = mainNode.rawHtml

                val genres = mutableListOf<String>()
                Regex("""genres:\[([^\]]*)\]""").find(html)?.groupValues?.getOrNull(1)
                    ?.let { block ->
                        Regex("""name:"([^"]+)"""").findAll(block).forEach { m ->
                            val g = m.groupValues[1].trim()
                            if (isValidGenreName(g) && !genres.contains(g)) genres.add(g)
                        }
                    }

                val cleanHtml = stripNestedArrays(html)
                val animeId = mainNode.animeId
                val title = mainNode.title

                val synopsis =
                    Regex("""synopsis:"((?:\\.|[^"\\])*)"""").find(cleanHtml)?.groupValues?.getOrNull(
                        1
                    )
                        ?.replace("\\n", "\n")
                        ?.replace("\\\"", "\"")
                        ?.trim()
                        .orEmpty()
                        .ifBlank { "Sinopsis no disponible en español para este título." }

                var malId = mainNode.malId
                val aniListMeta = fetchAniListMeta(malId, title)
                if ((malId == null || malId <= 0) && aniListMeta?.malId != null) {
                    malId = aniListMeta.malId
                }
                if (malId != null && malId > 0 && animeId > 0) {
                    prefs.edit().putInt("mal_$animeId", malId).apply()
                }

                val htmlSaysFinished = cleanHtml.contains("\"finalizado\"", ignoreCase = true) ||
                        cleanHtml.contains("name:\"Finalizado\"", ignoreCase = true)
                val htmlSaysAiring = cleanHtml.contains("\"en-emision\"", ignoreCase = true) ||
                        cleanHtml.contains("name:\"En emisión\"", ignoreCase = true)

                val isAiring = when {
                    aniListMeta?.isAiring != null -> aniListMeta.isAiring
                    htmlSaysFinished -> false
                    htmlSaysAiring -> true
                    else -> false
                }

                val rawScore =
                    Regex("""score:([\d.]+)""").find(cleanHtml)?.groupValues?.getOrNull(1)
                        ?.toDoubleOrNull()
                val scoreText = when {
                    rawScore != null && rawScore > 0 -> String.format(Locale.US, "%.1f", rawScore)
                    !aniListMeta?.score.isNullOrBlank() -> aniListMeta!!.score!!
                    else -> "8.5"
                }

                val yearText = aniListMeta?.year
                    ?: mainNode.year?.toString()
                    ?: "Anime"

                val aniZip = malId?.let { getAniZipData(it) }
                val artwork = extractAniZipImages(aniZip)
                val posterUrl = artwork.poster
                    ?: aniListMeta?.posterUrl
                    ?: "https://cdn.animeav1.com/covers/$animeId.jpg"

                val hasDubOnSeries = checkSeriesHasDub(slug, html)

                val episodesJsonArray = extractJsArray(html, "episodes") ?: JsonArray()

                // Procesamos y traducimos al español los nombres de los episodios en paralelo
                val parsedEpisodes = coroutineScope {
                    episodesJsonArray.mapNotNull { el ->
                        val obj = el.asSafeObj() ?: return@mapNotNull null
                        val epId = obj.safeInt("id") ?: 0
                        val epNum = obj.safeInt("number") ?: return@mapNotNull null

                        async {
                            val aniEp = extractAniZipEpisode(aniZip, epNum, esPrefs)

                            val cleanEpTitle = aniEp.title?.trim()?.takeIf { t ->
                                t.isNotEmpty() &&
                                        !t.equals("Episode $epNum", ignoreCase = true) &&
                                        !t.equals("Episodio $epNum", ignoreCase = true) &&
                                        !t.equals("TBA", ignoreCase = true)
                            } ?: "Episodio $epNum"

                            val screenshot = aniEp.imageUrl
                                ?: "https://cdn.animeav1.com/screenshots/$animeId/$epNum.jpg"

                            AnimeEpisodeItem(
                                episodeId = epId,
                                number = epNum,
                                title = cleanEpTitle,
                                screenshotUrl = screenshot,
                                durationMinutes = aniEp.runtime ?: 24,
                                hasDub = hasDubOnSeries
                            )
                        }
                    }.awaitAll()
                }

                val sortedEpisodes = parsedEpisodes.sortedBy { it.number }
                val seasonTabs = resolveFullFranchiseTabs(slug)

                AnimeDetailData(
                    animeId = animeId,
                    slug = slug,
                    title = title,
                    posterUrl = posterUrl,
                    scoreText = scoreText,
                    yearText = yearText,
                    isAiring = isAiring,
                    totalEpisodes = sortedEpisodes.size,
                    genres = if (genres.isNotEmpty()) genres else listOf("Anime"),
                    synopsis = synopsis,
                    seasonTabs = seasonTabs,
                    episodes = sortedEpisodes
                )
            }
        }

    private fun fetchOrGetMediaNode(slug: String): ParsedMediaNode? {
        if (slug.isBlank()) return null
        mediaNodeCache[slug]?.let { return it }

        return runCatching {
            val req = Request.Builder()
                .url("https://animeav1.com/media/$slug")
                .header("User-Agent", USER_AGENT)
                .build()
            val rawHtml = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string() ?: return null
            }

            val html = deobfuscateSvelteKitFunctions(rawHtml)
            val cleanHtml = stripNestedArrays(html)

            val animeId = Regex("""media:\{id:(\d+)""").find(cleanHtml)?.groupValues?.getOrNull(1)
                ?.toIntOrNull()
                ?: Regex("""\bid:(\d+),malId:""").find(cleanHtml)?.groupValues?.getOrNull(1)
                    ?.toIntOrNull()
                ?: 0

            val title =
                Regex("""title:"((?:\\.|[^"\\])*)"""").find(cleanHtml)?.groupValues?.getOrNull(1)
                    ?.replace("\\\"", "\"") ?: slug

            val rawCategory = Regex("""category:\{[^}]*name:"([^"]+)"""").find(cleanHtml)
                ?.groupValues?.getOrNull(1)
            val categoryName = normalizeCategoryType(rawCategory, null, title)

            val malId = Regex("""\bmalId:(\d+)\b""").find(cleanHtml)?.groupValues?.getOrNull(1)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }

            val year = Regex("""(?:releaseDate|aired|startDate|year):"?(\d{4})""").find(cleanHtml)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()

            val episodesCount = extractJsArray(html, "episodes")?.size() ?: 0

            val relatedSlugs = mutableListOf<String>()
            val relationsArray = extractJsArray(html, "relations") ?: JsonArray()
            relationsArray.forEach { relEl ->
                val relObj = relEl.asSafeObj() ?: return@forEach
                val dest = relObj.safeObj("destination")
                    ?: relObj.safeObj("media")
                    ?: relObj
                val relSlug = dest.safeString("slug").orEmpty()
                if (relSlug.isNotBlank() && relSlug != slug && !relatedSlugs.contains(relSlug)) {
                    relatedSlugs.add(relSlug)
                }
            }

            ParsedMediaNode(
                animeId = animeId,
                slug = slug,
                title = title,
                categoryName = categoryName,
                malId = malId,
                year = year,
                episodesCount = episodesCount,
                relatedSlugs = relatedSlugs,
                rawHtml = html
            ).also { node ->
                mediaNodeCache[slug] = node
            }
        }.getOrNull()
    }

    private fun checkSeriesHasDub(slug: String, mediaHtml: String = ""): Boolean {
        if (slug.isBlank()) return false
        dubSlugCache[slug]?.let { return it }

        if (Regex("""\bDUB\s*:\s*\[(?!\s*\])""").containsMatchIn(mediaHtml)) {
            dubSlugCache[slug] = true
            return true
        }

        val hasDub = runCatching {
            val req = Request.Builder()
                .url("https://animeav1.com/media/$slug/1")
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val epRawHtml = resp.body?.string().orEmpty()
                val epHtml = deobfuscateSvelteKitFunctions(epRawHtml)
                Regex("""\bDUB\s*:\s*\[(?!\s*\])""").containsMatchIn(epHtml)
            }
        }.getOrDefault(false)

        dubSlugCache[slug] = hasDub
        return hasDub
    }

    private suspend fun resolveFullFranchiseTabs(startSlug: String): List<SeasonTabItem> =
        coroutineScope {
            franchiseTabsCache[startSlug]?.let { return@coroutineScope it }

            val visited = LinkedHashMap<String, ParsedMediaNode>()
            var frontier = listOf(startSlug)

            var hops = 0
            while (frontier.isNotEmpty() && hops < 6 && visited.size < 12) {
                val toFetch = frontier.filter { !visited.containsKey(it) }
                if (toFetch.isEmpty()) break

                val fetchedNodes = toFetch.map { s ->
                    async { fetchOrGetMediaNode(s) }
                }.awaitAll().filterNotNull()

                val nextFrontier = mutableSetOf<String>()
                for (node in fetchedNodes) {
                    visited[node.slug] = node
                    for (relSlug in node.relatedSlugs) {
                        if (!visited.containsKey(relSlug)) {
                            nextFrontier.add(relSlug)
                        }
                    }
                }
                frontier = nextFrontier.toList()
                hops++
            }

            val allNodes = visited.values.toList()
            if (allNodes.isEmpty()) return@coroutineScope emptyList()

            if (allNodes.size == 1) {
                val single = allNodes.first()
                val tabs = listOf(
                    SeasonTabItem(
                        animeId = single.animeId,
                        slug = single.slug,
                        label = "Temporada 1",
                        episodesCount = single.episodesCount
                    )
                )
                franchiseTabsCache[startSlug] = tabs
                return@coroutineScope tabs
            }

            val metaBySlug = allNodes.map { node ->
                async {
                    node.slug to fetchAniListMeta(node.malId, node.title)
                }
            }.awaitAll().toMap()

            data class RankedNode(
                val node: ParsedMediaNode,
                val kind: Int,
                val startDateKey: Int,
                val malIdKey: Int
            )

            val ranked = allNodes.map { node ->
                val meta = metaBySlug[node.slug]
                val cat = normalizeCategoryType(node.categoryName, meta?.formatCategory, node.title)

                val kind = when (cat) {
                    "Película" -> 1
                    "OVA", "Especial" -> 2
                    else -> 0
                }

                val dateKey = meta?.startDateKey
                    ?: node.year?.let { it * 10000 + 101 }
                    ?: Int.MAX_VALUE

                val malKey = meta?.malId ?: node.malId ?: Int.MAX_VALUE

                RankedNode(
                    node = node,
                    kind = kind,
                    startDateKey = dateKey,
                    malIdKey = malKey
                )
            }

            val tvNodes = ranked.filter { it.kind == 0 }.sortedWith(
                compareBy<RankedNode> { it.startDateKey }
                    .thenBy { it.malIdKey }
                    .thenBy { if (it.node.animeId > 0) it.node.animeId else Int.MAX_VALUE }
            )

            val movieNodes = ranked.filter { it.kind == 1 }.sortedWith(
                compareBy<RankedNode> { it.startDateKey }
                    .thenBy { it.malIdKey }
            )

            val ovaNodes = ranked.filter { it.kind == 2 }.sortedWith(
                compareBy<RankedNode> { it.startDateKey }
                    .thenBy { it.malIdKey }
            )

            val baseTitle = findCommonFranchisePrefix(tvNodes.map { it.node.title })
            val hasNumberedSequels = tvNodes.drop(1).any {
                extractExplicitSeasonLabel(it.node.title, baseTitle) != null
            }

            val finalTabs = mutableListOf<SeasonTabItem>()

            tvNodes.forEachIndexed { index, item ->
                val rawTitle = item.node.title
                val smartLabel = formatSmartSeasonLabel(
                    rawTitle = rawTitle,
                    baseTitle = baseTitle,
                    isFirstEntry = index == 0,
                    hasNumberedSequels = hasNumberedSequels
                )

                finalTabs.add(
                    SeasonTabItem(
                        animeId = item.node.animeId,
                        slug = item.node.slug,
                        label = smartLabel,
                        episodesCount = item.node.episodesCount
                    )
                )
            }

            movieNodes.forEachIndexed { index, item ->
                val cleanMovie = stripCommonPrefix(item.node.title, baseTitle)
                val label = when {
                    cleanMovie.isNotBlank() && !cleanMovie.equals(
                        item.node.title,
                        ignoreCase = true
                    ) ->
                        "Película: $cleanMovie"

                    movieNodes.size == 1 -> "Película"
                    else -> "Película ${index + 1}"
                }
                finalTabs.add(
                    SeasonTabItem(
                        animeId = item.node.animeId,
                        slug = item.node.slug,
                        label = label,
                        episodesCount = item.node.episodesCount
                    )
                )
            }

            ovaNodes.forEachIndexed { index, item ->
                val cleanOva = stripCommonPrefix(item.node.title, baseTitle)
                val label = when {
                    cleanOva.isNotBlank() && cleanOva.length <= 24 -> "OVA: $cleanOva"
                    ovaNodes.size == 1 -> "OVA / Especial"
                    else -> "OVA ${index + 1}"
                }
                finalTabs.add(
                    SeasonTabItem(
                        animeId = item.node.animeId,
                        slug = item.node.slug,
                        label = label,
                        episodesCount = item.node.episodesCount
                    )
                )
            }

            for (node in allNodes) {
                franchiseTabsCache[node.slug] = finalTabs
            }

            finalTabs
        }

    private fun findCommonFranchisePrefix(titles: List<String>): String {
        if (titles.isEmpty()) return ""
        return titles.first()
            .replace(
                Regex("""(?i)\s*(?:1st\s*season|season\s*1|temporada\s*1|part\s*1|\(tv\))\s*$"""),
                ""
            )
            .trim()
    }

    private fun extractExplicitSeasonLabel(rawTitle: String, baseTitle: String): String? {
        val remainder = stripCommonPrefix(rawTitle, baseTitle)

        val pureSeasonRegex = Regex(
            """^(?i)(?:(\d+)(?:st|nd|rd|th)?\s*season|season\s*(\d+)|temporada\s*(\d+)|S(\d+)|(II|III|IV|V|VI))(?:\s*[-:•]?\s*(?:part|parte|cour)\s*(\d+))?$"""
        )
        val pureMatch = pureSeasonRegex.find(remainder)
        if (pureMatch != null) {
            val sNum =
                pureMatch.groupValues.slice(1..4).firstOrNull { it.isNotEmpty() }?.toIntOrNull()
                    ?: when (pureMatch.groupValues[5].uppercase()) {
                        "II" -> 2; "III" -> 3; "IV" -> 4; "V" -> 5; "VI" -> 6; else -> null
                    }
            val pNum = pureMatch.groupValues[6].toIntOrNull()
            if (sNum != null) {
                return if (pNum != null) "Temporada $sNum • Parte $pNum" else "Temporada $sNum"
            }
        }

        val purePartRegex = Regex("""^(?i)(?:part|parte|cour)\s*(\d+)$""")
        val partMatch = purePartRegex.find(remainder)
        if (partMatch != null) {
            return "Parte ${partMatch.groupValues[1]}"
        }

        return null
    }

    private fun formatSmartSeasonLabel(
        rawTitle: String,
        baseTitle: String,
        isFirstEntry: Boolean,
        hasNumberedSequels: Boolean
    ): String {
        extractExplicitSeasonLabel(rawTitle, baseTitle)?.let { return it }

        if (isFirstEntry) {
            return if (hasNumberedSequels || rawTitle.length > 24) {
                "Temporada 1"
            } else {
                rawTitle
            }
        }

        val stripped = stripCommonPrefix(rawTitle, baseTitle)
        return when {
            stripped.isEmpty() -> rawTitle
            stripped.length <= 3 && rawTitle.length <= 20 -> rawTitle
            else -> stripped
        }
    }

    private fun stripCommonPrefix(rawTitle: String, baseTitle: String): String {
        if (baseTitle.isBlank()) return rawTitle.trim()

        var cleaned = rawTitle.trim()
        if (cleaned.startsWith(baseTitle, ignoreCase = true)) {
            cleaned = cleaned.substring(baseTitle.length)
        } else if (baseTitle.length >= 8 && cleaned.contains(baseTitle, ignoreCase = true)) {
            cleaned = cleaned.replace(Regex(Regex.escape(baseTitle), RegexOption.IGNORE_CASE), " ")
        }

        return cleaned
            .replace(Regex("""^\s*[-:•–~]+\s*"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun stripNestedArrays(html: String): String {
        var result = html
        for (key in listOf("genres", "relations", "episodes")) {
            val token = "$key:["
            val startIdx = result.indexOf(token)
            if (startIdx != -1) {
                val arrayStart = startIdx + key.length + 1
                var depth = 0
                var inString = false
                var escaped = false
                for (i in arrayStart until result.length) {
                    val c = result[i]
                    if (escaped) {
                        escaped = false
                        continue
                    }
                    if (c == '\\') {
                        escaped = true
                        continue
                    }
                    if (c == '"') {
                        inString = !inString
                        continue
                    }
                    if (!inString) {
                        if (c == '[') depth++
                        else if (c == ']') {
                            depth--
                            if (depth == 0) {
                                result = result.removeRange(startIdx, i + 1)
                                break
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    private fun getAnimePageInfo(
        animeId: Int,
        slug: String,
        title: String,
        prefs: SharedPreferences
    ): ScrapedAnimePage {
        pageCache[animeId]?.let { return it }

        val cachedMal = prefs.getInt("mal_$animeId", 0).takeIf { it > 0 }
        val cachedGenres = prefs.getString("genres_$animeId", null)
            ?.split("|")?.filter { isValidGenreName(it) }.orEmpty()

        if (cachedMal != null && cachedGenres.isNotEmpty()) {
            return ScrapedAnimePage(cachedMal, cachedGenres, null).also {
                pageCache[animeId] = it
            }
        }

        var foundMalId: Int? = cachedMal
        val foundGenres = mutableListOf<String>()
        var foundSynopsis: String? = null

        if (slug.isNotBlank()) {
            runCatching {
                val node = fetchOrGetMediaNode(slug)
                if (node != null) {
                    val html = node.rawHtml
                    val genresBlockMatch = Regex("""genres:\[([^\]]*)\]""").find(html)
                    if (genresBlockMatch != null) {
                        val genresBlock = genresBlockMatch.groupValues[1]
                        Regex("""name:"([^"]+)"""").findAll(genresBlock).forEach { m ->
                            val g = m.groupValues[1].trim()
                            if (isValidGenreName(g) && !foundGenres.contains(g)) {
                                foundGenres.add(g)
                            }
                        }
                    }
                    if (node.malId != null && node.malId > 0) {
                        foundMalId = node.malId
                    }
                    val cleanHtml = stripNestedArrays(html)
                    val synMatch = Regex("""synopsis:"((?:\\.|[^"\\])*)"""").find(cleanHtml)
                    foundSynopsis = synMatch?.groupValues?.getOrNull(1)
                        ?.replace("\\n", " ")
                        ?.replace("\\\"", "\"")
                        ?.trim()
                }
            }
        }

        if (foundMalId == null) {
            foundMalId = fetchAniListMeta(null, title)?.malId
        }

        val editor = prefs.edit()
        if (foundMalId != null && foundMalId > 0) {
            editor.putInt("mal_$animeId", foundMalId)
        }
        if (foundGenres.isNotEmpty()) {
            editor.putString("genres_$animeId", foundGenres.joinToString("|"))
        }
        editor.apply()

        return ScrapedAnimePage(
            malId = foundMalId,
            genres = foundGenres,
            synopsis = foundSynopsis
        ).also { pageCache[animeId] = it }
    }

    private fun getAniZipData(malId: Int): JsonObject? {
        if (malId <= 0) return null
        aniZipCache[malId]?.let { return it }

        return runCatching {
            val req = Request.Builder()
                .url("https://api.ani.zip/mappings?mal_id=$malId")
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val parsed = JsonParser.parseString(body).asSafeObj() ?: return null
                aniZipCache[malId] = parsed
                parsed
            }
        }.getOrNull()
    }

    private fun fetchAniListMeta(malId: Int?, title: String): AniListMeta? {
        val cacheKey = if (malId != null && malId > 0) "mal_$malId" else "title_$title"
        aniListMetaCache[cacheKey]?.let { return it }
        if (System.currentTimeMillis() < aniListCooldownUntil) return null

        return runCatching {
            val queryField = if (malId != null && malId > 0) {
                "Media(idMal: $malId, type: ANIME)"
            } else {
                val escapedTitle = title.replace("\"", "\\\"")
                "Media(search: \"$escapedTitle\", type: ANIME)"
            }

            val queryJson =
                """{"query":"{ $queryField { idMal format status seasonYear startDate { year month day } averageScore coverImage { extraLarge } } }"}"""
            val body = queryJson.toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("https://graphql.anilist.co")
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.code == 429) {
                    aniListCooldownUntil = System.currentTimeMillis() + 45_000L
                    return null
                }
                if (!resp.isSuccessful) return null
                val root =
                    JsonParser.parseString(resp.body?.string().orEmpty()).asSafeObj() ?: return null
                val media = root.safeObj("data")?.safeObj("Media") ?: return null

                val foundMal = media.safeInt("idMal")
                val formatStr = media.safeString("format")?.uppercase()
                val formatCat = when (formatStr) {
                    "MOVIE" -> "Película"
                    "OVA", "ONA" -> "OVA"
                    "SPECIAL" -> "Especial"
                    "TV", "TV_SHORT" -> "TV Anime"
                    else -> null
                }

                val statusStr = media.safeString("status")
                val isAiring = statusStr?.equals("RELEASING", ignoreCase = true)

                val startDateObj = media.safeObj("startDate")
                val startYear = startDateObj?.safeInt("year")
                val startMonth = startDateObj?.safeInt("month") ?: 1
                val startDay = startDateObj?.safeInt("day") ?: 1

                val seasonYear = media.safeInt("seasonYear") ?: startYear
                val startDateKey = startYear?.let { it * 10000 + startMonth * 100 + startDay }

                val avgScore = media.safeInt("averageScore")
                val formattedScore = if (avgScore != null && avgScore > 0) {
                    String.format(Locale.US, "%.1f", avgScore / 10.0)
                } else null

                val poster = media.safeObj("coverImage")?.safeString("extraLarge")

                AniListMeta(
                    malId = foundMal,
                    posterUrl = poster,
                    isAiring = isAiring,
                    year = seasonYear?.toString(),
                    startDateKey = startDateKey,
                    score = formattedScore,
                    formatCategory = formatCat
                ).also { meta ->
                    aniListMetaCache[cacheKey] = meta
                    if (foundMal != null) aniListMetaCache["mal_$foundMal"] = meta
                }
            }
        }.getOrNull()
    }

    private data class AniZipArtwork(
        val poster: String?,
        val fanart: String?,
        val clearLogo: String?
    )

    private fun extractAniZipImages(aniZip: JsonObject?): AniZipArtwork {
        if (aniZip == null) return AniZipArtwork(null, null, null)
        var poster: String? = null
        var fanart: String? = null
        var clearLogo: String? = null

        aniZip.safeArray("images")?.forEach { el ->
            val obj = el.asSafeObj() ?: return@forEach
            val type = obj.safeString("coverType")
            val url = obj.safeString("url")
            when (type) {
                "Poster" -> if (poster == null) poster = url
                "Fanart" -> if (fanart == null) fanart = url
                "Clearlogo" -> if (clearLogo == null) clearLogo = url
            }
        }
        return AniZipArtwork(poster, fanart, clearLogo)
    }

    private data class AniZipEpisodeInfo(
        val title: String?,
        val imageUrl: String?,
        val runtime: Int?
    )

    /**
     * Extrae el episodio desde Ani.zip priorizando español ("es"/"es-LA"), y si solo viene
     * en inglés ("en"), lo traduce automáticamente al español y lo guarda en caché.
     */
    private fun extractAniZipEpisode(
        aniZip: JsonObject?,
        episodeNumber: Int,
        esPrefs: android.content.SharedPreferences? = null
    ): AniZipEpisodeInfo {
        if (aniZip == null) return AniZipEpisodeInfo(null, null, null)
        val episodesObj = aniZip.safeObj("episodes") ?: return AniZipEpisodeInfo(null, null, null)
        val epObj = episodesObj.safeObj(episodeNumber.toString())
            ?: return AniZipEpisodeInfo(null, null, null)

        val titleObj = epObj.safeObj("title")
        val finalTitle = if (titleObj != null) {
            // 1° Español oficial -> 2° Inglés oficial -> 3° Japonés (Romaji / Kanji)
            titleObj.safeString("es")
                ?: titleObj.safeString("es-LA")
                ?: titleObj.safeString("es-419")
                ?: titleObj.safeString("es-ES")
                ?: titleObj.safeString("spa")
                ?: titleObj.safeString("en")
                ?: titleObj.safeString("x-jat")
                ?: titleObj.safeString("ja")
        } else {
            epObj.safeString("title")
        }

        val imageUrl = epObj.safeString("image")
        val runtime = epObj.safeInt("runtime") ?: epObj.safeInt("length")

        return AniZipEpisodeInfo(finalTitle, imageUrl, runtime)
    }

    /**
     * Traduce un título de episodio del inglés al español usando el endpoint ligero GTX
     * y lo guarda en caché persistente para que las siguientes aperturas sean instantáneas.
     */

    private fun deobfuscateSvelteKitFunctions(html: String): String {
        var currentHtml = html
        var searchStart = 0

        while (true) {
            val fnIdx = currentHtml.indexOf("(function(", searchStart)
            if (fnIdx == -1) break

            val paramsStart = fnIdx + "(function(".length
            val paramsEnd = currentHtml.indexOf("){return", paramsStart)
            if (paramsEnd == -1 || paramsEnd - paramsStart > 600) {
                searchStart = paramsStart
                continue
            }

            val params = currentHtml.substring(paramsStart, paramsEnd)
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val bodyOpenBrace = currentHtml.indexOf('{', paramsEnd)
            if (bodyOpenBrace == -1) {
                searchStart = paramsEnd
                continue
            }

            val bodyCloseBrace = findMatchingBracket(currentHtml, bodyOpenBrace, '{', '}')
            if (bodyCloseBrace == -1) {
                searchStart = bodyOpenBrace + 1
                continue
            }

            var argsOpenParen = bodyCloseBrace + 1
            while (argsOpenParen < currentHtml.length && currentHtml[argsOpenParen].isWhitespace()) {
                argsOpenParen++
            }
            if (argsOpenParen >= currentHtml.length || currentHtml[argsOpenParen] != '(') {
                searchStart = bodyCloseBrace + 1
                continue
            }

            val argsCloseParen = findMatchingBracket(currentHtml, argsOpenParen, '(', ')')
            if (argsCloseParen == -1) {
                searchStart = argsOpenParen + 1
                continue
            }

            val rawBody = currentHtml.substring(bodyOpenBrace + 1, bodyCloseBrace)
            val rawArgs = currentHtml.substring(argsOpenParen + 1, argsCloseParen)
            val argValues = splitTopLevelArgs(rawArgs)

            if (params.isNotEmpty() && argValues.isNotEmpty()) {
                val paramMap = HashMap<String, String>()
                val count = minOf(params.size, argValues.size)
                for (i in 0 until count) {
                    val rawVal = argValues[i].trim()
                    val cleanVal = when {
                        rawVal == "void 0" || rawVal == "undefined" -> "null"
                        else -> replaceJsIdentifiers(rawVal, paramMap)
                    }
                    paramMap[params[i]] = cleanVal
                }

                val expandedBody = replaceJsIdentifiers(rawBody, paramMap)
                currentHtml = currentHtml.substring(0, fnIdx) +
                        expandedBody +
                        currentHtml.substring(argsCloseParen + 1)
                searchStart = fnIdx + expandedBody.length
            } else {
                searchStart = argsCloseParen + 1
            }
        }
        return currentHtml
    }

    private fun findMatchingBracket(
        text: String,
        openIdx: Int,
        openChar: Char,
        closeChar: Char
    ): Int {
        var depth = 0
        var inDouble = false
        var inSingle = false
        var escaped = false

        for (i in openIdx until text.length) {
            val c = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\') {
                escaped = true
                continue
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble
                continue
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle
                continue
            }
            if (!inDouble && !inSingle) {
                if (c == openChar) depth++
                else if (c == closeChar) {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun splitTopLevelArgs(argsStr: String): List<String> {
        val result = mutableListOf<String>()
        var braceDepth = 0
        var bracketDepth = 0
        var parenDepth = 0
        var inDouble = false
        var inSingle = false
        var escaped = false
        var tokenStart = 0

        for (i in argsStr.indices) {
            val c = argsStr[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\') {
                escaped = true
                continue
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble
                continue
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle
                continue
            }
            if (!inDouble && !inSingle) {
                when (c) {
                    '{' -> braceDepth++
                    '}' -> braceDepth--
                    '[' -> bracketDepth++
                    ']' -> bracketDepth--
                    '(' -> parenDepth++
                    ')' -> parenDepth--
                    ',' -> {
                        if (braceDepth == 0 && bracketDepth == 0 && parenDepth == 0) {
                            result.add(argsStr.substring(tokenStart, i))
                            tokenStart = i + 1
                        }
                    }
                }
            }
        }
        if (tokenStart <= argsStr.length) {
            result.add(argsStr.substring(tokenStart))
        }
        return result
    }

    private fun replaceJsIdentifiers(js: String, paramMap: Map<String, String>): String {
        if (paramMap.isEmpty()) return js
        val sb = StringBuilder(js.length + 256)
        var inDouble = false
        var inSingle = false
        var escaped = false
        var lastNonSpace = ' '
        var i = 0

        while (i < js.length) {
            val c = js[i]
            if (escaped) {
                sb.append(c)
                escaped = false
                i++
                continue
            }
            if (c == '\\') {
                sb.append(c)
                escaped = true
                i++
                continue
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble
                sb.append(c)
                lastNonSpace = '"'
                i++
                continue
            }
            if (c == '\'' && !inSingle) {
                inSingle = !inSingle
                sb.append(c)
                lastNonSpace = '\''
                i++
                continue
            }

            if (!inDouble && !inSingle && (c.isLetter() || c == '_' || c == '$')) {
                var end = i + 1
                while (end < js.length && (js[end].isLetterOrDigit() || js[end] == '_' || js[end] == '$')) {
                    end++
                }
                val ident = js.substring(i, end)

                var nextIdx = end
                while (nextIdx < js.length && js[nextIdx].isWhitespace()) {
                    nextIdx++
                }
                val nextNonSpace = if (nextIdx < js.length) js[nextIdx] else ' '

                val isValuePosition =
                    (lastNonSpace == ':' || lastNonSpace == '[' || lastNonSpace == ',' || lastNonSpace == '(') &&
                            (nextNonSpace == ',' || nextNonSpace == '}' || nextNonSpace == ']' || nextNonSpace == ')')

                val replacement = if (isValuePosition) paramMap[ident] else null
                if (replacement != null) {
                    sb.append(replacement)
                } else {
                    sb.append(ident)
                }
                lastNonSpace = ident.last()
                i = end
                continue
            }

            sb.append(c)
            if (!c.isWhitespace()) {
                lastNonSpace = c
            }
            i++
        }
        return sb.toString()
    }

    private fun extractJsArray(html: String, key: String): JsonArray? {
        val token = "$key:["
        val startIdx = html.indexOf(token)
        if (startIdx == -1) return null

        val arrayStart = startIdx + key.length + 1
        val arrayEnd = findMatchingBracket(html, arrayStart, '[', ']')
        if (arrayEnd == -1) return null

        val rawArray = html.substring(arrayStart, arrayEnd + 1)
        val sanitized = rawArray
            .replace(Regex("""\bvoid\s+0\b"""), "null")
            .replace(Regex("""\bundefined\b"""), "null")
            .replace(Regex("""new\s+Date\(([^)]*)\)"""), "\"$1\"")

        return runCatching {
            val reader = JsonReader(StringReader(sanitized)).apply {
                isLenient = true
            }
            val parsed = JsonParser.parseReader(reader)
            if (parsed.isJsonArray) parsed.asJsonArray else null
        }.getOrNull()
    }

    private fun formatTimeAgo(publishedAt: String): String {
        if (publishedAt.isBlank()) return "RECIENTE"
        return runCatching {
            val normalized = publishedAt.trim().replace(" ", "T").let {
                if (it.endsWith("+00")) "${it}:00" else it
            }
            val publishedInstant =
                OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
            val minutes =
                ChronoUnit.MINUTES.between(publishedInstant, Instant.now()).coerceAtLeast(1)
            when {
                minutes < 60 -> "HACE $minutes MIN"
                minutes < 1440 -> "HACE ${minutes / 60} HORAS"
                else -> "HACE ${minutes / 1440} DÍAS"
            }
        }.getOrDefault("HOY")
    }

    fun genreToSlug(genre: String): String {
        val normalized = Normalizer.normalize(genre.trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return normalized
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    /**
     * Genera variaciones inteligentes de búsqueda (uniendo espacios tras dos puntos como "re: zero" -> "re:zero",
     * uniendo palabras compuestas como "full metal" -> "fullmetal", y resolviendo sinónimos en inglés/español
     * a su título oficial Romaji mediante AniList GraphQL, ej. "demon slayer" -> "Kimetsu no Yaiba").
     */
    private fun resolveSmartSearchQueries(rawQuery: String): List<String> {
        val clean = rawQuery.trim()
        if (clean.isEmpty()) return listOf("")

        val candidates = LinkedHashSet<String>()
        candidates.add(clean)

        // 1. Pegar espacios alrededor de signos de puntuación ("re: zero" -> "re:zero")
        val collapsedPunct = clean.replace(Regex("""\s*([:.\-/])\s*"""), "$1")
        if (collapsedPunct.isNotBlank()) candidates.add(collapsedPunct)

        // 2. Si tiene ":" pegado ("re:zero"), agregar también con espacio ("re zero")
        val noPunct =
            clean.replace(Regex("""[:.\-/]+"""), " ").replace(Regex("""\s+"""), " ").trim()
        if (noPunct.isNotBlank()) candidates.add(noPunct)

        // 3. Si son 2 o más palabras ("full metal alchemist"), probar uniendo las dos primeras ("fullmetal alchemist")
        val words = noPunct.split(" ").filter { it.isNotBlank() }
        if (words.size >= 2) {
            val joinedFirstTwo = words[0] + words[1] +
                    if (words.size > 2) " " + words.drop(2).joinToString(" ") else ""
            candidates.add(joinedFirstTwo)

            // También probar solo la primera palabra compuesta si las dos primeras son cortas ("full metal" -> "fullmetal")
            if (words[0].length <= 6 && words[1].length <= 6) {
                candidates.add(words[0] + words[1])
            }
        }

        // 4. Consultar a AniList GraphQL por sinónimos en otros idiomas (ej. "demon slayer", "attack on titan")
        runCatching {
            val escaped = clean.replace("\"", "\\\"")
            val queryJson =
                """{"query":"{ Page(perPage: 4) { media(search: \"$escaped\", type: ANIME, sort: POPULARITY_DESC) { title { romaji } } } }"}"""
            val body = queryJson.toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("https://graphql.anilist.co")
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val root = JsonParser.parseString(resp.body?.string().orEmpty()).asSafeObj()
                    val mediaArr = root?.safeObj("data")?.safeObj("Page")?.safeArray("media")
                    mediaArr?.forEach { el ->
                        val romaji = el.asSafeObj()?.safeObj("title")?.safeString("romaji")?.trim()
                        if (!romaji.isNullOrBlank()) {
                            // Extraer el nombre base del Romaji antes de ":" o "-" (ej. "Kimetsu no Yaiba")
                            val baseRomaji = romaji.split(":", " - ", " – ").first().trim()
                            if (baseRomaji.length >= 3) {
                                candidates.add(baseRomaji)
                            }
                        }
                    }
                }
            }
        }

        return candidates.take(4).toList()
    }

    /**
     * Descarga el catálogo real aplicando búsqueda inteligente (multiconsulta si hay texto),
     * Tipo de Anime, Géneros, Audio y Año.
     */
    suspend fun fetchCatalogPage(
        context: Context,
        page: Int = 1,
        searchQuery: String = "",
        quickChip: String = "all",
        selectedCategoryType: String = "Todos",
        selectedGenres: Set<String> = emptySet(),
        audioFormat: String = "Todos",
        yearFilter: String = "Todos"
    ): Result<CatalogPageData> = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = context.getSharedPreferences("waku_catalog_meta_v4", Context.MODE_PRIVATE)

            val allRequestedGenres = mutableListOf<String>()
            if (quickChip !in listOf("all", "season", "latino", "movies")) {
                allRequestedGenres.add(quickChip)
            }
            selectedGenres.forEach { g ->
                if (allRequestedGenres.none { it.equals(g, ignoreCase = true) }) {
                    allRequestedGenres.add(g)
                }
            }

            val effectiveCategory = when {
                selectedCategoryType != "Todos" -> selectedCategoryType
                quickChip == "movies" -> "Película"
                else -> "Todos"
            }

            val cleanSearch = searchQuery.trim()

            // Si el usuario escribió en el buscador en la página 1, usamos el motor de búsqueda inteligente en paralelo
            val searchQueriesToRun = if (cleanSearch.isNotEmpty() && page == 1) {
                resolveSmartSearchQueries(cleanSearch)
            } else {
                listOf(cleanSearch)
            }

            val htmlResponses = coroutineScope {
                searchQueriesToRun.map { qVariant ->
                    async {
                        val queryParams = mutableListOf<String>()
                        queryParams.add("page=$page")

                        if (qVariant.isNotEmpty()) {
                            queryParams.add("search=${URLEncoder.encode(qVariant, "UTF-8")}")
                        }

                        if (quickChip == "season") {
                            queryParams.add("status=en-emision")
                        }

                        when (effectiveCategory) {
                            "Película" -> {
                                queryParams.add("category=pelicula")
                                queryParams.add("type=pelicula")
                            }

                            "OVA" -> {
                                queryParams.add("category=ova")
                                queryParams.add("type=ova")
                            }

                            "Especial" -> {
                                queryParams.add("category=especial")
                                queryParams.add("type=especial")
                            }

                            "TV Anime" -> {
                                queryParams.add("category=tv-anime")
                                queryParams.add("type=tv-anime")
                            }
                        }

                        allRequestedGenres.forEach { g ->
                            val slug = genreToSlug(g)
                            queryParams.add("genre=$slug")
                        }

                        when (yearFilter) {
                            "2026", "2025", "2024", "2023", "2022", "2021", "2020" -> {
                                queryParams.add("year=$yearFilter")
                            }

                            "Clásicos" -> {
                                val classicYears =
                                    listOf(2019, 2018, 2017, 2016, 2015, 2014, 2012, 2010)
                                val targetYear = classicYears[(page - 1) % classicYears.size]
                                queryParams.add("year=$targetYear")
                            }
                        }

                        val url = "https://animeav1.com/catalogo?${queryParams.joinToString("&")}"
                        runCatching {
                            deobfuscateSvelteKitFunctions(fetchCatalogHtml(url))
                        }.getOrDefault("")
                    }
                }.awaitAll()
            }

            val itemsMap = LinkedHashMap<Int, CatalogAnimeItem>()
            var detectedTotalCount: Int? = null

            for (html in htmlResponses) {
                if (html.isBlank()) continue
                if (detectedTotalCount == null) {
                    detectedTotalCount =
                        Regex("""\btotal:(\d+)\b""").find(html)?.groupValues?.getOrNull(1)
                            ?.toIntOrNull()
                }

                val rawArray = extractCatalogItemsArray(html)
                for (el in rawArray) {
                    val obj = el.asSafeObj() ?: continue
                    val id = obj.safeInt("id") ?: continue
                    if (itemsMap.containsKey(id)) continue

                    val slug = obj.safeString("slug") ?: continue
                    val title = obj.safeString("title") ?: slug

                    val rawCat = obj.safeObj("category")?.safeString("name")
                        ?: obj.safeString("category")
                    val catName = normalizeCategoryType(rawCat, null, title)

                    val statusName = obj.safeObj("status")?.safeString("name")
                        ?: obj.safeString("status")
                            .orEmpty()

                    val objYear = obj.safeInt("year")
                        ?: Regex("""(\d{4})""").find(
                            obj.safeString("releaseDate") ?: obj.safeString("aired") ?: ""
                        )?.groupValues?.getOrNull(1)?.toIntOrNull()

                    val jsonGenres = mutableListOf<String>()
                    obj.safeArray("genres")?.forEach { g ->
                        val gName = g.asSafeObj()?.safeString("name")
                            ?: if (g.isJsonPrimitive) g.asString else null
                        if (isValidGenreName(gName)) {
                            jsonGenres.add(gName!!.trim())
                        }
                    }

                    val cachedStr = prefs.getString("cat_$id", null)
                    if (cachedStr != null) {
                        val parts = cachedStr.split("|")
                        if (parts.size >= 8) {
                            val cachedPoster = parts[0]
                            val cachedBadge = parts[1]
                            val cachedScore = parts[2]
                            val cachedAiring = parts[3].toBoolean()
                            val cachedGenres = parts[4].split(",").filter { isValidGenreName(it) }
                            val cachedYear = parts[5].toIntOrNull() ?: (objYear ?: 2025)
                            val cachedDub = parts[6].toBoolean()
                            val cachedCategory = normalizeCategoryType(parts[7], null, title)

                            itemsMap[id] = CatalogAnimeItem(
                                animeId = id,
                                slug = slug,
                                title = title,
                                posterUrl = cachedPoster,
                                topLeftBadge = cachedBadge,
                                scoreText = cachedScore,
                                isAiring = cachedAiring,
                                primaryGenre = cachedGenres.firstOrNull() ?: cachedCategory,
                                allGenres = cachedGenres,
                                categoryName = cachedCategory,
                                year = cachedYear,
                                hasDub = cachedDub,
                                isEnriched = true
                            )
                            continue
                        }
                    }

                    val defaultBadge = when (catName) {
                        "Película" -> "Película"
                        "OVA" -> "OVA"
                        "Especial" -> "Especial"
                        else -> "TV Anime"
                    }

                    val initialAiring = statusName.contains("emisión", ignoreCase = true) ||
                            statusName.contains("emision", ignoreCase = true)

                    val initialYear = objYear ?: 2025

                    itemsMap[id] = CatalogAnimeItem(
                        animeId = id,
                        slug = slug,
                        title = title,
                        posterUrl = "https://cdn.animeav1.com/covers/$id.jpg",
                        topLeftBadge = defaultBadge,
                        scoreText = "8.5",
                        isAiring = initialAiring,
                        primaryGenre = jsonGenres.firstOrNull() ?: catName,
                        allGenres = jsonGenres,
                        categoryName = catName,
                        year = initialYear,
                        hasDub = false,
                        isEnriched = false
                    )
                }
            }

            val finalItems = itemsMap.values.toList()
            val finalTotal = if (cleanSearch.isNotEmpty()) {
                finalItems.size
            } else {
                detectedTotalCount ?: 1420
            }

            // En búsqueda por texto, si trajo menos de 18 resultados ya están todos en la página 1
            val hasMore = if (cleanSearch.isNotEmpty()) {
                finalItems.size >= 20
            } else {
                finalItems.size >= 15
            }

            CatalogPageData(
                items = finalItems,
                totalCount = finalTotal,
                hasNextPage = hasMore,
                availableGenres = DEFAULT_OFFICIAL_GENRES
            )
        }
    }

    private fun fetchCatalogHtml(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Error HTTP ${resp.code} al cargar el catálogo")
            resp.body?.string() ?: error("Respuesta vacía del catálogo")
        }
    }

    private fun extractCatalogItemsArray(html: String): JsonArray {
        return listOf("results", "items", "media", "latestMedia", "animes", "data")
            .firstNotNullOfOrNull { key ->
                extractJsArray(html, key)?.takeIf { arr ->
                    arr.size() > 0 && arr.first().asSafeObj()?.has("slug") == true
                }
            } ?: JsonArray()
    }

    suspend fun enrichCatalogItem(
        context: Context,
        item: CatalogAnimeItem,
        verifyDubOnEp1: Boolean = false
    ): CatalogAnimeItem = withContext(Dispatchers.IO) {
        if (item.isEnriched && (!verifyDubOnEp1 || dubSlugCache.containsKey(item.slug))) {
            val knownDub = dubSlugCache[item.slug] ?: item.hasDub
            return@withContext item.copy(hasDub = knownDub)
        }

        runCatching {
            val prefs = context.getSharedPreferences("waku_catalog_meta_v4", Context.MODE_PRIVATE)
            val node = fetchOrGetMediaNode(item.slug)
            val html = node?.rawHtml.orEmpty()

            val genres = mutableListOf<String>()
            Regex("""genres:\[([^\]]*)\]""").find(html)?.groupValues?.getOrNull(1)?.let { block ->
                Regex("""name:"([^"]+)"""").findAll(block).forEach { m ->
                    val gName = m.groupValues[1].trim()
                    if (isValidGenreName(gName) && !genres.contains(gName)) {
                        genres.add(gName)
                    }
                }
            }
            if (genres.isEmpty()) {
                genres.addAll(item.allGenres.filter { isValidGenreName(it) })
            }

            val cleanHtml = if (html.isNotEmpty()) stripNestedArrays(html) else ""
            val aniMeta = fetchAniListMeta(node?.malId, item.title)

            val resolvedCategory = normalizeCategoryType(
                node?.categoryName ?: item.categoryName,
                aniMeta?.formatCategory,
                item.title
            )

            val htmlSaysFinished = cleanHtml.contains("\"finalizado\"", ignoreCase = true) ||
                    cleanHtml.contains("name:\"Finalizado\"", ignoreCase = true)
            val htmlSaysAiring = cleanHtml.contains("\"en-emision\"", ignoreCase = true) ||
                    cleanHtml.contains("name:\"En emisión\"", ignoreCase = true)

            val isAiring = when {
                aniMeta?.isAiring != null -> aniMeta.isAiring
                htmlSaysFinished -> false
                htmlSaysAiring -> true
                else -> item.isAiring
            }

            val rawScore = Regex("""score:([\d.]+)""").find(cleanHtml)?.groupValues?.getOrNull(1)
                ?.toDoubleOrNull()
            val scoreText = when {
                rawScore != null && rawScore > 0 -> String.format(Locale.US, "%.1f", rawScore)
                !aniMeta?.score.isNullOrBlank() -> aniMeta!!.score!!
                else -> "8.4"
            }

            val year = aniMeta?.year?.toIntOrNull()
                ?: node?.year
                ?: item.year

            val epsCount = node?.episodesCount ?: 0
            val topLeftBadge = when {
                resolvedCategory == "Película" -> "Película"
                resolvedCategory == "OVA" -> if (epsCount > 0) "OVA • $epsCount Eps" else "OVA"
                resolvedCategory == "Especial" -> "Especial"
                epsCount > 0 -> "$epsCount Eps"
                else -> resolvedCategory
            }

            val posterUrl = aniMeta?.posterUrl
                ?: "https://cdn.animeav1.com/covers/${item.animeId}.jpg"

            val hasDub = checkSeriesHasDub(item.slug, html)

            val finalGenres = if (genres.isNotEmpty()) genres else listOf(resolvedCategory)

            val cacheValue = listOf(
                posterUrl,
                topLeftBadge,
                scoreText,
                isAiring.toString(),
                finalGenres.joinToString(","),
                year.toString(),
                hasDub.toString(),
                resolvedCategory
            ).joinToString("|")
            prefs.edit().putString("cat_${item.animeId}", cacheValue).apply()

            item.copy(
                posterUrl = posterUrl,
                topLeftBadge = topLeftBadge,
                scoreText = scoreText,
                isAiring = isAiring,
                primaryGenre = finalGenres.first(),
                allGenres = finalGenres,
                categoryName = resolvedCategory,
                year = year,
                hasDub = hasDub,
                isEnriched = true
            )
        }.getOrDefault(item.copy(isEnriched = true))
    }

    // =========================================================================
    // MOTOR DE HORARIO SIMULCAST SEMANAL (AnimeAV1 + AniList AiringSchedule)
    // =========================================================================
    // =========================================================================
    // MOTOR DE HORARIO SIMULCAST SEMANAL (Directo desde animeav1.com/horario)
    // =========================================================================
    private var cachedScheduleList: List<ScheduleAnimeItem>? = null

    suspend fun fetchWeeklySchedule(
        context: Context,
        forceRefresh: Boolean = false
    ): Result<List<ScheduleAnimeItem>> = withContext(Dispatchers.IO) {
        runCatching {
            cachedScheduleList?.takeIf { !forceRefresh && it.isNotEmpty() }?.let {
                return@runCatching it
            }

            val zoneId = java.time.ZoneId.systemDefault()
            val nowZoned = java.time.ZonedDateTime.now(zoneId)
            val todayLocalDate = nowZoned.toLocalDate()
            val todayDayOfWeek = nowZoned.dayOfWeek.value // 1 = Lunes ... 7 = Domingo

            // 1. Descargar directamente https://animeav1.com/horario (trae los ~47 animes de la semana)
            val req = Request.Builder()
                .url("https://animeav1.com/horario")
                .header("User-Agent", USER_AGENT)
                .build()

            val rawHtml = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Error HTTP ${resp.code} al cargar el horario")
                resp.body?.string() ?: error("Respuesta vacía de /horario")
            }

            val html = deobfuscateSvelteKitFunctions(rawHtml)
            val mediaArray = extractJsArray(html, "media") ?: JsonArray()

            data class RawScheduleEntry(
                val animeId: Int,
                val slug: String,
                val title: String,
                val categoryName: String,
                val latestEpNumber: Int,
                val latestEpZdt: java.time.ZonedDateTime
            )

            val rawEntries = mutableListOf<RawScheduleEntry>()

            for (el in mediaArray) {
                val obj = el.asSafeObj() ?: continue
                val id = obj.safeInt("id") ?: continue
                val slug = obj.safeString("slug") ?: continue
                val title = obj.safeString("title") ?: slug

                val rawCat = obj.safeObj("category")?.safeString("name")
                    ?: obj.safeString("category")
                val catName = normalizeCategoryType(rawCat, null, title)

                val latestEpObj = obj.safeObj("latestEpisode")
                val epNum = latestEpObj?.safeInt("number") ?: 1
                val createdAtStr = latestEpObj?.safeString("createdAt")
                    ?: obj.safeString("createdAt").orEmpty()

                if (createdAtStr.isBlank()) continue

                val parsedZdt = runCatching {
                    val normalized = createdAtStr.trim().replace(" ", "T").let {
                        if (it.endsWith("+00")) "${it}:00" else it
                    }
                    OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                        .atZoneSameInstant(zoneId)
                }.getOrNull() ?: continue

                rawEntries.add(
                    RawScheduleEntry(
                        animeId = id,
                        slug = slug,
                        title = title,
                        categoryName = catName,
                        latestEpNumber = epNum,
                        latestEpZdt = parsedZdt
                    )
                )
            }

            // 2. Enriquecer solo póster HD y total de episodios en paralelo sin alterar el día ni la hora de AnimeAV1
            val scheduleItems = coroutineScope {
                rawEntries.map { entry ->
                    async {
                        aniListBgSemaphore.withPermit {
                            val aniInfo = fetchAniListScheduleInfo(context, entry.title)
                            val posterUrl = aniInfo?.posterUrl
                                ?: "https://cdn.animeav1.com/covers/${entry.animeId}.jpg"
                            val totalEps = aniInfo?.totalEpisodes

                            // El día de la semana y la hora local vienen 100% de latestEpisode.createdAt de AnimeAV1
                            val dayOfWeek = entry.latestEpZdt.dayOfWeek.value
                            val airTimeLocal = entry.latestEpZdt.toLocalTime()
                            val daysSinceLastEp = ChronoUnit.DAYS.between(
                                entry.latestEpZdt.toLocalDate(),
                                todayLocalDate
                            )

                            val time12h = airTimeLocal
                                .format(DateTimeFormatter.ofPattern("hh:mm a", Locale.US))
                                .lowercase()
                            val time24h = airTimeLocal
                                .format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))

                            val displayEpNumber: Int
                            val statusType: ScheduleStatusType
                            val statusText: String

                            when {
                                // Si su último capítulo salió hace más de 9 días (ej. #Zombie Sagashitemasu), está retrasado
                                daysSinceLastEp > 9 -> {
                                    displayEpNumber = entry.latestEpNumber + 1
                                    statusType = ScheduleStatusType.DELAYED
                                    statusText = "⚠ Retrasado"
                                }

                                // Si el capítulo se publicó esta misma semana (hace menos de 6 días)
                                daysSinceLastEp < 6 && dayOfWeek <= todayDayOfWeek -> {
                                    displayEpNumber = entry.latestEpNumber
                                    statusType = ScheduleStatusType.AIRED
                                    statusText = time12h
                                }

                                // Si corresponde a HOY pero aún no se ha subido el capítulo de hoy (el registrado es de hace 7 días)
                                dayOfWeek == todayDayOfWeek -> {
                                    displayEpNumber = entry.latestEpNumber + 1
                                    val minutesUntil = ChronoUnit.MINUTES.between(
                                        nowZoned.toLocalTime(),
                                        airTimeLocal
                                    )
                                    if (minutesUntil in 1..180) {
                                        val hours = (minutesUntil / 60).coerceAtLeast(1)
                                        statusType = ScheduleStatusType.UPCOMING_SOON
                                        statusText = if (minutesUntil < 60) {
                                            "En ${minutesUntil}m • $time24h"
                                        } else {
                                            "En ${hours}h • $time24h"
                                        }
                                    } else {
                                        statusType = ScheduleStatusType.UPCOMING_LATER
                                        statusText = "Hoy • $time24h"
                                    }
                                }

                                // Días futuros de esta semana (ej. Viernes, Sábado, Domingo vistos un Jueves)
                                else -> {
                                    displayEpNumber = if (daysSinceLastEp >= 4) {
                                        entry.latestEpNumber + 1
                                    } else {
                                        entry.latestEpNumber
                                    }
                                    statusType = ScheduleStatusType.UPCOMING_LATER
                                    statusText = time12h
                                }
                            }

                            val isFinale =
                                totalEps != null && totalEps > 1 && displayEpNumber >= totalEps

                            ScheduleAnimeItem(
                                animeId = entry.animeId,
                                slug = entry.slug,
                                title = entry.title,
                                posterUrl = posterUrl,
                                categoryName = entry.categoryName,
                                dayOfWeek = dayOfWeek,
                                episodeNumber = displayEpNumber,
                                totalEpisodes = totalEps,
                                isFinale = isFinale,
                                statusType = statusType,
                                statusBadgeText = statusText,
                                sortMinuteOfDay = airTimeLocal.hour * 60 + airTimeLocal.minute
                            )
                        }
                    }
                }.awaitAll()
            }

            val sorted = scheduleItems.sortedBy { it.sortMinuteOfDay }
            cachedScheduleList = sorted
            sorted
        }
    }

    private data class AniListScheduleMeta(
        val posterUrl: String?,
        val totalEpisodes: Int?
    )

    private val aniScheduleCache = ConcurrentHashMap<String, AniListScheduleMeta>()
    // Freno global: solo permite 2 consultas simultáneas en segundo plano y pausa si AniList da 429
    private val aniListBgSemaphore = Semaphore(2)
    @Volatile
    private var aniListCooldownUntil: Long = 0L

    private fun fetchAniListScheduleInfo(context: Context, title: String): AniListScheduleMeta? {
        aniScheduleCache[title]?.let { return it }

        val prefs = context.getSharedPreferences("waku_schedule_cache_v1", Context.MODE_PRIVATE)
        val cachedStr = prefs.getString("sched_$title", null)
        if (cachedStr != null) {
            val parts = cachedStr.split("|")
            val poster = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
            val eps = parts.getOrNull(1)?.toIntOrNull()
            return AniListScheduleMeta(poster, eps).also { aniScheduleCache[title] = it }
        }

        // Si AniList está en enfriamiento por 429, no gastamos más peticiones en segundo plano
        if (System.currentTimeMillis() < aniListCooldownUntil) return null

        return runCatching {
            val escaped = title.replace("\"", "\\\"")
            val queryJson =
                """{"query":"{ Media(search: \"$escaped\", type: ANIME) { episodes coverImage { extraLarge } } }"}"""
            val body = queryJson.toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("https://graphql.anilist.co")
                .header("User-Agent", USER_AGENT)
                .post(body)
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.code == 429) {
                    aniListCooldownUntil = System.currentTimeMillis() + 45_000L
                    return null
                }
                if (!resp.isSuccessful) return null
                val root =
                    JsonParser.parseString(resp.body?.string().orEmpty()).asSafeObj() ?: return null
                val media = root.safeObj("data")?.safeObj("Media") ?: return null

                val totalEps = media.safeInt("episodes")
                val poster = media.safeObj("coverImage")?.safeString("extraLarge")

                prefs.edit()
                    .putString("sched_$title", "${poster.orEmpty()}|${totalEps ?: ""}")
                    .apply()

                AniListScheduleMeta(
                    posterUrl = poster,
                    totalEpisodes = totalEps
                ).also { aniScheduleCache[title] = it }
            }
        }.getOrNull()
    }

    // =========================================================================
    // SELECTOR DE AVATARES POR SERIE (100% AniList con Caché y Reintento Anti-429)
    // =========================================================================
    private var defaultAvatarGroupsCache: List<SeriesAvatarGroup>? = null
    private val avatarSearchCache = ConcurrentHashMap<String, List<SeriesAvatarGroup>>()

    suspend fun fetchCharacterAvatarsBySeries(
        searchQuery: String = ""
    ): Result<List<SeriesAvatarGroup>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanQuery = searchQuery.trim()
            val cacheKey = cleanQuery.lowercase()

            if (cleanQuery.isEmpty() && !defaultAvatarGroupsCache.isNullOrEmpty()) {
                return@runCatching defaultAvatarGroupsCache!!
            }
            avatarSearchCache[cacheKey]?.let { cached ->
                if (cached.isNotEmpty()) return@runCatching cached
            }

            val graphqlQuery = if (cleanQuery.isEmpty()) {
                """
                {
                  bySeries: Page(perPage: 12) {
                    media(type: ANIME, sort: POPULARITY_DESC) {
                      id
                      title { romaji }
                      characters(sort: [ROLE, FAVOURITES_DESC], perPage: 6) {
                        nodes {
                          id
                          name { full }
                          image { large }
                        }
                      }
                    }
                  }
                }
                """.trimIndent()
            } else {
                val safeQ = cleanQuery.replace("\\", "\\\\").replace("\"", "\\\"")
                """
                {
                  bySeries: Page(perPage: 8) {
                    media(search: "$safeQ", type: ANIME) {
                      id
                      title { romaji }
                      characters(sort: [ROLE, FAVOURITES_DESC], perPage: 6) {
                        nodes {
                          id
                          name { full }
                          image { large }
                        }
                      }
                    }
                  }
                  byCharacter: Page(perPage: 6) {
                    characters(search: "$safeQ", sort: FAVOURITES_DESC) {
                      id
                      name { full }
                      image { large }
                    }
                  }
                }
                """.trimIndent()
            }

            val payload = JsonObject().apply {
                addProperty("query", graphqlQuery)
            }.toString()

            fun executeAniListAvatarRequest(): okhttp3.Response {
                val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder()
                    .url("https://graphql.anilist.co")
                    .header("User-Agent", USER_AGENT)
                    .post(body)
                    .build()
                return client.newCall(req).execute()
            }

            var response = executeAniListAvatarRequest()

            // Si justo había un pico de peticiones (429), pausamos las tareas de fondo, esperamos 2.2s y reintentamos
            if (response.code == 429) {
                response.close()
                aniListCooldownUntil = System.currentTimeMillis() + 45_000L
                kotlinx.coroutines.delay(2200)
                response = executeAniListAvatarRequest()
            }

            val groups = response.use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 429) {
                        error("AniList está recibiendo muchas consultas. Espera unos segundos y vuelve a intentar.")
                    } else {
                        error("Error HTTP ${resp.code} al buscar en AniList")
                    }
                }
                val root = JsonParser.parseString(resp.body?.string().orEmpty()).asSafeObj()
                    ?: error("Respuesta vacía de AniList")

                val dataObj = root.safeObj("data") ?: JsonObject()
                val resultGroups = mutableListOf<SeriesAvatarGroup>()
                val seenCharIds = HashSet<Int>()

                if (cleanQuery.isNotEmpty()) {
                    val directCharsArray = dataObj.safeObj("byCharacter")?.safeArray("characters")
                    if (directCharsArray != null) {
                        val directMatchedChars = mutableListOf<CharacterAvatarItem>()
                        for (charEl in directCharsArray) {
                            val charObj = charEl.asSafeObj() ?: continue
                            val charId = charObj.safeInt("id") ?: continue
                            val charName = charObj.safeObj("name")?.safeString("full")?.trim().orEmpty()
                            val charImg = charObj.safeObj("image")?.safeString("large")?.trim().orEmpty()

                            if (charImg.isNotBlank() && !charImg.endsWith("default.jpg")) {
                                directMatchedChars.add(CharacterAvatarItem(charId, charName, charImg))
                                seenCharIds.add(charId)
                            }
                        }
                        if (directMatchedChars.isNotEmpty()) {
                            resultGroups.add(
                                SeriesAvatarGroup(
                                    animeId = -1,
                                    seriesTitle = "Personajes encontrados",
                                    characters = directMatchedChars
                                )
                            )
                        }
                    }
                }

                val mediaArray = dataObj.safeObj("bySeries")?.safeArray("media") ?: JsonArray()
                val minCharsRequired = if (cleanQuery.isEmpty()) 3 else 1

                for (el in mediaArray) {
                    val mediaObj = el.asSafeObj() ?: continue
                    val animeId = mediaObj.safeInt("id") ?: continue
                    val romajiTitle = mediaObj.safeObj("title")?.safeString("romaji") ?: continue
                    val nodes = mediaObj.safeObj("characters")?.safeArray("nodes") ?: continue

                    val charactersList = mutableListOf<CharacterAvatarItem>()
                    for (charEl in nodes) {
                        val charObj = charEl.asSafeObj() ?: continue
                        val charId = charObj.safeInt("id") ?: continue
                        val charName = charObj.safeObj("name")?.safeString("full")?.trim().orEmpty()
                        val charImg = charObj.safeObj("image")?.safeString("large")?.trim().orEmpty()

                        if (charImg.isNotBlank() && !charImg.endsWith("default.jpg")) {
                            charactersList.add(
                                CharacterAvatarItem(
                                    characterId = charId,
                                    name = charName,
                                    imageUrl = charImg
                                )
                            )
                        }
                    }

                    if (charactersList.size >= minCharsRequired) {
                        resultGroups.add(
                            SeriesAvatarGroup(
                                animeId = animeId,
                                seriesTitle = romajiTitle,
                                characters = charactersList
                            )
                        )
                    }
                }

                resultGroups
            }

            if (groups.isNotEmpty()) {
                if (cleanQuery.isEmpty()) {
                    defaultAvatarGroupsCache = groups
                }
                avatarSearchCache[cacheKey] = groups
            }
            groups
        }
    }

    // =========================================================================
    // EXTRACTOR DE SERVIDORES DE VIDEO POR EPISODIO (/media/{slug}/{number})
    // =========================================================================
    suspend fun fetchEpisodeStreams(
        context: Context,
        slug: String,
        episodeNumber: Int
    ): Result<EpisodeStreamsData> = withContext(Dispatchers.IO) {
        runCatching {
            val epUrl = "https://animeav1.com/media/$slug/$episodeNumber"

            val (detail, rawHtml) = coroutineScope {
                val detailDeferred = async { fetchAnimeDetail(context, slug).getOrNull() }
                val htmlDeferred = async {
                    val req = Request.Builder()
                        .url(epUrl)
                        .header("User-Agent", USER_AGENT)
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("Error HTTP ${resp.code} al cargar el episodio$episodeNumber")
                        resp.body?.string() ?: error("Respuesta vacía del episodio")
                    }
                }
                detailDeferred.await() to htmlDeferred.await()
            }

            val html = deobfuscateSvelteKitFunctions(rawHtml)

            val subServers = mutableListOf<EpisodeStreamServer>()
            val dubServers = mutableListOf<EpisodeStreamServer>()

            // Extraer únicamente reproductores de embeds:{SUB:[...],DUB:[...]} (Sin links de descarga)
            val embedsIdx = html.indexOf("embeds:{")
            if (embedsIdx != -1) {
                val openBrace = embedsIdx + "embeds:".length
                val closeBrace = findMatchingBracket(html, openBrace, '{', '}')
                if (closeBrace != -1) {
                    val embedsBlock = html.substring(openBrace, closeBrace + 1)
                    extractJsArray(embedsBlock, "SUB")?.let { parseServerJsonArray(it, "SUB", subServers) }
                    extractJsArray(embedsBlock, "DUB")?.let { parseServerJsonArray(it, "DUB", dubServers) }

                    if (subServers.isEmpty()) extractServersByRegex(embedsBlock, "SUB", subServers)
                    if (dubServers.isEmpty()) extractServersByRegex(embedsBlock, "DUB", dubServers)
                }
            }

            if (subServers.isEmpty()) {
                extractJsArray(html, "SUB")?.let { parseServerJsonArray(it, "SUB", subServers) }
                if (subServers.isEmpty()) extractServersByRegex(html, "SUB", subServers)
            }
            if (dubServers.isEmpty()) {
                extractJsArray(html, "DUB")?.let { parseServerJsonArray(it, "DUB", dubServers) }
                if (dubServers.isEmpty()) extractServersByRegex(html, "DUB", dubServers)
            }

            // Priorizar siempre HLS primero por defecto
            // Priorizar siempre HLS -> Voe -> Byse -> MP4Upload, dejando UPNShare al final
            fun sortServersForPlayer(list: List<EpisodeStreamServer>): List<EpisodeStreamServer> {
                return list.sortedBy { srv ->
                    val n = srv.name.lowercase()
                    val u = srv.embedUrl.lowercase()
                    when {
                        n.contains("hls") || u.contains("zilla-networks") -> 0
                        n.contains("voe") || u.contains("voe") -> 1
                        n.contains("byse") || u.contains("byse") || u.contains("n1mwq") -> 2
                        n.contains("mp4upload") || u.contains("mp4upload") -> 3
                        n.contains("p2p") -> 4
                        n.contains("wish") || n.contains("sw") || u.contains("wish") -> 5
                        n.contains("hide") || n.contains("lions") || u.contains("hide") -> 6
                        n.contains("moon") || u.contains("filemoon") -> 7
                        n.contains("yourupload") || u.contains("yourupload") -> 8
                        n.contains("upn") || u.contains("upn") -> 9
                        n.contains("mega") || u.contains("mega.nz") -> 10
                        else -> 8
                    }
                }
            }

            val epItem = detail?.episodes?.find { it.number == episodeNumber }
            val totalEps = detail?.totalEpisodes ?: episodeNumber
            val hasNext = detail?.episodes?.any { it.number == episodeNumber + 1 } ?: false

            EpisodeStreamsData(
                animeId = detail?.animeId ?: 0,
                slug = slug,
                animeTitle = detail?.title ?: slug,
                posterUrl = detail?.posterUrl.orEmpty(),
                episodeNumber = episodeNumber,
                episodeTitle = epItem?.title ?: "Episodio $episodeNumber",
                totalEpisodes = totalEps,
                hasNextEpisode = hasNext,
                subServers = sortServersForPlayer(subServers),
                dubServers = sortServersForPlayer(dubServers)
            )
        }
    }

    private fun parseServerJsonArray(
        arr: JsonArray,
        lang: String,
        targetList: MutableList<EpisodeStreamServer>
    ) {
        for (el in arr) {
            val obj = el.asSafeObj() ?: continue
            var foundName: String? = obj.safeString("server")
                ?: obj.safeString("name")
                ?: obj.safeString("title")
            var foundUrl: String? = obj.safeString("url")
                ?: obj.safeString("link")
                ?: obj.safeString("src")
                ?: obj.safeString("code")
                ?: obj.safeString("file")
                ?: obj.safeString("embed")

            for ((_, v) in obj.entrySet()) {
                if (v.isJsonPrimitive) {
                    val str = runCatching { v.asString.trim() }.getOrNull().orEmpty()
                    if (str.startsWith("http://") || str.startsWith("https://") || str.startsWith("//")) {
                        if (foundUrl.isNullOrBlank()) foundUrl = str
                    } else if (str.length in 2..20 && foundName.isNullOrBlank()) {
                        foundName = str
                    }
                }
            }

            val cleanUrl = foundUrl?.replace("\\/", "/")?.let {
                if (it.startsWith("//")) "https:$it" else it
            }.orEmpty()

            if (cleanUrl.startsWith("http")) {
                val hostName = runCatching {
                    android.net.Uri.parse(cleanUrl).host?.substringBeforeLast(".")?.substringAfterLast(".")
                        ?.replaceFirstChar { it.uppercase() }
                }.getOrNull()
                val label = foundName?.takeIf { it.isNotBlank() } ?: hostName ?: "Servidor"

                if (targetList.none { it.embedUrl == cleanUrl }) {
                    targetList.add(EpisodeStreamServer(name = label, embedUrl = cleanUrl, lang = lang))
                }
            }
        }
    }

    private fun extractServersByRegex(
        source: String,
        lang: String,
        targetList: MutableList<EpisodeStreamServer>
    ) {
        val token = "$lang:["
        val startIdx = source.indexOf(token)
        if (startIdx == -1) return
        val arrayStart = startIdx + token.length - 1
        val arrayEnd = findMatchingBracket(source, arrayStart, '[', ']')
        if (arrayEnd == -1) return

        val block = source.substring(arrayStart, arrayEnd + 1).replace("\\/", "/")
        val objRegex = Regex("""\{([^{}]+)\}""")
        objRegex.findAll(block).forEach { match ->
            val props = match.groupValues[1]
            val urlMatch = Regex("""(https?://[^\s"'<>\\,]+)""").find(props) ?: return@forEach
            val url = urlMatch.groupValues[1]
            val nameMatch = Regex("""(?:server|name|title)\s*:\s*"([^"]+)"""").find(props)
            val hostFallback = runCatching {
                android.net.Uri.parse(url).host?.substringBeforeLast(".")?.substringAfterLast(".")
                    ?.replaceFirstChar { it.uppercase() }
            }.getOrNull() ?: "Servidor"
            val name = nameMatch?.groupValues?.getOrNull(1) ?: hostFallback

            if (targetList.none { it.embedUrl == url }) {
                targetList.add(EpisodeStreamServer(name = name, embedUrl = url, lang = lang))
            }
        }
    }

    suspend fun tryExtractDirectVideoUrl(embedUrl: String): Pair<String, Map<String, String>>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val lower = embedUrl.lowercase()
                val origin = runCatching {
                    val u = android.net.Uri.parse(embedUrl)
                    "${u.scheme}://${u.host}"
                }.getOrDefault("https://player.zilla-networks.com")

                // -----------------------------------------------------------------
                // 1. SERVIDOR HLS (PlayerZilla: player.zilla-networks.com/play/...)
                // Usamos `client` estándar + cabeceras Sec-Fetch para evitar HTTP 403 en init.html
                // -----------------------------------------------------------------
                if (lower.contains("zilla-networks.com/play/")) {
                    var sessionCookie = ""
                    var playHtml = ""

                    runCatching {
                        val playReq = Request.Builder()
                            .url(embedUrl)
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", "https://animeav1.com/")
                            .header("Sec-Fetch-Dest", "iframe")
                            .header("Sec-Fetch-Mode", "navigate")
                            .header("Sec-Fetch-Site", "cross-site")
                            .build()
                        client.newCall(playReq).execute().use { resp ->
                            val cookies = resp.headers("Set-Cookie")
                                .mapNotNull { it.substringBefore(";").trim().takeIf { c -> c.isNotEmpty() } }
                            if (cookies.isNotEmpty()) {
                                sessionCookie = cookies.joinToString("; ")
                            }
                            playHtml = resp.body?.string().orEmpty()
                        }
                    }

                    val extractedM3u8 = Regex("""(https?://[^\s"'<>\\]+/m3u8/[^\s"'<>\\]+)""")
                        .find(playHtml)?.groupValues?.getOrNull(1)
                        ?: Regex("""(/m3u8/[a-zA-Z0-9_-]+(?:\?[^\s"'<>\\]+)?)""")
                            .find(playHtml)?.groupValues?.getOrNull(1)?.let { "$origin$it" }
                        ?: embedUrl.replace("/play/", "/m3u8/")

                    val zillaHeaders = HashMap<String, String>().apply {
                        put("Referer", embedUrl)
                        put("Origin", origin)
                        put("Accept", "*/*")
                        put("Sec-Fetch-Dest", "empty")
                        put("Sec-Fetch-Mode", "cors")
                        put("Sec-Fetch-Site", "same-origin")
                        if (sessionCookie.isNotBlank()) {
                            put("Cookie", sessionCookie)
                        }
                    }
                    return@withContext extractedM3u8 to zillaHeaders
                }

                // -----------------------------------------------------------------
                // 2. SERVIDOR BYSE (Extracción 100% nativa: Challenge -> Attest -> PoW -> Playback)
                // -----------------------------------------------------------------
                if (lower.contains("byse") || lower.contains("n1mwq")) {
                    val byseResult = extractByseFromApi(embedUrl)
                    if (byseResult != null) {
                        return@withContext byseResult
                    }
                    return@withContext null
                }

                // -----------------------------------------------------------------
                // 3. DESCARGAR HTML DEL EMBED (VOE, MP4Upload, etc.)
                // -----------------------------------------------------------------
                val activeClient = if (lower.contains("mp4upload")) streamHttpClient else client

                val initialReq = Request.Builder()
                    .url(embedUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://animeav1.com/")
                    .build()

                var currentUrl = embedUrl
                var body = activeClient.newCall(initialReq).execute().use { resp ->
                    currentUrl = resp.request.url.toString()
                    if (!resp.isSuccessful) return@use ""
                    resp.body?.string().orEmpty()
                }
                if (body.isBlank()) return@withContext null

                // Seguir redirección por JS (ej. voe.sx -> susanexpectcare.com)
                val jsRedirect = Regex(
                    """(?:window\.location\.href|location\.href|window\.location\.replace)\s*(?:=|\()\s*['"](https?://[^'"]+)['"]"""
                ).find(body)?.groupValues?.getOrNull(1)

                if (!jsRedirect.isNullOrBlank()) {
                    val redirectReq = Request.Builder()
                        .url(jsRedirect)
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", currentUrl)
                        .build()
                    body = activeClient.newCall(redirectReq).execute().use { resp ->
                        currentUrl = resp.request.url.toString()
                        if (!resp.isSuccessful) return@use ""
                        resp.body?.string().orEmpty()
                    }
                }

                val finalOrigin = runCatching {
                    val u = android.net.Uri.parse(currentUrl)
                    "${u.scheme}://${u.host}"
                }.getOrDefault(origin)

                // -----------------------------------------------------------------
                // 4. DESENCRIPTADOR DE VOE (voe.sx / susanexpectcare.com)
                // -----------------------------------------------------------------
                val voeStreamUrl = decodeVoeFromHtml(body)
                if (!voeStreamUrl.isNullOrBlank()) {
                    val voeHeaders = mapOf(
                        "Referer" to "$finalOrigin/",
                        "Origin" to finalOrigin,
                        "Accept" to "*/*"
                    )
                    return@withContext voeStreamUrl to voeHeaders
                }

                // -----------------------------------------------------------------
                // 5. EXTRACTOR DE MP4UPLOAD (embed-xxxx.html -> :183/d/.../video.mp4)
                // -----------------------------------------------------------------
                val combinedText = StringBuilder(body)
                unpackJsEvalBlocks(body).forEach { unpacked ->
                    combinedText.append("\n").append(unpacked)
                }
                val fullSource = combinedText.toString().replace("\\/", "/")

                if (lower.contains("mp4upload")) {
                    val mp4UploadMatch = Regex("""(https?://[^\s"'<>\\]+\.mp4upload\.com(?::\d+)?/[^\s"'<>\\]+\.mp4)""")
                        .find(fullSource)
                        ?: Regex("""src\s*:\s*['"](https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*)['"]""")
                            .find(fullSource)

                    if (mp4UploadMatch != null) {
                        val mp4Headers = mapOf(
                            "Referer" to "https://www.mp4upload.com/",
                            "Accept" to "*/*"
                        )
                        return@withContext mp4UploadMatch.groupValues[1] to mp4Headers
                    }
                }

                // -----------------------------------------------------------------
                // 6. OTROS SERVIDORES CON .M3U8 O .MP4
                // -----------------------------------------------------------------
                val finalHeaders = mapOf(
                    "Referer" to "$finalOrigin/",
                    "Origin" to finalOrigin,
                    "Accept" to "*/*"
                )

                val hlsMatch = Regex("""(https?://[^\s"'<>\\]+(?:\.m3u8|/m3u8/[a-zA-Z0-9_-]+|master\.txt)[^\s"'<>\\]*)""")
                    .findAll(fullSource)
                    .map { it.groupValues[1] }
                    .firstOrNull { !it.contains("test-videos.co.uk") && !it.contains("bigbuckbunny", ignoreCase = true) }

                if (hlsMatch != null) {
                    return@withContext hlsMatch to finalHeaders
                }

                val mp4Match = Regex("""(https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*)""")
                    .findAll(fullSource)
                    .map { it.groupValues[1] }
                    .firstOrNull {
                        !it.contains("test-videos.co.uk") &&
                                !it.contains("bigbuckbunny", ignoreCase = true) &&
                                !it.endsWith("_SUB.mp4", ignoreCase = true) &&
                                !it.endsWith("_DUB.mp4", ignoreCase = true)
                    }

                if (mp4Match != null) {
                    return@withContext mp4Match to finalHeaders
                }

                null
            }.getOrNull()
        }

    // =========================================================================
    // MOTOR NATIVO COMPLETO PARA BYSE (Challenge -> Attest -> PoW -> Verify -> Playback)
    // =========================================================================
    private fun extractByseFromApi(embedUrl: String): Pair<String, Map<String, String>>? {
        return runCatching {
            val uri = android.net.Uri.parse(embedUrl)
            val baseHost = "${uri.scheme}://${uri.host}"
            val code = Regex("""/(?:e|d)/([a-zA-Z0-9_-]+)""").find(embedUrl)?.groupValues?.getOrNull(1)
                ?: uri.lastPathSegment
                ?: return null

            val jsonMediaType = "application/json; charset=utf-8".toMediaType()
            val cookieStore = mutableMapOf<String, String>()

            fun captureCookies(resp: okhttp3.Response) {
                resp.headers("Set-Cookie").forEach { raw ->
                    val pair = raw.substringBefore(";").trim()
                    val k = pair.substringBefore("=", "").trim()
                    val v = pair.substringAfter("=", "").trim()
                    if (k.isNotEmpty()) cookieStore[k] = v
                }
            }

            fun cookieHeader(): String =
                cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }

            // 1. Obtener `embed_frame_url` desde /api/videos/{code}/embed/details
            var frameUrl = embedUrl
            var playerOrigin = baseHost

            runCatching {
                val detailsReq = Request.Builder()
                    .url("$baseHost/api/videos/$code/embed/details")
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", embedUrl)
                    .header("Accept", "application/json")
                    .header("X-Embed-Origin", "animeav1.com")
                    .header("X-Embed-Referer", "https://animeav1.com/")
                    .header("X-Embed-Parent", embedUrl)
                    .build()

                client.newCall(detailsReq).execute().use { resp ->
                    captureCookies(resp)
                    if (resp.isSuccessful) {
                        val root = JsonParser.parseString(resp.body?.string().orEmpty()).asSafeObj()
                        val fUrl = root?.safeString("embed_frame_url")
                        if (!fUrl.isNullOrBlank() && fUrl.startsWith("http")) {
                            frameUrl = fUrl.trim()
                            val fUri = android.net.Uri.parse(frameUrl)
                            playerOrigin = "${fUri.scheme}://${fUri.host}"
                        }
                    }
                }
            }

            fun Request.Builder.addByseEmbedHeaders(targetOrigin: String): Request.Builder {
                header("User-Agent", USER_AGENT)
                header("Accept", "application/json")
                header("Origin", targetOrigin)
                header("Referer", frameUrl)
                header("X-Embed-Origin", "animeav1.com")
                header("X-Embed-Referer", "https://animeav1.com/")
                header("X-Embed-Parent", embedUrl)
                val ck = cookieHeader()
                if (ck.isNotBlank()) header("Cookie", ck)
                return this
            }

            val originsToTry = listOf(playerOrigin, baseHost).distinct()

            for (targetOrigin in originsToTry) {
                // 2. Consultar /embed/settings para ver si pide captcha PoW
                var captchaRequired = true
                runCatching {
                    val settingsReq = Request.Builder()
                        .url("$targetOrigin/api/videos/$code/embed/settings")
                        .addByseEmbedHeaders(targetOrigin)
                        .get()
                        .build()
                    client.newCall(settingsReq).execute().use { resp ->
                        captureCookies(resp)
                        if (resp.isSuccessful) {
                            val sObj = JsonParser.parseString(resp.body?.string().orEmpty()).asSafeObj()
                            if (sObj?.has("captcha_required") == true) {
                                captchaRequired = sObj.get("captcha_required").asBoolean
                            }
                        }
                    }
                }

                // 3. Ejecutar /api/videos/access/challenge + /api/videos/access/attest (ECDSA P-256)
                val fingerprintJson = performByseAttestation(targetOrigin, frameUrl, embedUrl, ::captureCookies, ::cookieHeader)

                // 4. Si requiere captcha (o siempre por seguridad), resolver el Proof-of-Work (/embed/captcha -> /embed/captcha/verify)
                var captchaToken: String? = null
                if (captchaRequired) {
                    captchaToken = solveByseCaptchaChallenge(
                        targetOrigin = targetOrigin,
                        code = code,
                        fingerprintJson = fingerprintJson,
                        addHeaders = { addByseEmbedHeaders(targetOrigin) },
                        captureCookies = ::captureCookies
                    )
                }

                // 5. Llamar a /api/videos/{code}/embed/playback
                fun requestPlayback(capToken: String?): Pair<Int, JsonObject?> {
                    val pbBodyStr = if (fingerprintJson != null) {
                        """{"fingerprint":$fingerprintJson}"""
                    } else "{}"

                    val pbReqBuilder = Request.Builder()
                        .url("$targetOrigin/api/videos/$code/embed/playback")
                        .addByseEmbedHeaders(targetOrigin)
                        .header("Content-Type", "application/json")
                        .post(pbBodyStr.toRequestBody(jsonMediaType))

                    if (!capToken.isNullOrBlank()) {
                        pbReqBuilder.header("X-Captcha-Token", capToken)
                    }

                    return client.newCall(pbReqBuilder.build()).execute().use { resp ->
                        captureCookies(resp)
                        val bodyStr = resp.body?.string().orEmpty()
                        val json = runCatching { JsonParser.parseString(bodyStr).asSafeObj() }.getOrNull()
                        resp.code to json
                    }
                }

                var (statusCode, playbackRoot) = requestPlayback(captchaToken)

                // Si devolvió 428 Precondition Required (captcha_required) y aún no habíamos corrido el PoW, lo corremos ahora
                if ((statusCode == 428 || playbackRoot == null) && captchaToken == null) {
                    captchaToken = solveByseCaptchaChallenge(
                        targetOrigin = targetOrigin,
                        code = code,
                        fingerprintJson = fingerprintJson,
                        addHeaders = { addByseEmbedHeaders(targetOrigin) },
                        captureCookies = ::captureCookies
                    )
                    if (!captchaToken.isNullOrBlank()) {
                        val retry = requestPlayback(captchaToken)
                        statusCode = retry.first
                        playbackRoot = retry.second
                    }
                }

                if (playbackRoot == null) continue

                val streamHeaders = mapOf(
                    "Referer" to "$targetOrigin/",
                    "Origin" to targetOrigin,
                    "Accept" to "*/*"
                )

                // Caso A: sources directos sin cifrar
                val directUrl = playbackRoot.safeArray("sources")
                    ?.firstOrNull()?.asSafeObj()?.safeString("url")
                if (!directUrl.isNullOrBlank() && directUrl.startsWith("http")) {
                    return directUrl to streamHeaders
                }

                // Caso B: Desencriptar `playback` usando la selección de índices `version` (Qa/ws de byse_video.js)
                val pbObj = playbackRoot.safeObj("playback") ?: continue
                val decryptedUrl = decryptBysePlaybackPayload(pbObj)
                if (!decryptedUrl.isNullOrBlank()) {
                    return decryptedUrl to streamHeaders
                }
            }
            null
        }.getOrNull()
    }

    /**
     * Ejecuta el paso 1 y 2 de `byse_pow.js`:
     * POST /api/videos/access/challenge -> Firma ECDSA P-256 IEEE P1363 (r||s) -> POST /api/videos/access/attest
     */
    private fun performByseAttestation(
        targetOrigin: String,
        frameUrl: String,
        embedUrl: String,
        captureCookies: (okhttp3.Response) -> Unit,
        cookieHeader: () -> String
    ): String? {
        return runCatching {
            val jsonMediaType = "application/json; charset=utf-8".toMediaType()

            val challengeReq = Request.Builder()
                .url("$targetOrigin/api/videos/access/challenge")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Origin", targetOrigin)
                .header("Referer", frameUrl)
                .apply {
                    val ck = cookieHeader()
                    if (ck.isNotBlank()) header("Cookie", ck)
                }
                .post("".toRequestBody(null))
                .build()

            val challengeObj = client.newCall(challengeReq).execute().use { resp ->
                captureCookies(resp)
                if (!resp.isSuccessful) return null
                JsonParser.parseString(resp.body?.string().orEmpty()).asSafeObj()
            } ?: return null

            val challengeId = challengeObj.safeString("challenge_id") ?: return null
            val nonce = challengeObj.safeString("nonce") ?: return null

            // Generar par de llaves EC P-256 (secp256r1) igual que Wn() en byse_pow.js
            val kpg = java.security.KeyPairGenerator.getInstance("EC")
            kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
            val keyPair = kpg.generateKeyPair()
            val pubKey = keyPair.public as java.security.interfaces.ECPublicKey

            val xB64 = encodeBase64UrlNoPad(pubKey.w.affineX.toFixed32Bytes())
            val yB64 = encodeBase64UrlNoPad(pubKey.w.affineY.toFixed32Bytes())

            // Firmar el nonce con SHA-256 + ECDSA y convertir de DER a formato compacto P1363 (64 bytes r||s con lowS)
            val signer = java.security.Signature.getInstance("SHA256withECDSA")
            signer.initSign(keyPair.private)
            signer.update(nonce.toByteArray(Charsets.UTF_8))
            val derSig = signer.sign()
            val rawSig64 = derSignatureToP1363LowS(derSig)
            val signatureB64 = encodeBase64UrlNoPad(rawSig64)

            fun sha256B64Url(text: String): String {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.toByteArray(Charsets.UTF_8))
                return encodeBase64UrlNoPad(digest)
            }

            val canvasHash = sha256B64Url("byse-canvas-v1")
            val audioHash = sha256B64Url("byse-audio-v1")
            val webglHash = sha256B64Url("byse-webgl-v1")
            val fontsHash = sha256B64Url("Arial,Calibri,Consolas,Courier New,Georgia,Segoe UI,Tahoma,Times New Roman,Verdana")
            val codecsHash = sha256B64Url("v:avc1=probably|a:mp4a=probably")

            val attestPayload = """
                {
                  "viewer_id": "",
                  "device_id": "",
                  "challenge_id": "$challengeId",
                  "nonce": "$nonce",
                  "signature": "$signatureB64",
                  "public_key": {
                    "kty": "EC",
                    "crv": "P-256",
                    "x": "$xB64",
                    "y": "$yB64"
                  },
                  "client": {
                    "user_agent": "$USER_AGENT",
                    "architecture": "x86",
                    "bitness": "64",
                    "platform": "Windows",
                    "platform_version": "15.0.0",
                    "ua_full_version": "131.0.6778.86",
                    "brand_full_versions": [
                      {"brand": "Chromium", "version": "131.0.6778.86"},
                      {"brand": "Google Chrome", "version": "131.0.6778.86"},
                      {"brand": "Not_A Brand", "version": "24.0.0.0"}
                    ],
                    "pixel_ratio": 1,
                    "screen_width": 1920,
                    "screen_height": 1080,
                    "color_depth": 24,
                    "languages": ["es-ES", "es"],
                    "timezone": "America/Santiago",
                    "hardware_concurrency": 12,
                    "device_memory": 8,
                    "touch_points": 0,
                    "webgl_vendor": "Google Inc. (AMD)",
                    "webgl_renderer": "ANGLE (AMD, AMD Radeon Graphics Direct3D11 vs_5_0 ps_5_0, D3D11)",
                    "canvas_hash": "$canvasHash",
                    "audio_hash": "$audioHash",
                    "webgl_params_hash": "$webglHash",
                    "fonts_hash": "$fontsHash",
                    "codecs_hash": "$codecsHash",
                    "media_devices": "ai1ao1vi0",
                    "pointer_type": "fine,hover",
                    "extra": {
                      "vendor": "Google Inc.",
                      "appVersion": "5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                    }
                  },
                  "storage": {},
                  "attributes": {
                    "entropy": "high"
                  }
                }
            """.trimIndent()

            val attestReq = Request.Builder()
                .url("$targetOrigin/api/videos/access/attest")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Origin", targetOrigin)
                .header("Referer", frameUrl)
                .header("X-Embed-Origin", "animeav1.com")
                .header("X-Embed-Referer", "https://animeav1.com/")
                .header("X-Embed-Parent", embedUrl)
                .apply {
                    val ck = cookieHeader()
                    if (ck.isNotBlank()) header("Cookie", ck)
                }
                .post(attestPayload.toRequestBody(jsonMediaType))
                .build()

            client.newCall(attestReq).execute().use { resp ->
                captureCookies(resp)
                if (!resp.isSuccessful) return null
                val attObj = JsonParser.parseString(resp.body?.string().orEmpty()).asSafeObj() ?: return null
                val token = attObj.safeString("token") ?: return null
                val viewerId = attObj.safeString("viewer_id").orEmpty()
                val deviceId = attObj.safeString("device_id").orEmpty()
                val confidence = attObj.get("confidence")?.asDouble ?: 1.0
                """{"token":"$token","viewer_id":"$viewerId","device_id":"$deviceId","confidence":$confidence}"""
            }
        }.getOrNull()
    }

    /**
     * Ejecuta el paso 3 y 4 de `byse_video.js`:
     * POST /api/videos/{code}/embed/captcha -> Resuelve Proof-of-Work gr() -> POST /api/videos/{code}/embed/captcha/verify
     */
    private fun solveByseCaptchaChallenge(
        targetOrigin: String,
        code: String,
        fingerprintJson: String?,
        addHeaders: Request.Builder.() -> Request.Builder,
        captureCookies: (okhttp3.Response) -> Unit
    ): String? {
        return runCatching {
            val jsonMediaType = "application/json; charset=utf-8".toMediaType()
            val bodyStr = if (fingerprintJson != null) """{"fingerprint":$fingerprintJson}""" else "{}"

            val captchaReq = Request.Builder()
                .url("$targetOrigin/api/videos/$code/embed/captcha")
                .addHeaders()
                .header("Content-Type", "application/json")
                .post(bodyStr.toRequestBody(jsonMediaType))
                .build()

            val capObj = client.newCall(captchaReq).execute().use { resp ->
                captureCookies(resp)
                if (!resp.isSuccessful) return null
                JsonParser.parseString(resp.body?.string().orEmpty()).asSafeObj()
            } ?: return null

            val powNonce = capObj.safeString("pow_nonce") ?: return null
            val powDifficulty = capObj.safeInt("pow_difficulty") ?: 0
            val powToken = capObj.safeString("pow_token") ?: return null

            val solution = solveBysePowHash(powNonce, powDifficulty)

            val verifyBody = if (fingerprintJson != null) {
                """{"pow_token":"$powToken","solution":"$solution","fingerprint":$fingerprintJson}"""
            } else {
                """{"pow_token":"$powToken","solution":"$solution"}"""
            }

            val verifyReq = Request.Builder()
                .url("$targetOrigin/api/videos/$code/embed/captcha/verify")
                .addHeaders()
                .header("Content-Type", "application/json")
                .post(verifyBody.toRequestBody(jsonMediaType))
                .build()

            client.newCall(verifyReq).execute().use { resp ->
                captureCookies(resp)
                if (!resp.isSuccessful) return null
                val verObj = JsonParser.parseString(resp.body?.string().orEmpty()).asSafeObj()
                verObj?.safeString("token")
            }
        }.getOrNull()
    }

    /**
     * Port exacto en 32-bit de `gr()`, `wr()` y `Er()` de `byse_pow.js`
     */
    private fun solveBysePowHash(nonce: String, difficulty: Int): String {
        if (difficulty <= 0) return "0"
        val prefix = "$nonce:"
        val e = IntArray(4)
        val r = IntArray(512)
        val n = IntArray(8)
        val lr = -1640531535 // 2654435761 en uint32
        val hr = -2048144777 // 2246822519 en uint32

        fun ye(s: IntArray) {
            s[0] = s[0] + s[1]
            s[3] = Integer.rotateLeft(s[3] xor s[0], 16)
            s[2] = s[2] + s[3]
            s[1] = Integer.rotateLeft(s[1] xor s[2], 12)
            s[0] = s[0] + s[1]
            s[3] = Integer.rotateLeft(s[3] xor s[0], 8)
            s[2] = s[2] + s[3]
            s[1] = Integer.rotateLeft(s[1] xor s[2], 7)
        }

        var counter = 0L
        while (counter < 2_000_000L) {
            val candidate = prefix + counter.toString()
            e[0] = 1779033703
            e[1] = -1150833019 // 3144134277 en uint32
            e[2] = 1013904242
            e[3] = -1521486534 // 2773480762 en uint32

            for (i in 0 until candidate.length) {
                e[0] = e[0] + (candidate[i].code and 0xFF)
                e[0] = Integer.rotateLeft(e[0], 7)
                ye(e)
            }
            for (i in 0 until 8) ye(e)
            for (i in 0 until 512) {
                ye(e)
                r[i] = e[0] xor e[2]
            }
            for (pass in 0 until 2) {
                for (s in 0 until 512) {
                    val a = r[s] and 511
                    var c = r[s] + r[a]
                    c = Integer.rotateLeft(c, 13)
                    c = c xor (r[(s + 1) and 511] * lr)
                    r[s] = c
                    e[0] = e[0] xor c
                    ye(e)
                }
            }
            for (i in 0 until 8) {
                ye(e)
                var s = e[0]
                val a = i * 64
                for (c in 0 until 64) {
                    val d = r[a + c]
                    s += d
                    s = Integer.rotateLeft(s, 5)
                    s = s xor (d * hr)
                }
                n[i] = s xor e[2]
            }

            var leadingZeros = 0
            for (i in 0 until 8) {
                val word = n[i]
                if (word == 0) {
                    leadingZeros += 32
                } else {
                    leadingZeros += Integer.numberOfLeadingZeros(word)
                    break
                }
            }
            if (leadingZeros >= difficulty) {
                return counter.toString()
            }
            counter++
        }
        return "0"
    }

    /**
     * Port exacto de `Qa()`, `Ea()`, `ws()`, `ks()` y `La()` de `byse_video.js`:
     * Selecciona las 2 partes de la llave AES según `version` (`version - 1` y `30 - version`) y desencripta con AES-GCM.
     */
    private fun decryptBysePlaybackPayload(pbObj: JsonObject): String? {
        return runCatching {
            val ivB64 = pbObj.safeString("iv") ?: return null
            val payloadB64 = pbObj.safeString("payload") ?: return null
            val keyPartsArr = pbObj.safeArray("key_parts") ?: return null

            val allParts = keyPartsArr.mapNotNull {
                runCatching { it.asString }.getOrNull()?.takeIf { s -> s.isNotEmpty() }
            }
            if (allParts.isEmpty()) return null

            val versionStr = pbObj.safeString("version")?.trim().orEmpty()
            val vNum = versionStr.toIntOrNull()

            // En `byse_video.js`: Qa() mapea n (1..20) -> [n, 31 - n] (índices 1-based sobre `key_parts`)
            val selectedParts = if (vNum != null && vNum in 1..20) {
                val idx1 = vNum
                val idx2 = 31 - vNum
                if (idx1 in 1..allParts.size && idx2 in 1..allParts.size) {
                    listOf(allParts[idx1 - 1], allParts[idx2 - 1])
                } else {
                    allParts
                }
            } else {
                allParts
            }

            val decodedChunks = selectedParts.map { decodeBase64Url(it) }
            val totalKeyLen = decodedChunks.sumOf { it.size }
            val keyBytes = ByteArray(totalKeyLen)
            var offset = 0
            for (chunk in decodedChunks) {
                System.arraycopy(chunk, 0, keyBytes, offset, chunk.size)
                offset += chunk.size
            }

            val ivBytes = decodeBase64Url(ivB64)
            val cipherBytes = decodeBase64Url(payloadB64)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(128, ivBytes)
            )
            val decryptedStr = String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            val decryptedJson = JsonParser.parseString(decryptedStr).asSafeObj()

            decryptedJson?.safeArray("sources")
                ?.firstOrNull()?.asSafeObj()?.safeString("url")
                ?: Regex("""(https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*)""").find(decryptedStr)?.groupValues?.getOrNull(1)
        }.getOrNull()
    }

    private fun java.math.BigInteger.toFixed32Bytes(): ByteArray {
        val raw = this.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32).also { System.arraycopy(raw, 0, it, 32 - raw.size, raw.size) }
        }
    }

    private fun derSignatureToP1363LowS(der: ByteArray): ByteArray {
        var idx = 2
        if (der[1].toInt() and 0x80 != 0) {
            idx += (der[1].toInt() and 0x7F)
        }
        // Leer entero R
        idx++ // tag 0x02
        val rLen = der[idx++].toInt() and 0xFF
        val rVal = java.math.BigInteger(1, der.copyOfRange(idx, idx + rLen))
        idx += rLen
        // Leer entero S
        idx++ // tag 0x02
        val sLen = der[idx++].toInt() and 0xFF
        var sVal = java.math.BigInteger(1, der.copyOfRange(idx, idx + sLen))

        // Normalizar lowS según el orden n de la curva P-256 (requerido por @noble/curves)
        val p256OrderN = java.math.BigInteger(
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
            16
        )
        val halfN = p256OrderN.shiftRight(1)
        if (sVal > halfN) {
            sVal = p256OrderN.subtract(sVal)
        }

        return rVal.toFixed32Bytes() + sVal.toFixed32Bytes()
    }

    private fun encodeBase64UrlNoPad(bytes: ByteArray): String {
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        ).trim()
    }

    private fun decodeBase64Url(input: String): ByteArray {
        val standard = input.trim().replace('-', '+').replace('_', '/')
        return android.util.Base64.decode(standard, android.util.Base64.DEFAULT)
    }

    private fun decodeVoeFromHtml(html: String): String? {
        val jsonScriptRegex = Regex(
            """<script\s+type=["']application/json["']>\s*\[\s*["']([^"']+)["']\s*\]\s*</script>""",
            RegexOption.IGNORE_CASE
        )
        val encodedPayload = jsonScriptRegex.find(html)?.groupValues?.getOrNull(1)
        if (!encodedPayload.isNullOrBlank()) {
            runCatching {
                val rot13 = buildString(encodedPayload.length) {
                    for (ch in encodedPayload) {
                        when (ch) {
                            in 'a'..'z' -> append('a' + (ch - 'a' + 13) % 26)
                            in 'A'..'Z' -> append('A' + (ch - 'A' + 13) % 26)
                            else -> append(ch)
                        }
                    }
                }
                val cleanB64First = rot13.filter {
                    it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '='
                }
                val step3Bytes = android.util.Base64.decode(cleanB64First, android.util.Base64.DEFAULT)
                val step3Str = String(step3Bytes, Charsets.ISO_8859_1)

                val shiftedAndReversed = buildString(step3Str.length) {
                    for (i in step3Str.length - 1 downTo 0) {
                        append((step3Str[i].code - 3).toChar())
                    }
                }
                val finalJsonBytes = android.util.Base64.decode(shiftedAndReversed, android.util.Base64.DEFAULT)
                val finalJsonStr = String(finalJsonBytes, Charsets.UTF_8)

                val jsonObj = JsonParser.parseString(finalJsonStr).asSafeObj()
                val sourceUrl = jsonObj?.safeString("source")?.takeIf { it.startsWith("http") }
                if (!sourceUrl.isNullOrBlank()) {
                    return sourceUrl
                }
            }
        }

        val b64Hls = Regex("""['"]hls['"]\s*:\s*['"]([A-Za-z0-9+/=]{20,})['"]""")
            .find(html)?.groupValues?.getOrNull(1)
        if (!b64Hls.isNullOrBlank()) {
            val decoded = runCatching {
                String(android.util.Base64.decode(b64Hls, android.util.Base64.DEFAULT)).trim()
            }.getOrNull()
            if (!decoded.isNullOrBlank() && decoded.startsWith("http")) {
                return decoded
            }
        }

        return null
    }

    private fun unpackJsEvalBlocks(html: String): List<String> {
        val results = mutableListOf<String>()
        val regex = Regex(
            """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\('((?:\\.|[^'])*)',\s*(\d+),\s*(\d+),\s*'((?:\\.|[^'])*)'\.split\('\|'\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        regex.findAll(html).forEach { match ->
            runCatching {
                var p = match.groupValues[1]
                val a = match.groupValues[2].toInt()
                var c = match.groupValues[3].toInt()
                val k = match.groupValues[4].split("|")

                fun encodeBase(num: Int, radix: Int): String {
                    val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    if (num < radix) {
                        return if (num < 36) num.toString(36) else chars[num].toString()
                    }
                    return encodeBase(num / radix, radix) +
                            if (num % radix < 36) (num % radix).toString(36) else chars[num % radix].toString()
                }

                while (c-- > 0) {
                    if (c < k.size && k[c].isNotEmpty()) {
                        val token = encodeBase(c, a)
                        p = p.replace(Regex("\\b$token\\b"), k[c])
                    }
                }
                results.add(p)
            }
        }
        return results
    }
}