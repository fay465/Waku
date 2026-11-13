package com.faygath.waku

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.faygath.waku.ui.theme.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.Normalizer

private data class CatalogChipOption(
    val id: String,
    val label: String,
    val hasGreenDot: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    autoFocusSearch: Boolean = false,
    onNavigateTab: (String) -> Unit,
    onOpenAnimeDetail: (String) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchFocusRequester = remember { FocusRequester() }

    var userAvatarUrl by remember { mutableStateOf<String?>(null) }

    // Estados del Catálogo y Paginación
    var allLoadedItems by remember { mutableStateOf<List<CatalogAnimeItem>>(emptyList()) }
    var availableOfficialGenres by remember {
        mutableStateOf(WakuRepository.DEFAULT_OFFICIAL_GENRES)
    }
    var totalCatalogCount by remember { mutableIntStateOf(1420) }
    var currentPage by remember { mutableIntStateOf(1) }
    var hasNextPage by remember { mutableStateOf(true) }
    var isInitialLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Búsqueda y Chip Rápido principal
    var searchInput by remember { mutableStateOf("") }
    var debouncedSearch by remember { mutableStateOf("") }
    var selectedChipId by remember { mutableStateOf("all") }

    // Filtros Avanzados del Modal
    var showFilterModal by remember { mutableStateOf(false) }
    var appliedCategoryType by remember { mutableStateOf("Todos") }
    var appliedGenres by remember { mutableStateOf<Set<String>>(emptySet()) }
    var appliedAudioFormat by remember { mutableStateOf("Todos") }
    var appliedYearRange by remember { mutableStateOf("Todos") }

    val gridState = rememberLazyGridState()

    val filterChips = remember {
        listOf(
            CatalogChipOption("all", "Todos"),
            CatalogChipOption("season", "Temporada", hasGreenDot = true),
            CatalogChipOption("latino", "Doblaje Latino"),
            CatalogChipOption("movies", "Películas"),
            CatalogChipOption("Shounen", "Shounen"),
            CatalogChipOption("Seinen", "Seinen"),
            CatalogChipOption("Shoujo", "Shoujo"),
            CatalogChipOption("Josei", "Josei"),
            CatalogChipOption("Acción", "Acción"),
            CatalogChipOption("Romance", "Romance"),
            CatalogChipOption("Fantasía", "Fantasía"),
            CatalogChipOption("Comedia", "Comedia")
        )
    }

    LaunchedEffect(autoFocusSearch) {
        if (autoFocusSearch) {
            delay(180) // Espera a que termine el slide para abrir el teclado con fluidez
            runCatching {
                searchFocusRequester.requestFocus()
                keyboardController?.show()
            }
        }
    }

    // 1. Escuchar avatar del usuario
    LaunchedEffect(uid) {
        if (uid != null) {
            db.collection("usuarios").document(uid)
                .addSnapshotListener { snap, _ ->
                    userAvatarUrl = snap?.getString("avatarUrl")
                }
        }
    }

    // 2. Debounce para el buscador
    LaunchedEffect(searchInput) {
        delay(400)
        debouncedSearch = searchInput.trim()
    }

    // 3. Cargar páginas del catálogo cuando cambian los filtros o la búsqueda
    LaunchedEffect(
        debouncedSearch,
        selectedChipId,
        appliedCategoryType,
        appliedGenres,
        appliedAudioFormat,
        appliedYearRange
    ) {
        isInitialLoading = true
        isLoadingMore = false
        errorMessage = null
        currentPage = 1

        val res = WakuRepository.fetchCatalogPage(
            context = context,
            page = 1,
            searchQuery = debouncedSearch,
            quickChip = selectedChipId,
            selectedCategoryType = appliedCategoryType,
            selectedGenres = appliedGenres,
            audioFormat = appliedAudioFormat,
            yearFilter = appliedYearRange
        )

        res.onSuccess { pageData ->
            allLoadedItems = pageData.items
            totalCatalogCount = pageData.totalCount
            hasNextPage = pageData.hasNextPage
            if (pageData.availableGenres.isNotEmpty()) {
                availableOfficialGenres = pageData.availableGenres
            }
            isInitialLoading = false

            // Solo precargamos página 2 si NO es una búsqueda puntual con pocos resultados
            if (pageData.hasNextPage && debouncedSearch.isEmpty()) {
                WakuRepository.fetchCatalogPage(
                    context = context,
                    page = 2,
                    searchQuery = "",
                    quickChip = selectedChipId,
                    selectedCategoryType = appliedCategoryType,
                    selectedGenres = appliedGenres,
                    audioFormat = appliedAudioFormat,
                    yearFilter = appliedYearRange
                ).onSuccess { nextData ->
                    val existingIds = allLoadedItems.map { it.animeId }.toSet()
                    val newItems = nextData.items.filter { !existingIds.contains(it.animeId) }
                    if (newItems.isEmpty()) {
                        hasNextPage = false
                    } else {
                        allLoadedItems = allLoadedItems + newItems
                        currentPage = 2
                        hasNextPage = nextData.hasNextPage
                    }
                }
            }
        }.onFailure { err ->
            errorMessage = err.localizedMessage ?: "Error al cargar el catálogo"
            isInitialLoading = false
        }
    }

    // 4. Enriquecer items en segundo plano
    val isDubFilterRequested = selectedChipId == "latino" || appliedAudioFormat == "Doblaje Español"

    LaunchedEffect(
        allLoadedItems.size,
        debouncedSearch,
        selectedChipId,
        appliedCategoryType,
        appliedGenres,
        appliedAudioFormat,
        appliedYearRange
    ) {
        val toEnrich = allLoadedItems.filter { !it.isEnriched }
        if (toEnrich.isEmpty()) return@LaunchedEffect

        val semaphore = Semaphore(6)
        toEnrich.forEach { item ->
            launch {
                semaphore.withPermit {
                    val enriched = WakuRepository.enrichCatalogItem(
                        context = context,
                        item = item,
                        verifyDubOnEp1 = isDubFilterRequested
                    )
                    allLoadedItems = allLoadedItems.map { current ->
                        if (current.animeId == enriched.animeId) enriched else current
                    }
                }
            }
        }
    }

    // 5. Filtrado combinado (Respeta los resultados por sinónimos/AniList del servidor cuando se busca por texto)
    val hasCategoryOrModalFilter = selectedChipId != "all" ||
            appliedCategoryType != "Todos" ||
            appliedGenres.isNotEmpty() ||
            appliedAudioFormat != "Todos" ||
            appliedYearRange != "Todos"

    val hasAnyFilterActive = hasCategoryOrModalFilter || searchInput.isNotBlank()

    val displayedItems = remember(
        allLoadedItems,
        searchInput,
        debouncedSearch,
        selectedChipId,
        appliedCategoryType,
        appliedGenres,
        appliedAudioFormat,
        appliedYearRange
    ) {
        val needsEnrichmentFirst = selectedChipId !in listOf("all", "movies") ||
                appliedGenres.isNotEmpty() ||
                appliedAudioFormat != "Todos" ||
                appliedYearRange != "Todos"

        allLoadedItems.filter { item ->
            if (needsEnrichmentFirst && !item.isEnriched) {
                return@filter false
            }

            // Si el usuario buscó en el servidor (debouncedSearch activo), los items de allLoadedItems
            // ya vienen resueltos por el buscador inteligente (incluyendo sinónimos como "demon slayer" -> "Kimetsu no Yaiba"),
            // por lo que no los bloqueamos por comparación literal de letras.
            val normGenres = item.allGenres.map { normalizeText(it) }

            val matchesQuickChip = when (selectedChipId) {
                "all" -> true
                "season" -> item.isAiring
                "latino" -> item.hasDub
                "movies" -> item.categoryName == "Película"
                else -> {
                    val targetChipGenre = normalizeText(selectedChipId)
                    normGenres.any { it == targetChipGenre || it.contains(targetChipGenre) }
                }
            }

            val matchesCategoryType = when (appliedCategoryType) {
                "Todos" -> true
                else -> item.categoryName.equals(appliedCategoryType, ignoreCase = true)
            }

            val matchesMultiGenres = if (appliedGenres.isEmpty()) {
                true
            } else {
                appliedGenres.all { reqGenre ->
                    val target = normalizeText(reqGenre)
                    normGenres.any { it == target || it.contains(target) }
                }
            }

            val matchesAudio = when (appliedAudioFormat) {
                "Doblaje Español" -> item.hasDub
                else -> true
            }

            val matchesYear = when (appliedYearRange) {
                "2026", "2025", "2024", "2023", "2022", "2021", "2020" ->
                    item.year == appliedYearRange.toInt()
                "Clásicos" -> item.year < 2020
                else -> true
            }

            matchesQuickChip && matchesCategoryType &&
                    matchesMultiGenres && matchesAudio && matchesYear
        }
    }

    // 6. Paginación infinita limpia (se detiene de inmediato si la página siguiente ya no trae nuevos items)
    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val reachedBottom = totalItems >= 12 && lastVisibleIndex >= totalItems - 4
            val needsMoreForFilter = hasCategoryOrModalFilter &&
                    debouncedSearch.isEmpty() &&
                    displayedItems.size < 8 &&
                    allLoadedItems.isNotEmpty() &&
                    allLoadedItems.all { it.isEnriched } &&
                    currentPage < 8

            reachedBottom || needsMoreForFilter
        }
    }

    LaunchedEffect(shouldLoadMore, displayedItems.size, allLoadedItems.count { it.isEnriched }) {
        if (shouldLoadMore && hasNextPage && !isLoadingMore && !isInitialLoading) {
            isLoadingMore = true
            val nextPage = currentPage + 1
            WakuRepository.fetchCatalogPage(
                context = context,
                page = nextPage,
                searchQuery = debouncedSearch,
                quickChip = selectedChipId,
                selectedCategoryType = appliedCategoryType,
                selectedGenres = appliedGenres,
                audioFormat = appliedAudioFormat,
                yearFilter = appliedYearRange
            ).onSuccess { pageData ->
                val existingIds = allLoadedItems.map { it.animeId }.toSet()
                val newUnique = pageData.items.filter { !existingIds.contains(it.animeId) }
                if (newUnique.isEmpty()) {
                    // Si el servidor ya no devolvió ningún anime nuevo, cerramos la paginación
                    hasNextPage = false
                } else {
                    allLoadedItems = allLoadedItems + newUnique
                    currentPage = nextPage
                    hasNextPage = pageData.hasNextPage
                }
            }.onFailure {
                hasNextPage = false
            }
            isLoadingMore = false
        }
    }

    val isFilteringInProgress = isInitialLoading ||
            (displayedItems.isEmpty() && (allLoadedItems.any { !it.isEnriched } || isLoadingMore))

    val activeFiltersCount = appliedGenres.size +
            (if (appliedCategoryType != "Todos") 1 else 0) +
            (if (appliedAudioFormat != "Todos") 1 else 0) +
            (if (appliedYearRange != "Todos") 1 else 0)

    val counterBadgeText = if (!hasAnyFilterActive) {
        "%,d+".format(totalCatalogCount).replace('.', ',')
    } else if (isFilteringInProgress) {
        "Buscando..."
    } else {
        "${displayedItems.size}${if (hasNextPage) "+" else ""}"
    }

    Scaffold(
        containerColor = WakuBg,
        topBar = {
            CatalogTopBar()
        },
        bottomBar = {
            WakuBottomNavBar(
                selectedRoute = "catalogo",
                avatarUrl = userAvatarUrl,
                onSelectRoute = { route ->
                    if (route != "catalogo") {
                        onNavigateTab(route)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(WakuBg)
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            // SECCIÓN SUPERIOR: Título + Buscador
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Catálogo",
                        color = WakuText,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFF2F2926))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = counterBadgeText,
                            color = WakuPink,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                CatalogSearchBar(
                    query = searchInput,
                    onQueryChange = { searchInput = it },
                    activeFiltersCount = activeFiltersCount,
                    focusRequester = searchFocusRequester,
                    onOpenFilterModal = {
                        focusManager.clearFocus()
                        showFilterModal = true
                    },
                    onSearchAction = { focusManager.clearFocus() }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Fila horizontal de Píldoras Rápidas
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filterChips, key = { it.id }) { chip ->
                    val isSelected = selectedChipId == chip.id
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .then(
                                if (isSelected) {
                                    Modifier.shadow(
                                        elevation = 10.dp,
                                        shape = CircleShape,
                                        ambientColor = WakuPrimary,
                                        spotColor = WakuPrimary
                                    )
                                } else Modifier
                            )
                            .clip(CircleShape)
                            .background(if (isSelected) WakuPrimary else WakuSurface)
                            .clickable {
                                selectedChipId = chip.id
                                if (chip.id == "movies") {
                                    appliedCategoryType = "Todos"
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        if (chip.hasGreenDot) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) WakuText else WakuGreen)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = chip.label,
                            color = if (isSelected) WakuText else Color(0xFFE4BEB7),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Chips de filtros avanzados activos
            if (activeFiltersCount > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (appliedCategoryType != "Todos") {
                        item {
                            ActiveFilterBadge(
                                text = "Tipo: $appliedCategoryType",
                                onRemove = { appliedCategoryType = "Todos" }
                            )
                        }
                    }
                    if (appliedAudioFormat != "Todos") {
                        item {
                            ActiveFilterBadge(
                                text = appliedAudioFormat,
                                onRemove = { appliedAudioFormat = "Todos" }
                            )
                        }
                    }
                    if (appliedYearRange != "Todos") {
                        item {
                            ActiveFilterBadge(
                                text = "Año: $appliedYearRange",
                                onRemove = { appliedYearRange = "Todos" }
                            )
                        }
                    }
                    items(appliedGenres.toList(), key = { it }) { g ->
                        ActiveFilterBadge(
                            text = g,
                            onRemove = { appliedGenres = appliedGenres - g }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // GRILLA DE 2 COLUMNAS
            if (isFilteringInProgress) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = innerPadding.calculateBottomPadding()),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = WakuPrimary, strokeWidth = 3.dp)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Buscando animes que coincidan...",
                            color = Color(0xFFE4BEB7),
                            fontSize = 13.sp
                        )
                    }
                }
            } else if (errorMessage != null && allLoadedItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = errorMessage!!,
                        color = WakuPink,
                        fontSize = 14.sp
                    )
                }
            } else if (displayedItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No se encontraron animes con esa combinación exacta.",
                            color = Color(0xFFE4BEB7),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Restablecer filtros",
                            color = WakuGold,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable {
                                appliedCategoryType = "Todos"
                                appliedGenres = emptySet()
                                appliedAudioFormat = "Todos"
                                appliedYearRange = "Todos"
                                selectedChipId = "all"
                                searchInput = ""
                            }
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = innerPadding.calculateBottomPadding() + 28.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(
                        items = displayedItems,
                        key = { it.animeId }
                    ) { anime ->
                        CatalogPosterCard(
                            anime = anime,
                            onClick = { onOpenAnimeDetail(anime.slug) }
                        )
                    }

                    // Spinner inferior SOLO cuando realmente está descargando una página nueva
                    if (isLoadingMore) {
                        item(span = { GridItemSpan(2) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = WakuPrimary,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }

                    // Pie de página "Fin de los resultados" cuando ya se mostraron todos los items
                    if (!isLoadingMore && !hasNextPage && displayedItems.isNotEmpty()) {
                        item(span = { GridItemSpan(2) }) {
                            EndOfCatalogFooter(totalShown = displayedItems.size)
                        }
                    }
                }
            }
        }

        // MODAL DE FILTROS ESPECÍFICOS
        if (showFilterModal) {
            ModalBottomSheet(
                onDismissRequest = { showFilterModal = false },
                containerColor = WakuSurface,
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .padding(top = 12.dp, bottom = 6.dp)
                            .width(48.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF5B403B))
                    )
                },
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            ) {
                CatalogFilterSheetContent(
                    availableGenres = availableOfficialGenres,
                    initialCategoryType = appliedCategoryType,
                    initialGenres = appliedGenres,
                    initialAudio = appliedAudioFormat,
                    initialYear = appliedYearRange,
                    onClose = { showFilterModal = false },
                    onReset = {
                        appliedCategoryType = "Todos"
                        appliedGenres = emptySet()
                        appliedAudioFormat = "Todos"
                        appliedYearRange = "Todos"
                        selectedChipId = "all"
                        showFilterModal = false
                    },
                    onApply = { newType, newGenres, newAudio, newYear ->
                        appliedCategoryType = newType
                        appliedGenres = newGenres
                        appliedAudioFormat = newAudio
                        appliedYearRange = newYear
                        if (newType != "Todos" && selectedChipId == "movies") {
                            selectedChipId = "all"
                        }
                        showFilterModal = false
                    }
                )
            }
        }
    }
}

/**
 * Pie de página elegante que indica el final de la lista o búsqueda
 */
@Composable
private fun EndOfCatalogFooter(totalShown: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(Color(0xFF3A3331))
            )
            Text(
                text = "✦",
                color = WakuGold.copy(alpha = 0.75f),
                fontSize = 12.sp
            )
            Text(
                text = "FIN DE LOS RESULTADOS",
                color = Color(0xFFE4BEB7).copy(alpha = 0.70f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp
            )
            Text(
                text = "✦",
                color = WakuGold.copy(alpha = 0.75f),
                fontSize = 12.sp
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(Color(0xFF3A3331))
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "$totalShown ${if (totalShown == 1) "título encontrado" else "títulos encontrados"}",
            color = Color(0xFFAA8983).copy(alpha = 0.65f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ActiveFilterBadge(
    text: String,
    onRemove: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color(0xFF2F2926))
            .border(1.dp, WakuGold.copy(alpha = 0.5f), CircleShape)
            .clickable { onRemove() }
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = WakuGold,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Outlined.Close,
            contentDescription = "Quitar filtro",
            tint = WakuGold,
            modifier = Modifier.size(13.dp)
        )
    }
}

@Composable
private fun CatalogTopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(WakuBg.copy(alpha = 0.92f))
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
}

@Composable
private fun CatalogSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    activeFiltersCount: Int,
    focusRequester: FocusRequester,
    onOpenFilterModal: () -> Unit,
    onSearchAction: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val borderColor = if (isFocused) WakuPrimary else Color.Transparent

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(color = WakuText, fontSize = 14.sp),
        cursorBrush = SolidColor(WakuPrimary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearchAction() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused },
        decorationBox = { innerTextField ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isFocused) Color(0xFF241E1C) else WakuSurface)
                    .border(2.dp, borderColor, RoundedCornerShape(14.dp))
                    .padding(start = 14.dp, end = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Buscar",
                    tint = WakuPink,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Buscar anime por título...",
                            color = Color(0xFFAA8983),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()
                }

                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "Limpiar búsqueda",
                            tint = Color(0xFFE4BEB7),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (activeFiltersCount > 0) WakuPrimary else Color(0xFF2F2926))
                        .clickable { onOpenFilterModal() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = "Filtros específicos",
                        tint = WakuText,
                        modifier = Modifier.size(20.dp)
                    )
                    if (activeFiltersCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 2.dp, y = (-2).dp)
                                .size(15.dp)
                                .clip(CircleShape)
                                .background(WakuGold),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = activeFiltersCount.toString(),
                                color = Color(0xFF241A00),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun CatalogPosterCard(
    anime: CatalogAnimeItem,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF241E1C))
        ) {
            AsyncImage(
                model = anime.posterUrl,
                contentDescription = anime.title,
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
                            colors = listOf(
                                Color(0xFF120D0B).copy(alpha = 0.80f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFF120D0B).copy(alpha = 0.85f)
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
                        text = anime.topLeftBadge,
                        color = WakuGold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xFF120D0B).copy(alpha = 0.82f))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = WakuGold,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = anime.scoreText,
                        color = WakuGold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = anime.title,
            color = WakuText,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (anime.isAiring) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(WakuGreen)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "En emisión • ${anime.primaryGenre}",
                    color = Color(0xFFE4BEB7),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Text(
                    text = "Finalizado • ${anime.primaryGenre}",
                    color = Color(0xFFAA8983),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogFilterSheetContent(
    availableGenres: List<String>,
    initialCategoryType: String,
    initialGenres: Set<String>,
    initialAudio: String,
    initialYear: String,
    onClose: () -> Unit,
    onReset: () -> Unit,
    onApply: (String, Set<String>, String, String) -> Unit
) {
    var draftCategoryType by remember { mutableStateOf(initialCategoryType) }
    var draftGenres by remember { mutableStateOf(initialGenres) }
    var draftAudio by remember { mutableStateOf(initialAudio) }
    var draftYear by remember { mutableStateOf(initialYear) }

    val categoryOptions = listOf("Todos", "TV Anime", "Película", "OVA", "Especial")
    val audioOptions = listOf("Todos", "Subtitulado (JP)", "Doblaje Español")
    val yearOptions = listOf("Todos", "2026", "2025", "2024", "2023", "2022", "2021", "2020", "Clásicos")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Filtros Específicos",
                    color = WakuText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Combina tipo, géneros, audio y año",
                    color = Color(0xFFE4BEB7),
                    fontSize = 12.sp
                )
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF241E1C))
                    .clickable { onClose() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Cerrar",
                    tint = Color(0xFFE4BEB7),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "TIPO DE ANIME",
            color = WakuGold,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categoryOptions.forEach { option ->
                val isSelected = draftCategoryType == option
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isSelected) WakuPrimary else Color(0xFF241E1C))
                        .clickable { draftCategoryType = option }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = option,
                        color = if (isSelected) WakuText else Color(0xFFE4BEB7),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "GÉNEROS (SELECCIÓN MÚLTIPLE)",
                color = WakuGold,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
            if (draftGenres.isNotEmpty()) {
                Text(
                    text = "${draftGenres.size} seleccionados",
                    color = WakuPink,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            availableGenres.forEach { genre ->
                val isSelected = draftGenres.contains(genre)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isSelected) WakuPrimary else Color(0xFF241E1C))
                        .border(
                            width = 1.dp,
                            color = if (isSelected) WakuPrimary else Color(0xFF3A3331),
                            shape = CircleShape
                        )
                        .clickable {
                            draftGenres = if (isSelected) draftGenres - genre else draftGenres + genre
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = WakuText,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = genre,
                        color = if (isSelected) WakuText else Color(0xFFE4BEB7),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        Text(
            text = "FORMATO DE AUDIO",
            color = WakuGold,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            audioOptions.forEach { option ->
                val isSelected = draftAudio == option
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isSelected) WakuPrimary else Color(0xFF241E1C))
                        .clickable { draftAudio = option }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = option,
                        color = if (isSelected) WakuText else Color(0xFFE4BEB7),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        Text(
            text = "AÑO DE ESTRENO",
            color = WakuGold,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            yearOptions.forEach { option ->
                val isSelected = draftYear == option
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (isSelected) WakuPrimary else Color(0xFF241E1C))
                        .clickable { draftYear = option }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = option,
                        color = if (isSelected) WakuText else Color(0xFFE4BEB7),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onReset,
                shape = CircleShape,
                modifier = Modifier
                    .weight(0.38f)
                    .height(48.dp)
            ) {
                Text(
                    text = "Limpiar",
                    color = Color(0xFFE4BEB7),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Button(
                onClick = { onApply(draftCategoryType, draftGenres, draftAudio, draftYear) },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WakuPrimary,
                    contentColor = WakuText
                ),
                modifier = Modifier
                    .weight(0.62f)
                    .height(48.dp)
                    .shadow(
                        elevation = 16.dp,
                        shape = CircleShape,
                        ambientColor = WakuPrimary,
                        spotColor = WakuPrimary
                    )
            ) {
                Text(
                    text = "Aplicar Filtros",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun normalizeText(input: String): String {
    return Normalizer.normalize(input.trim().lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
}