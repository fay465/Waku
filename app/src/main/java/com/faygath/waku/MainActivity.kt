package com.faygath.waku

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.faygath.waku.ui.theme.WakuBg
import com.faygath.waku.ui.theme.WakuTheme
import com.google.firebase.auth.FirebaseAuth

class MainActivity : ComponentActivity() {

    private fun getBottomTabIndex(route: String?): Int = when {
        route == null -> -1
        route.startsWith("home") -> 0
        route.startsWith("catalogo") -> 1
        route.startsWith("mi_lista") -> 2
        route.startsWith("horario") -> 3
        route.startsWith("perfil") -> 4
        else -> -1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.parseColor("#120D0B"))
        )
        setContent {
            WakuTheme {
                val navController = rememberNavController()
                val startRoute = if (FirebaseAuth.getInstance().currentUser != null) "home" else "login"

                // Vigilante en tiempo real: verifica que este dispositivo siga siendo el único autorizado
                androidx.compose.runtime.DisposableEffect(Unit) {
                    val auth = FirebaseAuth.getInstance()
                    val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    val myDeviceId = getUniqueDeviceId(this@MainActivity)
                    var firestoreListener: com.google.firebase.firestore.ListenerRegistration? = null

                    val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
                        firestoreListener?.remove()
                        val user = firebaseAuth.currentUser
                        if (user != null) {
                            firestoreListener = db.collection("usuarios").document(user.uid)
                                .addSnapshotListener { snap, _ ->
                                    if (snap != null && snap.exists()) {
                                        val boundId = snap.getString("boundDeviceId")
                                        val isBlocked = snap.getBoolean("bloqueado") == true

                                        if (isBlocked || (!boundId.isNullOrBlank() && boundId != myDeviceId)) {
                                            firebaseAuth.signOut()
                                            android.widget.Toast.makeText(
                                                this@MainActivity,
                                                if (isBlocked) "Tu cuenta fue suspendida."
                                                else "Sesión cerrada: esta cuenta está vinculada a otro dispositivo.",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                            navController.navigate("login") {
                                                popUpTo(0) { inclusive = true }
                                            }
                                        }
                                    }
                                }
                        }
                    }

                    auth.addAuthStateListener(authListener)
                    onDispose {
                        firestoreListener?.remove()
                        auth.removeAuthStateListener(authListener)
                    }
                }

                val navigateBottomTab: (String) -> Unit = { route ->
                    when (route) {
                        "catalogo_search" -> {
                            navController.navigate("catalogo?focusSearch=true") {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                            }
                        }
                        "catalogo" -> {
                            navController.navigate("catalogo?focusSearch=false") {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        else -> {
                            val target = if (route == "inicio") "home" else route
                            if (target in listOf("home", "mi_lista", "horario", "perfil")) {
                                navController.navigate(target) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = startRoute,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(WakuBg),
                    enterTransition = {
                        val fromIdx = getBottomTabIndex(initialState.destination.route)
                        val toIdx = getBottomTabIndex(targetState.destination.route)
                        when {
                            fromIdx != -1 && toIdx != -1 && fromIdx != toIdx -> {
                                val direction = if (toIdx > fromIdx) 1 else -1
                                slideInHorizontally(
                                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                                    initialOffsetX = { fullWidth -> direction * fullWidth }
                                )
                            }
                            targetState.destination.route == "login" -> {
                                fadeIn(animationSpec = tween(durationMillis = 520, delayMillis = 160))
                            }
                            else -> {
                                fadeIn(tween(260)) + scaleIn(initialScale = 0.94f, animationSpec = tween(260))
                            }
                        }
                    },
                    exitTransition = {
                        val fromIdx = getBottomTabIndex(initialState.destination.route)
                        val toIdx = getBottomTabIndex(targetState.destination.route)
                        when {
                            fromIdx != -1 && toIdx != -1 && fromIdx != toIdx -> {
                                val direction = if (toIdx > fromIdx) -1 else 1
                                slideOutHorizontally(
                                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                                    targetOffsetX = { fullWidth -> direction * fullWidth }
                                )
                            }
                            targetState.destination.route == "login" -> {
                                fadeOut(animationSpec = tween(durationMillis = 420))
                            }
                            else -> {
                                fadeOut(tween(220))
                            }
                        }
                    },
                    popEnterTransition = {
                        fadeIn(animationSpec = tween(240))
                    },
                    popExitTransition = {
                        fadeOut(animationSpec = tween(220))
                    }
                ) {
                    composable("login") {
                        LoginScreen(
                            onLoginSuccess = {
                                navController.navigate("home") {
                                    popUpTo("login") { inclusive = true }
                                }
                            },
                            onForgotPasswordClick = {
                                navController.navigate("forgot_password")
                            }
                        )
                    }

                    composable("forgot_password") {
                        ForgotPasswordScreen(
                            onBackToLogin = {
                                navController.popBackStack()
                            }
                        )
                    }

                    composable("home") {
                        HomeScreen(
                            onNavigateTab = navigateBottomTab,
                            onOpenAnimeDetail = { slug ->
                                if (slug.isNotBlank()) {
                                    navController.navigate("anime_detail/${Uri.encode(slug)}")
                                }
                            },
                            onPlayEpisode = { slug, episodeNumber ->
                                if (slug.isNotBlank()) {
                                    navController.navigate("player/${Uri.encode(slug)}/$episodeNumber")
                                }
                            }
                        )
                    }

                    composable(
                        route = "catalogo?focusSearch={focusSearch}",
                        arguments = listOf(
                            navArgument("focusSearch") {
                                type = NavType.BoolType
                                defaultValue = false
                            }
                        )
                    ) { backStackEntry ->
                        val focusSearch = backStackEntry.arguments?.getBoolean("focusSearch") ?: false
                        CatalogScreen(
                            autoFocusSearch = focusSearch,
                            onNavigateTab = navigateBottomTab,
                            onOpenAnimeDetail = { slug ->
                                if (slug.isNotBlank()) {
                                    navController.navigate("anime_detail/${Uri.encode(slug)}")
                                }
                            }
                        )
                    }

                    composable("mi_lista") {
                        MyListScreen(
                            onNavigateTab = navigateBottomTab,
                            onOpenAnimeDetail = { slug ->
                                if (slug.isNotBlank()) {
                                    navController.navigate("anime_detail/${Uri.encode(slug)}")
                                }
                            }
                        )
                    }

                    composable("horario") {
                        ScheduleScreen(
                            onNavigateTab = navigateBottomTab,
                            onOpenAnimeDetail = { slug ->
                                if (slug.isNotBlank()) {
                                    navController.navigate("anime_detail/${Uri.encode(slug)}")
                                }
                            }
                        )
                    }

                    composable("perfil") {
                        ProfileScreen(
                            onNavigateTab = navigateBottomTab,
                            onLogoutSuccess = {
                                navController.navigate("login") {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        )
                    }

                    composable(
                        route = "anime_detail/{slug}",
                        arguments = listOf(navArgument("slug") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val slug = backStackEntry.arguments?.getString("slug").orEmpty()
                        AnimeDetailScreen(
                            initialSlug = slug,
                            onBackClick = { navController.popBackStack() },
                            onPlayEpisode = { detail, ep ->
                                if (detail.slug.isNotBlank()) {
                                    navController.navigate("player/${Uri.encode(detail.slug)}/${ep.number}")
                                }
                            }
                        )
                    }

                    composable(
                        route = "player/{slug}/{episodeNumber}",
                        arguments = listOf(
                            navArgument("slug") { type = NavType.StringType },
                            navArgument("episodeNumber") { type = NavType.IntType }
                        )
                    ) { backStackEntry ->
                        val slug = backStackEntry.arguments?.getString("slug").orEmpty()
                        val epNum = backStackEntry.arguments?.getInt("episodeNumber") ?: 1
                        VideoPlayerScreen(
                            slug = slug,
                            initialEpisodeNumber = epNum,
                            onBackClick = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}