package com.faygath.waku

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
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

@Composable
fun HomeScreen(
    onNavigateTab: (String) -> Unit = {},
    onOpenAnimeDetail: (String) -> Unit = {},
    onPlayEpisode: (String, Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var heroItems by remember { mutableStateOf<List<HeroAnimeItem>>(emptyList()) }
    var latestEpisodes by remember { mutableStateOf<List<LatestEpisodeItem>>(emptyList()) }

    var userAvatarUrl by remember { mutableStateOf<String?>(null) }
    var favoriteIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var continueWatchingList by remember { mutableStateOf<List<ContinueWatchingItem>>(emptyList()) }
    var selectedBottomTab by remember { mutableStateOf("inicio") }

    val lazyListState = rememberLazyListState()
    val topBarAlpha by remember {
        derivedStateOf {
            if (lazyListState.firstVisibleItemIndex > 0) {
                0.96f
            } else {
                val progress = (lazyListState.firstVisibleItemScrollOffset / 650f).coerceIn(0f, 1f)
                0.08f + (progress * 0.88f)
            }
        }
    }

    // 1. Escuchar Avatar del usuario y Mi Lista desde Firestore
    LaunchedEffect(uid) {
        if (uid != null) {
            db.collection("usuarios").document(uid)
                .addSnapshotListener { snap, _ ->
                    userAvatarUrl = snap?.getString("avatarUrl")
                }

            db.collection("usuarios").document(uid).collection("mi_lista")
                .addSnapshotListener { snap, _ ->
                    if (snap != null) {
                        val favs = mutableSetOf<Int>()
                        val watchingPairs = mutableListOf<Pair<Long, ContinueWatchingItem>>()

                        for (doc in snap.documents) {
                            val animeId = doc.getLong("animeId")?.toInt()
                                ?: doc.id.toIntOrNull() ?: 0
                            if (doc.getBoolean("esFavorito") == true) {
                                favs.add(animeId)
                            }
                            if (doc.getString("estado") == "VIENDO") {
                                val updatedAt = doc.getLong("updatedAt") ?: 0L
                                watchingPairs.add(
                                    updatedAt to ContinueWatchingItem(
                                        animeId = animeId,
                                        slug = doc.getString("slug").orEmpty(),
                                        title = doc.getString("title").orEmpty(),
                                        episodeNumber = doc.getLong("episodioActual")?.toInt() ?: 1,
                                        episodeTitle = doc.getString("episodeTitle").orEmpty(),
                                        imageUrl = doc.getString("imageUrl").orEmpty(),
                                        progressFraction = (doc.getDouble("progress")?.toFloat() ?: 0.5f)
                                            .coerceIn(0.05f, 1f),
                                        badgeText = doc.getString("badgeText") ?: "Quedan 14m",
                                        isNextEpisode = doc.getBoolean("isNextEpisode") ?: false
                                    )
                                )
                            }
                        }
                        favoriteIds = favs
                        continueWatchingList = watchingPairs.sortedByDescending { it.first }.map { it.second }
                    }
                }
        }
    }

    // 2. Cargar datos reales desde AnimeAV1 + Ani.zip HD
    LaunchedEffect(Unit) {
        isLoading = true
        val result = WakuRepository.fetchHomeData(context)
        result.onSuccess { data ->
            heroItems = data.heroItems
            latestEpisodes = data.latestEpisodes
            isLoading = false
        }.onFailure { err ->
            errorMessage = err.localizedMessage ?: "Error al conectar con AnimeAV1"
            isLoading = false
        }
    }

    Scaffold(
        containerColor = WakuBg,
        bottomBar = {
            WakuBottomNavBar(
                selectedRoute = selectedBottomTab,
                avatarUrl = userAvatarUrl,
                onSelectRoute = { route ->
                    selectedBottomTab = route
                    onNavigateTab(route)
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (isLoading) {
                val loadingEasterEgg = remember {
                    listOf(
                        "Buscando las Esferas del Dragón...",
                        "Armando a Exodia el Prohibido...",
                        "Resolviendo el misterio de las Pléyades...",
                        "Cargando el 100% de Mob...",
                        "Preparando un estofado con Senshi...",
                        "Calibrando el Medidor de Divergencia...",
                        "Abriendo la Puerta de la Verdad...",
                        "Reuniendo a los Sombrero de Paja...",
                        "Afilando las cuchillas de la Legión de Reconocimiento...",
                        "Poniendo a enfriar una Dr Pepper para Okabe...",
                        "Calculando al mil millones por ciento con Senku...",
                        "Comprando maní para Anya...",
                        "Sellando los dedos de Sukuna...",
                        "Entrenando en la Habitación del Tiempo...",
                        "Canalizando el Hamon..."
                    ).random()
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = WakuPrimary, strokeWidth = 3.dp)
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = loadingEasterEgg,
                            color = Color(0xFFE4BEB7),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            } else if (errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = errorMessage!!,
                        color = WakuPink,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(WakuBg),
                    contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding() + 24.dp)
                ) {
                    // COMPONENTE 1: HERO SLIDER
                    if (heroItems.isNotEmpty()) {
                        item(key = "hero_slider") {
                            HeroSliderSection(
                                items = heroItems,
                                favoriteIds = favoriteIds,
                                onWatchClick = { hero ->
                                    if (uid != null) {
                                        saveToWatchingFirestore(
                                            db = db,
                                            uid = uid,
                                            animeId = hero.animeId,
                                            slug = hero.slug,
                                            title = hero.title,
                                            posterUrl = hero.posterUrl,
                                            episodeNumber = 1,
                                            episodeTitle = "Episodio 1",
                                            imageUrl = "https://cdn.animeav1.com/screenshots/${hero.animeId}/1.jpg"
                                        )
                                    }
                                    onPlayEpisode(hero.slug, 1)
                                },
                                onInfoClick = { hero ->
                                    onOpenAnimeDetail(hero.slug)
                                },
                                onToggleFavorite = { hero ->
                                    if (uid != null) {
                                        val isFav = !favoriteIds.contains(hero.animeId)
                                        val data = mapOf(
                                            "animeId" to hero.animeId,
                                            "slug" to hero.slug,
                                            "title" to hero.title,
                                            "posterUrl" to hero.posterUrl,
                                            "esFavorito" to isFav
                                        )
                                        db.collection("usuarios").document(uid)
                                            .collection("mi_lista").document(hero.animeId.toString())
                                            .set(data, SetOptions.merge())
                                    }
                                }
                            )
                        }
                    }

                    // COMPONENTE 2: CARRUSEL "SEGUIR VIENDO"
                    if (continueWatchingList.isNotEmpty()) {
                        item(key = "continue_watching") {
                            Spacer(modifier = Modifier.height(24.dp))
                            ContinueWatchingSection(
                                items = continueWatchingList,
                                onSeeAllClick = { onNavigateTab("mi_lista") },
                                onItemClick = { item ->
                                    if (item.slug.isNotBlank()) {
                                        onPlayEpisode(item.slug, item.episodeNumber)
                                    }
                                }
                            )
                        }
                    }

                    // COMPONENTE 3: LISTA VERTICAL "ÚLTIMOS ACTUALIZADOS"
                    item(key = "latest_header") {
                        Spacer(modifier = Modifier.height(28.dp))
                        LatestEpisodesHeader()
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    items(
                        items = latestEpisodes,
                        key = { it.episodeId }
                    ) { episode ->
                        LatestEpisodeRow(
                            episode = episode,
                            onClick = {
                                onOpenAnimeDetail(episode.slug)
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            WakuFloatingTopBar(
                backgroundAlpha = topBarAlpha,
                onSearchClick = { onNavigateTab("catalogo_search") },
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }
}

@Composable
fun WakuFloatingTopBar(
    backgroundAlpha: Float,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0.0f to WakuBg.copy(alpha = (backgroundAlpha + 0.30f).coerceAtMost(0.98f)),
                    0.75f to WakuBg.copy(alpha = backgroundAlpha),
                    1.0f to WakuBg.copy(alpha = (backgroundAlpha - 0.08f).coerceAtLeast(0f))
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "WAKU",
                    color = WakuText,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                SparkleStar(
                    symbol = "✦",
                    color = WakuGold,
                    fontSize = 18.sp,
                    delayMillis = 0
                )
            }

            IconButton(onClick = onSearchClick) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Buscar en catálogo",
                    tint = WakuText,
                    modifier = Modifier.size(25.dp)
                )
            }
        }
    }
}

@Composable
fun HeroSliderSection(
    items: List<HeroAnimeItem>,
    favoriteIds: Set<Int>,
    onWatchClick: (HeroAnimeItem) -> Unit,
    onInfoClick: (HeroAnimeItem) -> Unit,
    onToggleFavorite: (HeroAnimeItem) -> Unit
) {
    val context = LocalContext.current
    var currentIndex by remember { mutableIntStateOf(0) }
    val slideProgress = remember { Animatable(0f) }

    LaunchedEffect(currentIndex, items.size) {
        if (items.size > 1) {
            slideProgress.snapTo(0f)
            slideProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 6500, easing = LinearEasing)
            )
            currentIndex = (currentIndex + 1) % items.size
        }
    }

    var accumulatedDrag by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(590.dp)
            .background(WakuBg)
            .pointerInput(items.size) {
                detectHorizontalDragGestures(
                    onDragStart = { accumulatedDrag = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        accumulatedDrag += dragAmount
                    },
                    onDragEnd = {
                        if (items.size > 1) {
                            if (accumulatedDrag < -70f) {
                                currentIndex = (currentIndex + 1) % items.size
                            } else if (accumulatedDrag > 70f) {
                                currentIndex = (currentIndex - 1 + items.size) % items.size
                            }
                        }
                    }
                )
            }
    ) {
        AnimatedContent(
            targetState = currentIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            transitionSpec = {
                fadeIn(animationSpec = tween(durationMillis = 420, delayMillis = 130)) togetherWith
                        fadeOut(animationSpec = tween(durationMillis = 220))
            },
            label = "heroFadeTransition",
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = items[page]
            val isFavorite = favoriteIds.contains(item.animeId)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onInfoClick(item) }
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.posterUrl)
                        .size(Size.ORIGINAL)
                        .crossfade(false)
                        .build(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.86f)
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to WakuBg.copy(alpha = 0.22f),
                                0.20f to Color.Transparent,
                                0.58f to WakuBg.copy(alpha = 0.65f),
                                0.81f to WakuBg,
                                1.0f to WakuBg
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 28.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFF886C01).copy(alpha = 0.35f))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(WakuGold)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = item.genresText.uppercase(),
                            color = WakuGold,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (!item.clearLogoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = item.clearLogoUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                            modifier = Modifier
                                .height(48.dp)
                                .widthIn(max = 250.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Text(
                        text = item.title.uppercase(),
                        color = WakuText,
                        fontSize = if (item.clearLogoUrl.isNullOrBlank()) 24.sp else 15.sp,
                        lineHeight = if (item.clearLogoUrl.isNullOrBlank()) 28.sp else 19.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        text = "${item.categoryName.uppercase()} • ESTRENO EN EMISIÓN",
                        color = WakuGold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.8.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = item.synopsis,
                        color = Color(0xFFE4BEB7),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { onWatchClick(item) },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WakuPrimary,
                                contentColor = WakuText
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .shadow(
                                    elevation = 16.dp,
                                    shape = CircleShape,
                                    ambientColor = WakuPrimary,
                                    spotColor = WakuPrimary
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Comenzar a ver Ep. 1",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        IconButton(
                            onClick = { onToggleFavorite(item) },
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2F2926).copy(alpha = 0.85f))
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Filled.Bookmark else Icons.Outlined.BookmarkAdd,
                                contentDescription = "Añadir a Mi Lista",
                                tint = if (isFavorite) WakuGold else WakuText,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = { onInfoClick(item) },
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2F2926).copy(alpha = 0.85f))
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = "Ver ficha del anime",
                                tint = Color(0xFFE4BEB7),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(items.size) { index ->
                val isSelected = currentIndex == index
                val pillWidth by animateDpAsState(
                    targetValue = if (isSelected) 32.dp else 7.dp,
                    label = "pillWidth"
                )

                Box(
                    modifier = Modifier
                        .height(4.dp)
                        .width(pillWidth)
                        .clip(CircleShape)
                        .background(Color(0xFF3A3331))
                        .clickable { currentIndex = index }
                ) {
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(slideProgress.value)
                                .clip(CircleShape)
                                .background(WakuPrimary)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ContinueWatchingSection(
    items: List<ContinueWatchingItem>,
    onSeeAllClick: () -> Unit,
    onItemClick: (ContinueWatchingItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(5.dp)
                        .height(18.dp)
                        .clip(CircleShape)
                        .background(WakuPrimary)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Seguir Viendo",
                    color = WakuText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onSeeAllClick() }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "VER TODO",
                    color = WakuPink,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = WakuPink,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(items, key = { "${it.animeId}_${it.episodeNumber}" }) { item ->
                Column(
                    modifier = Modifier
                        .width(230.dp)
                        .clickable { onItemClick(item) }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF2F2926))
                    ) {
                        AsyncImage(
                            model = item.imageUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (item.isNextEpisode) WakuGreenDark.copy(alpha = 0.90f)
                                    else WakuBg.copy(alpha = 0.82f)
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = item.badgeText,
                                color = if (item.isNextEpisode) Color(0xFFD5FDC8) else WakuGold,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

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
                                    .fillMaxWidth(item.progressFraction)
                                    .background(WakuPrimary)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = item.title,
                        color = WakuText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val epSubtitle = if (item.episodeTitle.isNotBlank() &&
                        !item.episodeTitle.equals("Episodio ${item.episodeNumber}", ignoreCase = true)
                    ) {
                        "E${item.episodeNumber} • ${item.episodeTitle}"
                    } else {
                        "Episodio ${item.episodeNumber}"
                    }
                    Text(
                        text = epSubtitle,
                        color = Color(0xFFE4BEB7),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun LatestEpisodesHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Últimos Actualizados",
            color = WakuText,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(CircleShape)
                .background(WakuGreenDark.copy(alpha = 0.35f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            PulsingGreenDot()
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "EN EMISIÓN",
                color = WakuGreen,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}

/**
 * Miniatura optimizada sin Size.ORIGINAL para scroll fluido a 120 FPS
 */
@Composable
fun LatestEpisodeRow(
    episode: LatestEpisodeItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(WakuSurface)
            .clickable { onClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(122.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF2F2926))
        ) {
            AsyncImage(
                model = episode.screenshotUrl,
                contentDescription = episode.animeTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(WakuBg.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = WakuText,
                    modifier = Modifier.size(16.dp)
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(WakuBg.copy(alpha = 0.82f))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = episode.durationText,
                    color = WakuText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = episode.timeAgoText,
                color = WakuGold,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = episode.animeTitle,
                color = WakuText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            val subtitleText = if (!episode.episodeTitle.isNullOrBlank()) {
                "Episodio ${episode.episodeNumber} • ${episode.episodeTitle}"
            } else {
                "Episodio ${episode.episodeNumber} • Estreno"
            }
            Text(
                text = subtitleText,
                color = Color(0xFFE4BEB7),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

/**
 * Barra de navegación inferior global: La pestaña PERFIL muestra la foto real del usuario
 */
@Composable
fun WakuBottomNavBar(
    selectedRoute: String,
    avatarUrl: String? = null,
    onSelectRoute: (String) -> Unit
) {
    val navItems = remember {
        listOf(
            BottomNavItem("inicio", "INICIO", Icons.Filled.Home),
            BottomNavItem("catalogo", "CATÁLOGO", Icons.Outlined.GridView),
            BottomNavItem("mi_lista", "MI LISTA", Icons.Outlined.BookmarkBorder),
            BottomNavItem("horario", "HORARIO", Icons.Outlined.CalendarToday),
            BottomNavItem("perfil", "PERFIL", Icons.Outlined.AccountCircle)
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF120D0B).copy(alpha = 0.94f))
            .navigationBarsPadding()
            .height(64.dp)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        navItems.forEach { item ->
            val isSelected = selectedRoute == item.route
            val tint = if (isSelected) WakuPrimary else Color(0xFFE4BEB7)

            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelectRoute(item.route) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (item.route == "perfil" && !avatarUrl.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .size(23.dp)
                            .clip(CircleShape)
                            .border(
                                width = if (isSelected) 1.8.dp else 1.dp,
                                color = if (isSelected) WakuPrimary else WakuGold.copy(alpha = 0.65f),
                                shape = CircleShape
                            )
                    ) {
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = "Perfil",
                            contentScale = ContentScale.Crop,
                            alignment = FaceTopAlignment,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        tint = tint,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.label,
                    color = tint,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}

private fun saveToWatchingFirestore(
    db: FirebaseFirestore,
    uid: String,
    animeId: Int,
    slug: String,
    title: String,
    posterUrl: String,
    episodeNumber: Int,
    episodeTitle: String,
    imageUrl: String
) {
    val data = mapOf(
        "animeId" to animeId,
        "slug" to slug,
        "title" to title,
        "posterUrl" to posterUrl,
        "episodioActual" to episodeNumber,
        "episodeTitle" to episodeTitle,
        "imageUrl" to imageUrl,
        "estado" to "VIENDO",
        "progress" to 0.02f,
        "badgeText" to "Quedan 14m",
        "isNextEpisode" to false,
        "updatedAt" to System.currentTimeMillis()
    )
    db.collection("usuarios").document(uid)
        .collection("mi_lista").document(animeId.toString())
        .set(data, SetOptions.merge())
}