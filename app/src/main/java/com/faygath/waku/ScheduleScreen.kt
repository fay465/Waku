package com.faygath.waku

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Search
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

private data class WeekDayTab(
    val dayOfWeekValue: Int, // 1 = LUN .. 7 = DOM
    val shortName: String,
    val dayOfMonthNumber: String
)

@Composable
fun ScheduleScreen(
    onNavigateTab: (String) -> Unit,
    onOpenAnimeDetail: (String) -> Unit
) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val uid = auth.currentUser?.uid

    var userAvatarUrl by remember { mutableStateOf<String?>(null) }

    // Calcular los 7 días reales de la semana actual y seleccionar HOY por defecto
    val today = remember { LocalDate.now() }
    val currentDayOfWeek = remember { today.dayOfWeek.value } // 1..7
    var selectedDayOfWeek by remember { mutableIntStateOf(currentDayOfWeek) }

    val weekDays = remember(today) {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val labels = listOf("LUN", "MAR", "MIÉ", "JUE", "VIE", "SÁB", "DOM")
        (0..6).map { offset ->
            val date = monday.plusDays(offset.toLong())
            WeekDayTab(
                dayOfWeekValue = offset + 1,
                shortName = labels[offset],
                dayOfMonthNumber = date.dayOfMonth.toString()
            )
        }
    }

    // Obtener el GMT real del dispositivo (ej. "GMT-3" o "GMT-5")
    val gmtOffsetText = remember {
        val offsetSeconds = ZonedDateTime.now(ZoneId.systemDefault()).offset.totalSeconds
        val hours = offsetSeconds / 3600
        if (hours >= 0) "GMT+$hours" else "GMT$hours"
    }

    var allScheduleItems by remember { mutableStateOf<List<ScheduleAnimeItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 1. Escuchar avatar del usuario para la barra inferior
    LaunchedEffect(uid) {
        if (uid != null) {
            db.collection("usuarios").document(uid)
                .addSnapshotListener { snap, _ ->
                    userAvatarUrl = snap?.getString("avatarUrl")
                }
        }
    }

    // 2. Cargar el horario simulcast real desde AnimeAV1 + AniList
    LaunchedEffect(Unit) {
        isLoading = true
        errorMessage = null
        val res = WakuRepository.fetchWeeklySchedule(context)
        res.onSuccess { list ->
            allScheduleItems = list
            isLoading = false
        }.onFailure { err ->
            errorMessage = err.localizedMessage ?: "Error al sincronizar el horario"
            isLoading = false
        }
    }

    // Filtrar los animes correspondientes al día seleccionado
    val dayItems = remember(allScheduleItems, selectedDayOfWeek) {
        allScheduleItems.filter { it.dayOfWeek == selectedDayOfWeek }
    }

    Scaffold(
        containerColor = WakuBg,
        topBar = {
            ScheduleTopBar(onSearchClick = { onNavigateTab("catalogo_search") })
        },
        bottomBar = {
            WakuBottomNavBar(
                selectedRoute = "horario",
                avatarUrl = userAvatarUrl,
                onSelectRoute = { route ->
                    if (route != "horario") {
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
            // SECCIÓN CABECERA: "Horario" + Badges "Simulcast Semanal" y "GMT" + Subtítulo
            ScheduleHeaderSection(gmtText = gmtOffsetText)

            // BARRA SELECTORA DE DÍAS DE LA SEMANA (LUN .. DOM)
            WeeklyDaySelectorRow(
                days = weekDays,
                selectedDay = selectedDayOfWeek,
                onSelectDay = { selectedDayOfWeek = it }
            )

            Spacer(modifier = Modifier.height(6.dp))

            // GRILLA DE 2 COLUMNAS DE ESTRENOS DEL DÍA
            if (isLoading) {
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
                            text = "Sincronizando transmisiones desde Japón...",
                            color = Color(0xFFE4BEB7),
                            fontSize = 13.sp
                        )
                    }
                }
            } else if (errorMessage != null && allScheduleItems.isEmpty()) {
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
            } else if (dayItems.isEmpty()) {
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
                            fontSize = 24.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Sin transmisiones registradas para este día",
                            color = WakuText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
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
                        items = dayItems,
                        key = { "${it.animeId}_${it.dayOfWeek}" }
                    ) { item ->
                        SchedulePosterCard(
                            item = item,
                            onClick = { onOpenAnimeDetail(item.slug) }
                        )
                    }

                    // Pie de página sutil al final del día
                    item(span = { GridItemSpan(2) }) {
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
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "PROGRAMACIÓN SUJETA A EMISIÓN EN JAPÓN",
                                color = Color(0xFFE4BEB7).copy(alpha = 0.45f),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.4.sp
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
        }
    }
}

@Composable
private fun ScheduleTopBar(onSearchClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(WakuBg.copy(alpha = 0.92f))
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 18.dp),
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
                    tint = Color(0xFFE4BEB7),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        HorizontalDivider(color = Color(0xFF332622).copy(alpha = 0.45f), thickness = 1.dp)
    }
}

@Composable
private fun ScheduleHeaderSection(gmtText: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Horario",
                color = WakuText,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Badge "Simulcast Semanal" con punto carmesí
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xFF1E1614))
                        .border(1.dp, Color(0xFF332622), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(WakuPrimary)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "SIMULCAST SEMANAL",
                        color = WakuText,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }

                // Badge GMT dinámico
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0xFF1E1614).copy(alpha = 0.75f))
                        .border(1.dp, Color(0xFF332622).copy(alpha = 0.65f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AccessTime,
                        contentDescription = null,
                        tint = Color(0xFFE4BEB7),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = gmtText,
                        color = Color(0xFFE4BEB7),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Estrenos directos y transmisiones simultáneas desde Japón",
            color = Color(0xFFFFEFE0).copy(alpha = 0.65f),
            fontSize = 12.sp
        )
    }
}

/**
 * Barra horizontal de días (LUN 24 .. DOM 30) con el punto dorado brillante en el día activo
 */
@Composable
private fun WeeklyDaySelectorRow(
    days: List<WeekDayTab>,
    selectedDay: Int,
    onSelectDay: (Int) -> Unit
) {
    val rowState = rememberLazyListState()

    LaunchedEffect(selectedDay) {
        val index = (selectedDay - 1).coerceIn(0, 6)
        rowState.animateScrollToItem((index - 2).coerceAtLeast(0))
    }

    LazyRow(
        state = rowState,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(days, key = { it.dayOfWeekValue }) { day ->
            val isSelected = day.dayOfWeekValue == selectedDay
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) WakuPrimary else Color(0xFF1E1614),
                label = "dayBg"
            )

            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .widthIn(min = 50.dp)
                        .then(
                            if (isSelected) {
                                Modifier.shadow(
                                    elevation = 12.dp,
                                    shape = RoundedCornerShape(12.dp),
                                    ambientColor = WakuPrimary,
                                    spotColor = WakuPrimary
                                )
                            } else Modifier
                        )
                        .clip(RoundedCornerShape(12.dp))
                        .background(bgColor)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) WakuPrimary else Color(0xFF332622),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelectDay(day.dayOfWeekValue) }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = day.shortName,
                        color = if (isSelected) WakuText.copy(alpha = 0.92f) else Color(0xFFFFEFE0).copy(alpha = 0.65f),
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = day.dayOfMonthNumber,
                        color = WakuText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Punto dorado brillante debajo del día activo (fiel a code.html)
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .offset(y = 3.dp)
                            .size(6.dp)
                            .shadow(6.dp, CircleShape, ambientColor = WakuGold, spotColor = WakuGold)
                            .clip(CircleShape)
                            .background(WakuGold)
                    )
                }
            }
        }
    }
}

/**
 * Tarjeta 2:3 del Horario fiel a code.html y screen.jpg
 */
@Composable
private fun SchedulePosterCard(
    item: ScheduleAnimeItem,
    onClick: () -> Unit
) {
    val cardBorderColor = if (item.isFinale) {
        WakuGold.copy(alpha = 0.40f)
    } else {
        Color(0xFF332622)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1E1614))
                .border(1.dp, cardBorderColor, RoundedCornerShape(14.dp))
        ) {
            AsyncImage(
                model = item.posterUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Degradado doble (arriba y abajo) para legibilidad de las píldoras
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color.Black.copy(alpha = 0.50f),
                            0.45f to Color.Transparent,
                            1.0f to Color.Black.copy(alpha = 0.82f)
                        )
                    )
            )

            // BADGE SUPERIOR DERECHO (Hora / Estado de emisión)
            ScheduleTopRightBadge(
                statusType = item.statusType,
                text = item.statusBadgeText,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            )

            // BADGE INFERIOR IZQUIERDO (Ep. 6 / 12 o ★ FINAL • Ep. 12)
            if (item.isFinale) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(WakuGold)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "★ FINAL • Ep. ${item.episodeNumber}",
                        color = Color(0xFF16110F),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            } else {
                val epLabel = if (item.totalEpisodes != null && item.totalEpisodes > 0) {
                    "Ep. ${item.episodeNumber} / ${item.totalEpisodes}"
                } else {
                    "Ep. ${item.episodeNumber}"
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(WakuBg.copy(alpha = 0.82f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = epLabel,
                        color = WakuText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = item.title,
            color = WakuText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        val subText = if (item.isFinale) {
            "${item.categoryName} • Episodio ${item.episodeNumber} (Final)"
        } else {
            "${item.categoryName} • Episodio ${item.episodeNumber}"
        }

        Text(
            text = subText,
            color = Color(0xFFFFEFE0).copy(alpha = 0.65f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ScheduleTopRightBadge(
    statusType: ScheduleStatusType,
    text: String,
    modifier: Modifier = Modifier
) {
    when (statusType) {
        ScheduleStatusType.AIRED -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = modifier
                    .clip(CircleShape)
                    .background(WakuBg.copy(alpha = 0.85f))
                    .border(1.dp, WakuGreen.copy(alpha = 0.35f), CircleShape)
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
                    text = text,
                    color = WakuGreen,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        ScheduleStatusType.UPCOMING_SOON -> {
            Box(
                modifier = modifier
                    .clip(CircleShape)
                    .background(WakuBg.copy(alpha = 0.90f))
                    .border(1.dp, WakuGold.copy(alpha = 0.45f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = text,
                    color = WakuGold,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        ScheduleStatusType.UPCOMING_LATER -> {
            Box(
                modifier = modifier
                    .clip(CircleShape)
                    .background(WakuBg.copy(alpha = 0.85f))
                    .border(1.dp, Color(0xFF332622), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = text,
                    color = Color(0xFFFFEFE0).copy(alpha = 0.75f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        ScheduleStatusType.DELAYED -> {
            Box(
                modifier = modifier
                    .clip(CircleShape)
                    .background(WakuPink.copy(alpha = 0.92f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "⚠ Retrasado",
                    color = Color(0xFF781B1B),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}