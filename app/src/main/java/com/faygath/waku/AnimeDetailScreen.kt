package com.faygath.waku

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.faygath.waku.ui.theme.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.delay

@Composable
fun AnimeDetailScreen(
    initialSlug: String,
    onBackClick: () -> Unit,
    onPlayEpisode: (AnimeDetailData, AnimeEpisodeItem) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid

    var currentSlug by remember { mutableStateOf(initialSlug) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var detailData by remember { mutableStateOf<AnimeDetailData?>(null) }

    // Estados sincronizados con Firestore para este anime
    var isFavorite by remember { mutableStateOf(false) }
    var currentWatchingEp by remember { mutableIntStateOf(1) }
    var currentEpProgress by remember { mutableFloatStateOf(0f) }
    var hasStartedWatching by remember { mutableStateOf(false) }
    var watchedEpisodes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var noticeMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(noticeMessage) {
        if (noticeMessage != null) {
            delay(3200)
            noticeMessage = null
        }
    }

    val lazyListState = rememberLazyListState()

    // Progreso del scroll (0f arriba del todo -> 1f cuando ya bajaste hacia los episodios)
    val scrollFraction by remember {
        derivedStateOf {
            if (lazyListState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (lazyListState.firstVisibleItemScrollOffset / 550f).coerceIn(0f, 1f)
            }
        }
    }

    // 1. Cargar datos reales de la ficha desde AnimeAV1 + Ani.zip
    LaunchedEffect(currentSlug) {
        isLoading = true
        errorMessage = null
        val result = WakuRepository.fetchAnimeDetail(context, currentSlug)
        result.onSuccess { data ->
            detailData = data
            isLoading = false
        }.onFailure { err ->
            errorMessage = err.localizedMessage ?: "No se pudo cargar la ficha del anime"
            isLoading = false
        }
    }

    // 2. Escuchar el progreso y favoritos de este anime en Firestore
    LaunchedEffect(uid, detailData?.animeId) {
        val animeId = detailData?.animeId ?: return@LaunchedEffect
        if (uid != null && animeId > 0) {
            db.collection("usuarios").document(uid)
                .collection("mi_lista").document(animeId.toString())
                .addSnapshotListener { snap, _ ->
                    if (snap != null && snap.exists()) {
                        isFavorite = snap.getBoolean("esFavorito") == true
                        val estado = snap.getString("estado")
                        hasStartedWatching = estado == "VIENDO" || estado == "FINALIZADO"
                        currentWatchingEp = snap.getLong("episodioActual")?.toInt() ?: 1
                        currentEpProgress = (snap.getDouble("progress")?.toFloat() ?: 0f).coerceIn(0f, 1f)

                        val rawWatched = snap.get("watchedEpisodes") as? List<*>
                        watchedEpisodes = rawWatched?.mapNotNull {
                            (it as? Number)?.toInt()
                        }?.toSet() ?: emptySet()
                    } else {
                        isFavorite = false
                        hasStartedWatching = false
                        currentWatchingEp = 1
                        currentEpProgress = 0f
                        watchedEpisodes = emptySet()
                    }
                }
        }
    }

    Scaffold(
        containerColor = WakuBg,
        bottomBar = {
            val data = detailData
            if (data != null) {
                AnimeDetailStickyBottomBar(
                    hasStarted = hasStartedWatching,
                    episodeToPlay = currentWatchingEp,
                    isFavorite = isFavorite,
                    onMainPlayClick = {
                        val targetEp = data.episodes.find { it.number == currentWatchingEp }
                            ?: data.episodes.firstOrNull()
                        if (targetEp != null) {
                            markEpisodeInProgressFirestore(
                                db = db,
                                uid = uid,
                                data = data,
                                episode = targetEp,
                                currentWatchingEp = currentWatchingEp,
                                currentEpProgress = currentEpProgress
                            )
                            onPlayEpisode(data, targetEp)
                        }
                    },
                    onToggleFavorite = {
                        if (uid != null && data.animeId > 0) {
                            val newFav = !isFavorite
                            val payload = mapOf(
                                "animeId" to data.animeId,
                                "slug" to data.slug,
                                "title" to data.title,
                                "posterUrl" to data.posterUrl,
                                "totalEpisodes" to data.totalEpisodes,
                                "esFavorito" to newFav
                            )
                            db.collection("usuarios").document(uid)
                                .collection("mi_lista").document(data.animeId.toString())
                                .set(payload, SetOptions.merge())

                            noticeMessage = if (newFav) {
                                "Añadiste \"${data.title}\" a tus Favoritos"
                            } else {
                                "Quitaste \"${data.title}\" de tus Favoritos"
                            }
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WakuBg)
        ) {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = WakuPrimary, strokeWidth = 3.dp)
                }
            } else if (errorMessage != null || detailData == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = errorMessage ?: "Error desconocido",
                        color = WakuPink,
                        fontSize = 14.sp
                    )
                }
            } else {
                val data = detailData!!

                // 1. PÓSTER FIJO EN EL FONDO: No sube con el scroll, sino que se desvanece suavemente al bajar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(440.dp)
                        .graphicsLayer {
                            alpha = (1f - (scrollFraction * 1.15f)).coerceIn(0f, 1f)
                        }
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(data.posterUrl)
                            .size(Size.ORIGINAL)
                            .crossfade(true)
                            .build(),
                        contentDescription = data.title,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Degradado multicapa hacia #16110F
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.0f to WakuBg.copy(alpha = 0.30f),
                                    0.35f to Color.Transparent,
                                    0.72f to WakuBg.copy(alpha = 0.80f),
                                    1.0f to WakuBg
                                )
                            )
                    )
                }

                // 2. CONTENIDO DESLIZABLE (Metadatos, Sinopsis, Temporadas y Episodios)
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = 290.dp,
                        bottom = innerPadding.calculateBottomPadding() + 24.dp
                    )
                ) {
                    // Bloque de Metadatos, Géneros y Sinopsis
                    item {
                        AnimeMetadataSection(data = data)
                    }

                    // Selector de Temporadas
                    item {
                        Spacer(modifier = Modifier.height(22.dp))
                        SeasonSelectorRow(
                            seasons = data.seasonTabs,
                            currentSlug = currentSlug,
                            onSelectSeason = { selectedSlug ->
                                if (selectedSlug != currentSlug) {
                                    currentSlug = selectedSlug
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // Lista de Episodios
                    items(
                        items = data.episodes,
                        key = { it.number }
                    ) { episode ->
                        val isEpWatched = watchedEpisodes.contains(episode.number)
                        val isEpInProgress = !isEpWatched &&
                                hasStartedWatching &&
                                currentWatchingEp == episode.number &&
                                currentEpProgress > 0.05f

                        val remainingMinutes = if (isEpInProgress) {
                            ((1f - currentEpProgress) * episode.durationMinutes).toInt().coerceAtLeast(1)
                        } else 0

                        AnimeEpisodeRow(
                            episode = episode,
                            isWatched = isEpWatched,
                            isInProgress = isEpInProgress,
                            progressFraction = if (isEpWatched) 1f else if (isEpInProgress) currentEpProgress else 0f,
                            remainingMinutes = remainingMinutes,
                            onEpisodeClick = {
                                markEpisodeInProgressFirestore(
                                    db = db,
                                    uid = uid,
                                    data = data,
                                    episode = episode,
                                    currentWatchingEp = currentWatchingEp,
                                    currentEpProgress = currentEpProgress
                                )
                                onPlayEpisode(data, episode)
                            },
                            onToggleWatched = {
                                toggleEpisodeWatchedFirestore(
                                    db = db,
                                    uid = uid,
                                    data = data,
                                    episodeNumber = episode.number,
                                    currentWatched = watchedEpisodes
                                )
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            // 3. TOP BAR FLOTANTE: Flecha atrás a la izquierda, Título al centro que aparece al bajar, y estrella ✦ a la derecha
            AnimeDetailFloatingTopBar(
                title = detailData?.title.orEmpty(),
                scrollFraction = scrollFraction,
                onBackClick = onBackClick,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            // 4. Cartelito flotante personalizado al guardar/quitar de favoritos
            AnimatedVisibility(
                visible = noticeMessage != null,
                enter = fadeIn(tween(200)) + slideInVertically { it / 2 },
                exit = fadeOut(tween(250)) + slideOutVertically { it / 2 },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = innerPadding.calculateBottomPadding() + 14.dp
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(16.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1A1412))
                        .border(1.dp, WakuGold.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "✦",
                        color = WakuGold,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = noticeMessage.orEmpty(),
                        color = WakuText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun AnimeDetailFloatingTopBar(
    title: String,
    scrollFraction: Float,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val barBgAlpha = 0.06f + (scrollFraction * 0.90f)
    val titleAlpha = ((scrollFraction - 0.35f) / 0.45f).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0.0f to WakuBg.copy(alpha = (barBgAlpha + 0.25f).coerceAtMost(0.98f)),
                    0.8f to WakuBg.copy(alpha = barBgAlpha),
                    1.0f to WakuBg.copy(alpha = (barBgAlpha - 0.06f).coerceAtLeast(0f))
                )
            )
            .statusBarsPadding()
            .height(60.dp)
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF241E1C).copy(alpha = 0.72f))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Volver",
                    tint = WakuText,
                    modifier = Modifier.size(20.dp)
                )
            }

            Text(
                text = title,
                color = WakuText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
                    .alpha(titleAlpha)
            )

            Box(
                modifier = Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                SparkleStar(
                    symbol = "✦",
                    color = WakuGold,
                    fontSize = 20.sp,
                    delayMillis = 0
                )
            }
        }
    }
}

@Composable
fun AnimeMetadataSection(data: AnimeDetailData) {
    var synopsisExpanded by remember(data.synopsis) { mutableStateOf(false) }
    var isSynopsisOverflowing by remember(data.synopsis) { mutableStateOf(false) }

    val episodesHeaderLabel = when {
        data.isAiring && data.totalEpisodes > data.episodes.size ->
            "${data.episodes.size} / ${data.totalEpisodes} Episodios"
        data.isAiring && data.totalEpisodes == 0 ->
            "${data.episodes.size} / ? Episodios"
        data.totalEpisodes > 0 ->
            "${data.totalEpisodes} Episodios"
        else ->
            "${data.episodes.size} Episodios"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFF2F2926))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    tint = WakuGold,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = data.scoreText,
                    color = WakuGold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            DotSeparator()

            Text(
                text = data.yearText,
                color = Color(0xFFE4BEB7),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )

            if (data.isAiring) {
                DotSeparator()

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(WakuGreenDark.copy(alpha = 0.35f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "EN EMISIÓN",
                        color = WakuGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }

            DotSeparator()

            Text(
                text = episodesHeaderLabel,
                color = Color(0xFFE4BEB7),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = data.title,
            color = WakuText,
            fontSize = 26.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(10.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(data.genres) { genre ->
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xFF241E1C))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = genre,
                        color = Color(0xFFE4BEB7),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
        ) {
            Text(
                text = data.synopsis,
                color = Color(0xFFE4BEB7),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                maxLines = if (synopsisExpanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { layoutResult ->
                    if (!synopsisExpanded) {
                        isSynopsisOverflowing = layoutResult.hasVisualOverflow
                    }
                }
            )

            if (isSynopsisOverflowing || synopsisExpanded) {
                Text(
                    text = if (synopsisExpanded) "Mostrar menos ▴" else "Leer más ▾",
                    color = WakuGold,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable { synopsisExpanded = !synopsisExpanded }
                )
            }
        }
    }
}

@Composable
private fun DotSeparator() {
    Box(
        modifier = Modifier
            .size(4.dp)
            .clip(CircleShape)
            .background(Color(0xFFAA8983))
    )
}

@Composable
fun SeasonSelectorRow(
    seasons: List<SeasonTabItem>,
    currentSlug: String,
    onSelectSeason: (String) -> Unit
) {
    val rowState = rememberLazyListState()

    LaunchedEffect(seasons, currentSlug) {
        val activeIndex = seasons.indexOfFirst { it.slug == currentSlug }
        if (activeIndex >= 0) {
            rowState.animateScrollToItem(activeIndex)
        }
    }

    LazyRow(
        state = rowState,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(seasons, key = { it.slug }) { season ->
            val isSelected = season.slug == currentSlug
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (isSelected) WakuPrimary else Color(0xFF241E1C))
                    .clickable { onSelectSeason(season.slug) }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Text(
                    text = season.label,
                    color = if (isSelected) WakuText else Color(0xFFE4BEB7),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (season.episodesCount != null && season.episodesCount > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "(${season.episodesCount} Eps)",
                        color = if (isSelected) WakuText.copy(alpha = 0.85f) else Color(0xFFE4BEB7).copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
fun AnimeEpisodeRow(
    episode: AnimeEpisodeItem,
    isWatched: Boolean,
    isInProgress: Boolean,
    progressFraction: Float,
    remainingMinutes: Int,
    onEpisodeClick: () -> Unit,
    onToggleWatched: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(WakuSurface)
            .clickable { onEpisodeClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(126.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF3A3331))
        ) {
            AsyncImage(
                model = episode.screenshotUrl,
                contentDescription = episode.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        if (isInProgress) WakuPrimary
                        else WakuBg.copy(alpha = 0.68f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = WakuText,
                    modifier = Modifier.size(16.dp)
                )
            }

            if (isInProgress) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .clip(CircleShape)
                        .background(WakuBg.copy(alpha = 0.85f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Quedan ${remainingMinutes}m",
                        color = WakuGold,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (!isWatched && !isInProgress) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF120D0B).copy(alpha = 0.84f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "${episode.durationMinutes}m",
                        color = WakuText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (isWatched || isInProgress) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0xFF3A3331))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                            .background(WakuPrimary)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "EPISODIO ${episode.number}",
                    color = if (isWatched || isInProgress) WakuGold else Color(0xFFE4BEB7),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp
                )

                if (isWatched) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = WakuGreen,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "Visto",
                            color = WakuGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = episode.title,
                color = WakuText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            val audioLabel = if (episode.hasDub) "Subtitulado / Doblado" else "Subtitulado"
            Text(
                text = "$audioLabel • ${episode.durationMinutes} min",
                color = Color(0xFFE4BEB7),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "Opciones del episodio",
                    tint = Color(0xFFE4BEB7),
                    modifier = Modifier.size(20.dp)
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = Color(0xFF120D0B),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.border(1.dp, Color(0xFF3A3331), RoundedCornerShape(14.dp))
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (isWatched) "Desmarcar como visto" else "Marcar como visto",
                            color = WakuText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = WakuGreen,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onToggleWatched()
                    }
                )
            }
        }
    }
}

@Composable
fun AnimeDetailStickyBottomBar(
    hasStarted: Boolean,
    episodeToPlay: Int,
    isFavorite: Boolean,
    onMainPlayClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF120D0B).copy(alpha = 0.94f))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onMainPlayClick,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = WakuPrimary,
                contentColor = WakuText
            ),
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .shadow(
                    elevation = 18.dp,
                    shape = CircleShape,
                    ambientColor = WakuPrimary,
                    spotColor = WakuPrimary
                )
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (hasStarted) "Continuar viendo • Ep. $episodeToPlay" else "Comenzar a ver • Ep. 1",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFF2F2926))
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Bookmark else Icons.Outlined.BookmarkAdd,
                contentDescription = "Guardar en Favoritos",
                tint = if (isFavorite) WakuGold else WakuPink,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

private fun toggleEpisodeWatchedFirestore(
    db: FirebaseFirestore,
    uid: String?,
    data: AnimeDetailData,
    episodeNumber: Int,
    currentWatched: Set<Int>
) {
    if (uid == null || data.animeId <= 0) return
    val updatedSet = currentWatched.toMutableSet()
    if (updatedSet.contains(episodeNumber)) {
        updatedSet.remove(episodeNumber)
    } else {
        updatedSet.add(episodeNumber)
    }

    val maxKnownEp = maxOf(data.totalEpisodes, data.episodes.size, 1)
    val nextUnwatched = (1..maxKnownEp)
        .firstOrNull { !updatedSet.contains(it) } ?: maxKnownEp

    val allWatched = !data.isAiring && data.totalEpisodes > 0 && updatedSet.size >= data.totalEpisodes

    val payload = mapOf(
        "animeId" to data.animeId,
        "slug" to data.slug,
        "title" to data.title,
        "posterUrl" to data.posterUrl,
        "totalEpisodes" to data.totalEpisodes,
        "isAiring" to data.isAiring,
        "watchedEpisodes" to updatedSet.toList(),
        "episodioActual" to nextUnwatched,
        "estado" to if (allWatched) "FINALIZADO" else "VIENDO",
        "progress" to if (allWatched) 1.0f else 0.0f,
        "updatedAt" to System.currentTimeMillis()
    )

    db.collection("usuarios").document(uid)
        .collection("mi_lista").document(data.animeId.toString())
        .set(payload, SetOptions.merge())
}

private fun markEpisodeInProgressFirestore(
    db: FirebaseFirestore,
    uid: String?,
    data: AnimeDetailData,
    episode: AnimeEpisodeItem,
    currentWatchingEp: Int,
    currentEpProgress: Float
) {
    if (uid == null || data.animeId <= 0) return
    val preservedProgress = if (episode.number == currentWatchingEp && currentEpProgress > 0.02f) {
        currentEpProgress
    } else {
        0.02f
    }
    val remainingMins = ((1f - preservedProgress) * episode.durationMinutes).toInt().coerceAtLeast(1)

    val payload = mapOf(
        "animeId" to data.animeId,
        "slug" to data.slug,
        "title" to data.title,
        "posterUrl" to data.posterUrl,
        "totalEpisodes" to data.totalEpisodes,
        "episodioActual" to episode.number,
        "episodeTitle" to episode.title,
        "imageUrl" to episode.screenshotUrl,
        "estado" to "VIENDO",
        "progress" to preservedProgress,
        "badgeText" to "Quedan ${remainingMins}m",
        "isNextEpisode" to false,
        "updatedAt" to System.currentTimeMillis()
    )
    db.collection("usuarios").document(uid)
        .collection("mi_lista").document(data.animeId.toString())
        .set(payload, SetOptions.merge())
}