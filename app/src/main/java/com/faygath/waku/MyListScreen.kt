package com.faygath.waku

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.faygath.waku.ui.theme.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.outlined.DeleteOutline
import kotlinx.coroutines.delay

enum class MyListTab {
    FAVORITOS, VIENDO, HISTORIAL
}

enum class MyListSortMode(val label: String) {
    RECIENTES("Recientes"),
    ALFABETICO("A - Z"),
    PROGRESO("Progreso")
}

data class UserAnimeEntry(
    val docId: String,
    val animeId: Int,
    val slug: String,
    val title: String,
    val posterUrl: String,
    val screenshotUrl: String,
    val isFavorite: Boolean,
    val status: String, // "VIENDO", "FINALIZADO", "DESCARTADO", "POR_VER", ""
    val currentEpisode: Int,
    val episodeTitle: String,
    val totalEpisodes: Int,
    val watchedEpisodesCount: Int,
    val progressFraction: Float,
    val isAiring: Boolean,
    val primaryGenre: String,
    val updatedAt: Long
)

@Composable
fun MyListScreen(
    initialTab: MyListTab = MyListTab.FAVORITOS,
    onNavigateTab: (String) -> Unit,
    onOpenAnimeDetail: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid

    var userAvatarUrl by remember { mutableStateOf<String?>(null) }
    var activeTab by remember { mutableStateOf(initialTab) }
    var sortMode by remember { mutableStateOf(MyListSortMode.RECIENTES) }

    var allEntries by remember { mutableStateOf<List<UserAnimeEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val enrichedSessionIds = remember { mutableSetOf<String>() }
    // Estado para nuestra notificación personalizada (reemplaza al Toast nativo de Android)
    var noticeMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(noticeMessage) {
        if (noticeMessage != null) {
            delay(3500) // Dura 3.5 segundos visible
            noticeMessage = null
        }
    }

    // 1. Escuchar avatar y colección `mi_lista` del usuario en tiempo real desde Firestore
    LaunchedEffect(uid) {
        if (uid == null) {
            isLoading = false
            return@LaunchedEffect
        }

        db.collection("usuarios").document(uid)
            .addSnapshotListener { snap, _ ->
                userAvatarUrl = snap?.getString("avatarUrl")
            }

        db.collection("usuarios").document(uid).collection("mi_lista")
            .addSnapshotListener { snap, _ ->
                if (snap != null) {
                    val parsed = snap.documents.mapNotNull { doc ->
                        val animeId = doc.getLong("animeId")?.toInt()
                            ?: doc.id.toIntOrNull() ?: 0
                        val slug = doc.getString("slug").orEmpty()
                        val title = doc.getString("title").orEmpty()
                        if (title.isBlank() && slug.isBlank()) return@mapNotNull null

                        val isFav = doc.getBoolean("esFavorito") == true
                        val status = doc.getString("estado").orEmpty()
                        val currentEp = doc.getLong("episodioActual")?.toInt() ?: 1
                        val epTitle = doc.getString("episodeTitle") ?: "Episodio $currentEp"
                        val totalEps = doc.getLong("totalEpisodes")?.toInt() ?: 0

                        val watchedList = doc.get("watchedEpisodes") as? List<*>
                        val watchedCount = watchedList?.size ?: when (status) {
                            "FINALIZADO" -> if (totalEps > 0) totalEps else currentEp
                            "VIENDO", "DESCARTADO" -> (currentEp - 1).coerceAtLeast(0)
                            else -> 0
                        }

                        val progress = (doc.getDouble("progress")?.toFloat() ?: 0f).coerceIn(0f, 1f)
                        val poster = doc.getString("posterUrl")
                            ?.takeIf { it.isNotBlank() }
                            ?: "https://cdn.animeav1.com/covers/$animeId.jpg"
                        val screen = doc.getString("imageUrl")
                            ?.takeIf { it.isNotBlank() }
                            ?: "https://cdn.animeav1.com/screenshots/$animeId/$currentEp.jpg"

                        val isAiring = doc.getBoolean("isAiring") ?: false
                        val genre = doc.getString("primaryGenre") ?: "Anime"
                        val updatedAt = doc.getLong("updatedAt") ?: 0L

                        UserAnimeEntry(
                            docId = doc.id,
                            animeId = animeId,
                            slug = slug,
                            title = title,
                            posterUrl = poster,
                            screenshotUrl = screen,
                            isFavorite = isFav,
                            status = status,
                            currentEpisode = currentEp,
                            episodeTitle = epTitle,
                            totalEpisodes = totalEps,
                            watchedEpisodesCount = watchedCount,
                            progressFraction = progress,
                            isAiring = isAiring,
                            primaryGenre = genre,
                            updatedAt = updatedAt
                        )
                    }
                    allEntries = parsed
                    isLoading = false

                    // Enriquecer en segundo plano cualquier entrada a la que le falten metadatos (totalEpisodes o género)
                    // Enriquecer en segundo plano solo si falta el género o si es un anime finalizado sin totalEpisodes
                    parsed.filter { (!it.isAiring && it.totalEpisodes == 0) || it.primaryGenre == "Anime" }.forEach { incomplete ->
                        if (incomplete.slug.isNotBlank() && enrichedSessionIds.add(incomplete.docId)) {
                            coroutineScope.launch {
                                val dummyCatalogItem = CatalogAnimeItem(
                                    animeId = incomplete.animeId,
                                    slug = incomplete.slug,
                                    title = incomplete.title,
                                    posterUrl = incomplete.posterUrl,
                                    topLeftBadge = "",
                                    scoreText = "8.5",
                                    isAiring = incomplete.isAiring,
                                    primaryGenre = incomplete.primaryGenre,
                                    allGenres = emptyList(),
                                    categoryName = "TV Anime",
                                    year = 2025
                                )
                                val enriched = WakuRepository.enrichCatalogItem(context, dummyCatalogItem)
                                val extractedEps = Regex("""(\d+)\s*Eps""").find(enriched.topLeftBadge)
                                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 12

                                val updates = mutableMapOf<String, Any>(
                                    "posterUrl" to enriched.posterUrl,
                                    "isAiring" to enriched.isAiring,
                                    "primaryGenre" to enriched.primaryGenre
                                )
                                if (!enriched.isAiring && incomplete.totalEpisodes == 0) {
                                    updates["totalEpisodes"] = extractedEps
                                }
                                db.collection("usuarios").document(uid)
                                    .collection("mi_lista").document(incomplete.docId)
                                    .set(updates, SetOptions.merge())
                            }
                        }
                    }
                } else {
                    isLoading = false
                }
            }
    }

    // 2. Filtrar y ordenar según la pestaña activa y el modo de ordenamiento
    val favoritesList = remember(allEntries, sortMode) {
        allEntries.filter { it.isFavorite }.sortedWith(getComparator(sortMode))
    }

    val watchingList = remember(allEntries, sortMode) {
        allEntries.filter { it.status == "VIENDO" }.sortedWith(getComparator(sortMode))
    }

    val historyList = remember(allEntries, sortMode) {
        allEntries.filter {
            it.status in listOf("FINALIZADO", "DESCARTADO", "POR_VER", "VIENDO") || it.isFavorite
        }.sortedWith(getComparator(sortMode))
    }

    val currentBadgeText = when (activeTab) {
        MyListTab.FAVORITOS -> "${favoritesList.size} ${if (favoritesList.size == 1) "título" else "títulos"}"
        MyListTab.VIENDO -> "${watchingList.size} EN CURSO"
        MyListTab.HISTORIAL -> "${historyList.size} ${if (historyList.size == 1) "título" else "títulos"}"
    }

    Scaffold(
        containerColor = WakuBg,
        topBar = {
            MyListTopBar(
                showSearchIcon = activeTab != MyListTab.HISTORIAL,
                onSearchClick = { onNavigateTab("catalogo_search") }
            )
        },
        bottomBar = {
            WakuBottomNavBar(
                selectedRoute = "mi_lista",
                avatarUrl = userAvatarUrl,
                onSelectRoute = { route ->
                    if (route != "mi_lista") {
                        onNavigateTab(route)
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WakuBg)
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            // Resplandor ambiental superior sutil (presente en codefavs.html)
            Box(
                modifier = Modifier
                    .width(300.dp)
                    .height(120.dp)
                    .align(Alignment.TopCenter)
                    .offset(y = (-30).dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(WakuPrimary.copy(alpha = 0.12f), Color.Transparent)
                        )
                    )
            )

            Column(modifier = Modifier.fillMaxSize()) {
                // CABECERA: "Mi Lista" + Badge + Botón de Ordenar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Mi Lista",
                            color = WakuText,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (activeTab == MyListTab.VIENDO) Color(0xFF2F2926)
                                    else Color(0xFF241E1C)
                                )
                                .padding(horizontal = 9.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = currentBadgeText,
                                color = if (activeTab == MyListTab.HISTORIAL) Color(0xFFE4BEB7) else WakuGold,
                                fontSize = if (activeTab == MyListTab.VIENDO) 10.sp else 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = if (activeTab == MyListTab.VIENDO) 0.8.sp else 0.3.sp
                            )
                        }
                    }

                    // Botón de Ordenar (Píldora con etiqueta en Historial o botón circular en Favoritos/Viendo)
                    val nextSortMode = {
                        sortMode = when (sortMode) {
                            MyListSortMode.RECIENTES -> MyListSortMode.ALFABETICO
                            MyListSortMode.ALFABETICO -> MyListSortMode.PROGRESO
                            MyListSortMode.PROGRESO -> MyListSortMode.RECIENTES
                        }
                    }

                    if (activeTab == MyListTab.HISTORIAL || sortMode != MyListSortMode.RECIENTES) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0xFF241E1C))
                                .clickable { nextSortMode() }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SwapVert,
                                contentDescription = "Ordenar",
                                tint = WakuGold,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = sortMode.label,
                                color = Color(0xFFE4BEB7),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF241E1C))
                                .clickable { nextSortMode() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SwapVert,
                                contentDescription = "Ordenar lista",
                                tint = Color(0xFFE4BEB7),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // CONTROL SEGMENTADO DE 3 PESTAÑAS (Favoritos | Viendo | Historial)
                SegmentedThreeTabBar(
                    selectedTab = activeTab,
                    onSelectTab = { activeTab = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // CONTENIDO DINÁMICO DE LAS 3 PESTAÑAS
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = innerPadding.calculateBottomPadding()),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = WakuPrimary, strokeWidth = 3.dp)
                    }
                } else {
                    AnimatedContent(
                        targetState = activeTab,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(220)) togetherWith
                                    fadeOut(animationSpec = tween(160))
                        },
                        label = "myListTabs",
                        modifier = Modifier.fillMaxSize()
                    ) { tab ->
                        when (tab) {
                            MyListTab.FAVORITOS -> {
                                FavoritosTabView(
                                    items = favoritesList,
                                    bottomPadding = innerPadding.calculateBottomPadding(),
                                    onOpenAnime = { onOpenAnimeDetail(it.slug) },
                                    onRemoveFavorite = { item ->
                                        if (uid != null) {
                                            db.collection("usuarios").document(uid)
                                                .collection("mi_lista").document(item.docId)
                                                .set(mapOf("esFavorito" to false), SetOptions.merge())
                                            noticeMessage = "Quitaste \"${item.title}\" de tus Favoritos"
                                        }
                                    }
                                )
                            }

                            MyListTab.VIENDO -> {
                                ViendoTabView(
                                    items = watchingList,
                                    bottomPadding = innerPadding.calculateBottomPadding(),
                                    onOpenAnime = { onOpenAnimeDetail(it.slug) },
                                    onToggleFavorite = { item ->
                                        if (uid != null) {
                                            val newFav = !item.isFavorite
                                            db.collection("usuarios").document(uid)
                                                .collection("mi_lista").document(item.docId)
                                                .set(
                                                    mapOf(
                                                        "esFavorito" to newFav,
                                                        "updatedAt" to System.currentTimeMillis()
                                                    ),
                                                    SetOptions.merge()
                                                )
                                            noticeMessage = if (newFav) {
                                                "Añadiste \"${item.title}\" a Favoritos"
                                            } else {
                                                "Quitaste \"${item.title}\" de Favoritos"
                                            }
                                        }
                                    },
                                    onMarkFinished = { item ->
                                        if (uid != null) {
                                            val total = if (item.totalEpisodes > 0) item.totalEpisodes else item.currentEpisode
                                            db.collection("usuarios").document(uid)
                                                .collection("mi_lista").document(item.docId)
                                                .set(
                                                    mapOf(
                                                        "estado" to "FINALIZADO",
                                                        "progress" to 1.0f,
                                                        "episodioActual" to total,
                                                        "updatedAt" to System.currentTimeMillis()
                                                    ),
                                                    SetOptions.merge()
                                                )
                                            noticeMessage = "Marcaste \"${item.title}\" como Finalizado"
                                        }
                                    },
                                    onMarkDropped = { item ->
                                        if (uid != null) {
                                            db.collection("usuarios").document(uid)
                                                .collection("mi_lista").document(item.docId)
                                                .set(
                                                    mapOf(
                                                        "estado" to "DESCARTADO",
                                                        "updatedAt" to System.currentTimeMillis()
                                                    ),
                                                    SetOptions.merge()
                                                )
                                            noticeMessage = "Moviste \"${item.title}\" a Descartados"
                                        }
                                    }
                                )
                            }

                            MyListTab.HISTORIAL -> {
                                HistorialTabView(
                                    items = historyList,
                                    bottomPadding = innerPadding.calculateBottomPadding(),
                                    onOpenAnime = { onOpenAnimeDetail(it.slug) },
                                    onDeleteFromHistory = { item ->
                                        if (uid != null) {
                                            db.collection("usuarios").document(uid)
                                                .collection("mi_lista").document(item.docId)
                                                .delete()
                                            noticeMessage = "Se borró \"${item.title}\" de tu historial"
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

            }
            // Notificación flotante personalizada de WAKU (sin ícono de Android y con texto completo)
            AnimatedVisibility(
                visible = noticeMessage != null,
                enter = fadeIn(tween(200)) + slideInVertically { it / 2 },
                exit = fadeOut(tween(250)) + slideOutVertically { it / 2 },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = innerPadding.calculateBottomPadding() + 16.dp
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
private fun MyListTopBar(
    showSearchIcon: Boolean,
    onSearchClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WakuBg.copy(alpha = 0.90f))
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 16.dp),
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

        if (showSearchIcon) {
            IconButton(onClick = onSearchClick) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Buscar",
                    tint = Color(0xFFE4BEB7),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * Control deslizante segmentado de 3 pestañas fiel a los 3 mockups de Stitch
 */
@Composable
private fun SegmentedThreeTabBar(
    selectedTab: MyListTab,
    onSelectTab: (MyListTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(Color(0xFF201A18))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tabs = listOf(
            MyListTab.FAVORITOS to "Favoritos",
            MyListTab.VIENDO to "Viendo",
            MyListTab.HISTORIAL to "Historial"
        )

        tabs.forEach { (tab, label) ->
            val isSelected = selectedTab == tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (isSelected) {
                            Modifier.shadow(
                                elevation = 12.dp,
                                shape = CircleShape,
                                ambientColor = WakuPrimary,
                                spotColor = WakuPrimary
                            )
                        } else Modifier
                    )
                    .clip(CircleShape)
                    .background(if (isSelected) WakuPrimary else Color.Transparent)
                    .clickable { onSelectTab(tab) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (isSelected) WakuText else Color(0xFFE4BEB7).copy(alpha = 0.80f),
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                    letterSpacing = 0.3.sp
                )
            }
        }
    }
}

// =========================================================================
// PESTAÑA 1: FAVORITOS (Fiel a codefavs.html y screen_3.jpg)
// =========================================================================
@Composable
private fun FavoritosTabView(
    items: List<UserAnimeEntry>,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onOpenAnime: (UserAnimeEntry) -> Unit,
    onRemoveFavorite: (UserAnimeEntry) -> Unit
) {
    if (items.isEmpty()) {
        EmptyMyListState(
            title = "Aún no tienes favoritos guardados",
            subtitle = "Toca el ícono de marcador en cualquier anime del Inicio o Catálogo para guardarlo aquí."
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 6.dp,
            bottom = bottomPadding + 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items, key = { it.docId }) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF201A18))
                    .clickable { onOpenAnime(item) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Póster vertical 68x102dp
                AsyncImage(
                    model = item.posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(68.dp)
                        .height(102.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF3A3331))
                )

                Spacer(modifier = Modifier.width(14.dp))

                // Información central
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = item.title,
                        color = WakuText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    val epsLabel = if (item.totalEpisodes > 0) {
                        "${item.totalEpisodes} Episodios"
                    } else {
                        "Serie TV"
                    }
                    Text(
                        text = epsLabel,
                        color = WakuGold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.isAiring) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(WakuGreen)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "En emisión • ${item.primaryGenre}",
                                color = Color(0xFFE4BEB7),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                text = "Finalizado • ${item.primaryGenre}",
                                color = Color(0xFFE4BEB7),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Botón circular derecho con corazón relleno
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(WakuPrimary.copy(alpha = 0.16f))
                        .clickable { onRemoveFavorite(item) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "Quitar de favoritos",
                        tint = WakuPrimary,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }

        // Pie de página "✦ FIN DE TUS FAVORITOS ✦"
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "✦",
                    color = WakuGold.copy(alpha = 0.55f),
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "FIN DE TUS FAVORITOS",
                    color = Color(0xFFE4BEB7).copy(alpha = 0.45f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "✦",
                    color = WakuGold.copy(alpha = 0.55f),
                    fontSize = 13.sp
                )
            }
        }
    }
}

// =========================================================================
// PESTAÑA 2: VIENDO (Fiel a codeviendo.html y screen_2.jpg)
// =========================================================================
@Composable
private fun ViendoTabView(
    items: List<UserAnimeEntry>,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onOpenAnime: (UserAnimeEntry) -> Unit,
    onToggleFavorite: (UserAnimeEntry) -> Unit,
    onMarkFinished: (UserAnimeEntry) -> Unit,
    onMarkDropped: (UserAnimeEntry) -> Unit
) {
    if (items.isEmpty()) {
        EmptyMyListState(
            title = "No tienes episodios en curso",
            subtitle = "Cuando empieces a ver un anime, aparecerá aquí con tu minuto exacto de progreso."
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 6.dp,
            bottom = bottomPadding + 28.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(items, key = { it.docId }) { item ->
            var menuExpanded by remember { mutableStateOf(false) }
            val percent = (item.progressFraction * 100).toInt().coerceIn(0, 100)
            val isJustReady = percent <= 5
            val remainingMins = ((1f - item.progressFraction) * 24f).toInt().coerceAtLeast(1)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF241E1C))
                    .clickable { onOpenAnime(item) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Miniatura 16:9 (130x73dp)
                Box(
                    modifier = Modifier
                        .width(130.dp)
                        .height(73.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF3A3331))
                ) {
                    AsyncImage(
                        model = item.screenshotUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Degradado sutil para contraste
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Black.copy(alpha = 0.25f),
                                        Color.Transparent,
                                        Color(0xFF120D0B).copy(alpha = 0.75f)
                                    )
                                )
                            )
                    )

                    // Botón Play central
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF120D0B).copy(alpha = 0.72f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = WakuText,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Píldora superior derecha ("Quedan 8m" o "Comenzar" en dorado)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (isJustReady) Color(0xFF886C01).copy(alpha = 0.92f)
                                else Color(0xFF120D0B).copy(alpha = 0.82f)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isJustReady) "Comenzar" else "Quedan ${remainingMins}m",
                            color = if (isJustReady) Color(0xFFFFF3DB) else WakuText,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Barra de progreso carmesí abajo
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color(0xFF3E3835).copy(alpha = 0.55f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(if (isJustReady) 0f else item.progressFraction)
                                .background(WakuPrimary)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Metadatos centrales
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = item.title,
                        color = WakuText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    val epSub = if (item.episodeTitle.isNotBlank() &&
                        !item.episodeTitle.equals("Episodio ${item.currentEpisode}", ignoreCase = true)
                    ) {
                        "Ep. ${item.currentEpisode} - ${item.episodeTitle}"
                    } else {
                        "Episodio ${item.currentEpisode}"
                    }

                    Text(
                        text = epSub,
                        color = Color(0xFFE4BEB7).copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val statusDotColor = when {
                            isJustReady -> WakuGreen
                            percent >= 50 -> WakuGold
                            else -> Color(0xFFAA8983)
                        }
                        val statusTextColor = when {
                            isJustReady -> WakuGreen
                            percent >= 50 -> WakuGold
                            else -> Color(0xFFE4BEB7).copy(alpha = 0.72f)
                        }

                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(statusDotColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isJustReady) "Nuevo episodio listo" else "$percent% completado",
                            color = statusTextColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Botón 3 puntos (⋮) con Popover oscuro fiel a codeviendo.html
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = "Opciones",
                            tint = Color(0xFFE4BEB7),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = Color(0xFF120D0B),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.border(1.dp, Color(0xFF3A3331), RoundedCornerShape(16.dp))
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = if (item.isFavorite) "Quitar de Favoritos" else "Añadir a Favoritos",
                                    color = WakuText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (item.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = null,
                                    tint = WakuPink,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onToggleFavorite(item)
                            }
                        )

                        HorizontalDivider(
                            color = Color(0xFF3A3331),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Marcar Finalizado",
                                    color = WakuGreen,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = WakuGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onMarkFinished(item)
                            }
                        )

                        HorizontalDivider(
                            color = Color(0xFF3A3331),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )

                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Marcar Descartado",
                                    color = WakuPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = null,
                                    tint = WakuPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onMarkDropped(item)
                            }
                        )
                    }
                }
            }
        }

        // Pie de página "✦ FIN DE EPISODIOS EN CURSO ✦"
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "✦",
                    color = WakuGold.copy(alpha = 0.55f),
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "FIN DE EPISODIOS EN CURSO",
                    color = WakuGold.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "✦",
                    color = WakuGold.copy(alpha = 0.55f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

// =========================================================================
// PESTAÑA 3: HISTORIAL (Fiel a code.html y screen.jpg)
// =========================================================================
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HistorialTabView(
    items: List<UserAnimeEntry>,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onOpenAnime: (UserAnimeEntry) -> Unit,
    onDeleteFromHistory: (UserAnimeEntry) -> Unit
) {
    if (items.isEmpty()) {
        EmptyMyListState(
            title = "Tu historial está vacío",
            subtitle = "Aquí aparecerán todos los animes que hayas completado, descartado o añadido a tu lista."
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 6.dp,
            bottom = bottomPadding + 28.dp
        ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        items(items, key = { it.docId }) { item ->
            var showDeleteMenu by remember { mutableStateOf(false) }
            val totalEpsLabel = if (item.totalEpisodes > 0) item.totalEpisodes.toString() else "?"
            val watchedEps = when (item.status) {
                "FINALIZADO" -> if (item.totalEpisodes > 0) item.totalEpisodes else item.currentEpisode
                "VIENDO", "DESCARTADO" -> item.currentEpisode
                else -> item.watchedEpisodesCount
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .combinedClickable(
                            onClick = { onOpenAnime(item) },
                            onLongClick = { showDeleteMenu = true }
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF241E1C))
                    ) {
                        AsyncImage(
                            model = item.posterUrl,
                            contentDescription = item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .align(Alignment.TopCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(
                                            Color(0xFF171210).copy(alpha = 0.82f),
                                            Color.Transparent
                                        )
                                    )
                                )
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(Color(0xFF120D0B).copy(alpha = 0.82f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "$watchedEps / $totalEpsLabel",
                                    color = WakuText,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            HistoryStatusBadge(status = item.status)
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF3A3331).copy(alpha = 0.90f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (item.status == "FINALIZADO") Icons.Outlined.Replay else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color(0xFFFFDAD3),
                                modifier = Modifier.size(18.dp)
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

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = formatHistorySubtitle(item),
                        color = Color(0xFFE4BEB7),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Menú emergente al mantener presionada la carátula
                DropdownMenu(
                    expanded = showDeleteMenu,
                    onDismissRequest = { showDeleteMenu = false },
                    containerColor = Color(0xFF120D0B),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.border(1.dp, Color(0xFF3A3331), RoundedCornerShape(14.dp))
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Borrar del historial",
                                color = WakuPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                tint = WakuPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            showDeleteMenu = false
                            onDeleteFromHistory(item)
                        }
                    )
                }
            }
        }

        item(span = { GridItemSpan(2) }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(Color(0xFF3A3331))
                )
                Text(
                    text = "✦",
                    color = WakuGold,
                    fontSize = 10.sp
                )
                Text(
                    text = "FIN DEL HISTORIAL",
                    color = Color(0xFFE4BEB7),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "✦",
                    color = WakuGold,
                    fontSize = 10.sp
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(Color(0xFF3A3331))
                )
            }
        }
    }
}

@Composable
private fun HistoryStatusBadge(status: String) {
    when (status) {
        "FINALIZADO" -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFF120D0B).copy(alpha = 0.82f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "✓ Finalizado",
                    color = WakuGreen,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        "DESCARTADO" -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFF120D0B).copy(alpha = 0.82f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "✕ Descartado",
                    color = WakuPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        "VIENDO" -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFF120D0B).copy(alpha = 0.82f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(WakuGreen)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Viendo",
                    color = WakuGreen,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        else -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFF120D0B).copy(alpha = 0.82f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(WakuGold)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Por ver",
                    color = WakuGold,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun EmptyMyListState(
    title: String,
    subtitle: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "✦",
                color = WakuGold,
                fontSize = 28.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                color = WakuText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                color = Color(0xFFE4BEB7).copy(alpha = 0.75f),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

private fun getComparator(mode: MyListSortMode): Comparator<UserAnimeEntry> {
    return when (mode) {
        MyListSortMode.RECIENTES -> compareByDescending { it.updatedAt }
        MyListSortMode.ALFABETICO -> compareBy { it.title.lowercase() }
        MyListSortMode.PROGRESO -> compareByDescending<UserAnimeEntry> { it.watchedEpisodesCount }
            .thenByDescending { it.progressFraction }
    }
}

private fun formatHistorySubtitle(item: UserAnimeEntry): String {
    val relative = if (item.updatedAt > 0L) {
        val diffMillis = (System.currentTimeMillis() - item.updatedAt).coerceAtLeast(0L)
        val hours = TimeUnit.MILLISECONDS.toHours(diffMillis)
        val days = TimeUnit.MILLISECONDS.toDays(diffMillis)
        when {
            hours < 1 -> "hace unos minutos"
            hours < 24 -> "hace $hours h"
            days == 1L -> "ayer"
            days < 7 -> "hace $days días"
            else -> "la semana pasada"
        }
    } else {
        "recientemente"
    }

    return when (item.status) {
        "FINALIZADO" -> "Completado $relative"
        "DESCARTADO" -> "Descartado en Ep. ${item.currentEpisode}"
        "VIENDO" -> "Viendo Ep. ${item.currentEpisode} • $relative"
        else -> "Añadido $relative"
    }
}