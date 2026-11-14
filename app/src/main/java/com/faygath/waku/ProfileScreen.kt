package com.faygath.waku

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.faygath.waku.ui.theme.*
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

// Encuadre enfocado en el tercio superior para que los retratos verticales de AniList encuadren siempre la cara
val FaceTopAlignment = BiasAlignment(horizontalBias = 0f, verticalBias = -0.60f)

// Avatar oficial por defecto (Anya Forger desde AniList CDN) si el usuario aún no ha elegido uno
private const val DEFAULT_ANILIST_AVATAR =
    "https://s4.anilist.co/file/anilistcdn/character/large/b138101-768222I9331P.png"

private enum class UsernameAvailability {
    TOO_SHORT,
    CHECKING,
    AVAILABLE,
    TAKEN
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    isMandatorySetup: Boolean = false,
    onNavigateTab: (String) -> Unit,
    onLogoutSuccess: () -> Unit,
    onSetupCompleted: () -> Unit = {}
) {
    // Si está en modo configuración obligatoria de primer ingreso, bloquea el botón físico Atrás
    BackHandler(enabled = isMandatorySetup) {}

    val focusManager = LocalFocusManager.current
    val usernameFocusRequester = remember { FocusRequester() }

    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val currentUser = auth.currentUser
    val uid = currentUser?.uid

    val userEmail = currentUser?.email ?: "usuario@waku.app"

    var memberSinceRaw by remember {
        val creationTs = currentUser?.metadata?.creationTimestamp ?: 0L
        val initialMonthYear = if (creationTs > 0L) {
            val zdt = Instant.ofEpochMilli(creationTs).atZone(ZoneId.systemDefault())
            val monthEs = zdt.month.getDisplayName(JavaTextStyle.FULL, Locale("es", "ES"))
                .replaceFirstChar { it.uppercase() }
            "$monthEs ${zdt.year}"
        } else {
            getCurrentMonthYearSpanish()
        }
        mutableStateOf(initialMonthYear)
    }

    val memberSinceText = "Miembro desde $memberSinceRaw"

    var selectedAvatarUrl by remember { mutableStateOf(DEFAULT_ANILIST_AVATAR) }
    var displayedUsername by remember {
        val initialName = if (isMandatorySetup) {
            ""
        } else {
            currentUser?.displayName?.takeIf { it.isNotBlank() }
                ?: userEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
        }
        mutableStateOf(initialName)
    }

    // TextFieldValue mantiene el cursor siempre al final del texto para evitar escribir al principio
    var usernameField by remember {
        mutableStateOf(
            TextFieldValue(
                text = displayedUsername,
                selection = TextRange(displayedUsername.length)
            )
        )
    }
    var isUsernameFocused by remember { mutableStateOf(false) }
    var usernameAvailability by remember {
        mutableStateOf(
            if (displayedUsername.length >= 3) UsernameAvailability.AVAILABLE
            else UsernameAvailability.TOO_SHORT
        )
    }

    // Estado para abrir el Modal Selector de Avatares por Serie
    var showAvatarPickerSheet by remember { mutableStateOf(false) }

    // Estados de contraseña
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var requireCurrentPwdForSetup by remember { mutableStateOf(false) }

    var showCurrentPwd by remember { mutableStateOf(false) }
    var showNewPwd by remember { mutableStateOf(false) }
    var showConfirmPwd by remember { mutableStateOf(false) }

    var isSaving by remember { mutableStateOf(false) }
    var avatarPopTrigger by remember { mutableStateOf(false) }

    var noticeMessage by remember { mutableStateOf<String?>(null) }
    var isErrorNotice by remember { mutableStateOf(false) }

    LaunchedEffect(noticeMessage) {
        if (noticeMessage != null) {
            delay(3200)
            noticeMessage = null
        }
    }

    LaunchedEffect(avatarPopTrigger) {
        if (avatarPopTrigger) {
            delay(180)
            avatarPopTrigger = false
        }
    }

    val avatarScale by animateFloatAsState(
        targetValue = if (avatarPopTrigger) 1.07f else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "avatarPop"
    )

    // 1. Cargar datos actuales del usuario desde Firestore
    LaunchedEffect(uid) {
        if (uid != null) {
            val userRef = db.collection("usuarios").document(uid)
            userRef.get()
                .addOnSuccessListener { doc ->
                    if (doc != null && doc.exists()) {
                        doc.getString("avatarUrl")?.takeIf { it.isNotBlank() }?.let {
                            selectedAvatarUrl = it
                        }
                        val savedName = doc.getString("username")
                            ?: doc.getString("nombreUsuario")
                            ?: doc.getString("nombre")
                        if (!savedName.isNullOrBlank() && !isMandatorySetup) {
                            displayedUsername = savedName
                            if (!isUsernameFocused) {
                                usernameField = TextFieldValue(
                                    text = savedName,
                                    selection = TextRange(savedName.length)
                                )
                            }
                        }
                        val savedMemberSince = doc.getString("memberSince")?.trim()
                        if (!savedMemberSince.isNullOrBlank()) {
                            memberSinceRaw = savedMemberSince
                        }

                        if (!isMandatorySetup) {
                            val missingFixes = mutableMapOf<String, Any>()
                            if (doc.getString("email").isNullOrBlank() && userEmail.isNotBlank()) {
                                missingFixes["email"] = userEmail
                            }
                            if (savedName.isNullOrBlank() && displayedUsername.isNotBlank()) {
                                missingFixes["username"] = displayedUsername
                            }
                            if (savedMemberSince.isNullOrBlank()) {
                                missingFixes["memberSince"] = memberSinceRaw
                            }
                            if (missingFixes.isNotEmpty()) {
                                missingFixes["updatedAt"] = System.currentTimeMillis()
                                userRef.set(missingFixes, SetOptions.merge())
                            }
                        }
                    }
                }
        }
    }

    // 2. Verificación en tiempo real (con debounce) de que el nombre de usuario sea único en Firestore
    LaunchedEffect(usernameField.text, displayedUsername, uid) {
        val clean = usernameField.text.trim()
        if (clean.length < 3) {
            usernameAvailability = UsernameAvailability.TOO_SHORT
            return@LaunchedEffect
        }
        if (!isMandatorySetup && clean.equals(displayedUsername, ignoreCase = true)) {
            usernameAvailability = UsernameAvailability.AVAILABLE
            return@LaunchedEffect
        }

        usernameAvailability = UsernameAvailability.CHECKING
        delay(350)

        val candidates = listOf(
            clean,
            clean.lowercase(),
            clean.uppercase(),
            clean.replaceFirstChar { it.uppercase() },
            clean.lowercase().replaceFirstChar { it.uppercase() }
        ).distinct()

        val isTaken = runCatching {
            val snap = db.collection("usuarios")
                .whereIn("username", candidates)
                .get()
                .await()
            snap.documents.any { doc ->
                doc.id != uid && doc.getString("username")?.trim().equals(clean, ignoreCase = true)
            }
        }.getOrDefault(false)

        usernameAvailability = if (isTaken) {
            UsernameAvailability.TAKEN
        } else {
            UsernameAvailability.AVAILABLE
        }
    }

    val hasValidNewPassword = remember(newPassword) {
        newPassword.length >= 6
    }

    Scaffold(
        containerColor = WakuBg,
        topBar = {
            ProfileTopBar(
                showSearchIcon = !isMandatorySetup,
                onSearchClick = { onNavigateTab("catalogo_search") }
            )
        },
        bottomBar = {
            if (!isMandatorySetup) {
                WakuBottomNavBar(
                    selectedRoute = "perfil",
                    avatarUrl = selectedAvatarUrl,
                    onSelectRoute = { route ->
                        if (route != "perfil") {
                            onNavigateTab(route)
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
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 6.dp,
                        bottom = innerPadding.calculateBottomPadding() + 32.dp
                    )
            ) {
                // 1. CABECERA
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (isMandatorySetup) "Configura tu Cuenta" else "Mi Perfil",
                        color = WakuText,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .shadow(8.dp, CircleShape, ambientColor = WakuPrimary, spotColor = WakuPrimary)
                            .clip(CircleShape)
                            .background(WakuPrimary)
                    )
                }

                if (isMandatorySetup) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF241E1C))
                            .border(1.dp, WakuGold.copy(alpha = 0.40f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "✦",
                            color = WakuGold,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "¡Bienvenido a Waku! Como es tu primer ingreso, elige un nombre de usuario único, tu avatar y define tu contraseña personal.",
                            color = Color(0xFFE4BEB7),
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // 2. AVATAR CENTRAL CON BORDE DORADO Y BOTÓN DE LÁPIZ
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            WakuGold.copy(alpha = 0.25f),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = CircleShape
                                )
                        )

                        Box(
                            modifier = Modifier
                                .size(102.dp)
                                .scale(avatarScale)
                                .shadow(
                                    elevation = 20.dp,
                                    shape = CircleShape,
                                    ambientColor = WakuGold,
                                    spotColor = WakuGold
                                )
                                .clip(CircleShape)
                                .background(WakuGold)
                                .clickable {
                                    focusManager.clearFocus()
                                    showAvatarPickerSheet = true
                                }
                                .padding(2.5.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF201A18))
                        ) {
                            AsyncImage(
                                model = selectedAvatarUrl,
                                contentDescription = "Avatar de perfil",
                                contentScale = ContentScale.Crop,
                                alignment = FaceTopAlignment,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = (-2).dp, y = (-2).dp)
                                .size(34.dp)
                                .shadow(10.dp, CircleShape, ambientColor = WakuPrimary, spotColor = WakuPrimary)
                                .clip(CircleShape)
                                .background(WakuPrimary)
                                .border(2.dp, WakuBg, CircleShape)
                                .clickable {
                                    focusManager.clearFocus()
                                    showAvatarPickerSheet = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "Elegir avatar",
                                tint = WakuText,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = displayedUsername.ifBlank {
                                usernameField.text.trim().ifBlank { "Nuevo Miembro" }
                            },
                            color = WakuText,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = Icons.Filled.Verified,
                            contentDescription = "Verificado",
                            tint = WakuPink,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = memberSinceText,
                        color = Color(0xFFE4BEB7).copy(alpha = 0.80f),
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(26.dp))

                // 3. SECCIÓN: DATOS DE LA CUENTA
                ProfileSectionHeader(
                    icon = Icons.Outlined.Badge,
                    title = "DATOS DE LA CUENTA",
                    rightStatus = if (isMandatorySetup) "PASO 1 DE 2" else "SINCRONIZADO"
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Correo Electrónico",
                    color = Color(0xFFE4BEB7).copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF120D0B))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Mail,
                            contentDescription = null,
                            tint = Color(0xFFAA8983),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = userEmail,
                            color = Color(0xFFAA8983),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = "PROTEGIDO",
                            color = Color(0xFFAA8983).copy(alpha = 0.80f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = "Protegido",
                            tint = Color(0xFFAA8983),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val statusTextAndColor = when (usernameAvailability) {
                    UsernameAvailability.TOO_SHORT -> "MÍN. 3 LETRAS" to WakuGold
                    UsernameAvailability.CHECKING -> "VERIFICANDO..." to WakuGold
                    UsernameAvailability.AVAILABLE -> "✓ DISPONIBLE" to WakuGreen
                    UsernameAvailability.TAKEN -> "✕ YA EN USO" to WakuPink
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Nombre de Usuario",
                        color = Color(0xFFE4BEB7),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = statusTextAndColor.first,
                        color = statusTextAndColor.second,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))

                val usernameBorderColor = when {
                    usernameAvailability == UsernameAvailability.TAKEN -> WakuPrimary
                    isUsernameFocused -> WakuPrimary
                    else -> Color.Transparent
                }

                BasicTextField(
                    value = usernameField,
                    onValueChange = { usernameField = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = WakuText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    cursorBrush = SolidColor(WakuPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(usernameFocusRequester)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused && !isUsernameFocused) {
                                // Si al tocar el campo el cursor cayó en 0, lo mueve automáticamente al final
                                if (usernameField.selection.start == 0 &&
                                    usernameField.selection.end == 0 &&
                                    usernameField.text.isNotEmpty()
                                ) {
                                    usernameField = usernameField.copy(
                                        selection = TextRange(usernameField.text.length)
                                    )
                                }
                            }
                            isUsernameFocused = focusState.isFocused
                        },
                    decorationBox = { innerField ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isUsernameFocused) Color(0xFF2F2926) else Color(0xFF241E1C))
                                .border(
                                    width = 1.dp,
                                    color = usernameBorderColor,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Badge,
                                contentDescription = null,
                                tint = WakuPink,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                if (usernameField.text.isEmpty()) {
                                    Text(
                                        text = "Elige tu nombre de usuario único",
                                        color = Color(0xFFAA8983),
                                        fontSize = 15.sp
                                    )
                                }
                                innerField()
                            }
                            IconButton(
                                onClick = {
                                    usernameField = usernameField.copy(
                                        selection = TextRange(usernameField.text.length)
                                    )
                                    usernameFocusRequester.requestFocus()
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = "Editar nombre",
                                    tint = WakuGold,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 4. SECCIÓN: CAMBIAR CONTRASEÑA
                ProfileSectionHeader(
                    icon = Icons.Outlined.LockReset,
                    title = if (isMandatorySetup) "CREA TU CONTRASEÑA PERSONAL" else "CAMBIAR CONTRASEÑA",
                    rightStatus = if (isMandatorySetup) "PASO 2 DE 2 • OBLIGATORIO" else "SEGURIDAD SSL"
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (!isMandatorySetup || requireCurrentPwdForSetup) {
                    ProfilePasswordField(
                        label = if (isMandatorySetup) "Contraseña Temporal Actual" else "Contraseña Actual",
                        value = currentPassword,
                        onValueChange = { currentPassword = it },
                        placeholder = "••••••••••••",
                        leadingIcon = Icons.Outlined.VpnKey,
                        isVisible = showCurrentPwd,
                        onToggleVisibility = { showCurrentPwd = !showCurrentPwd }
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                }

                ProfilePasswordField(
                    label = "Nueva Contraseña",
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    placeholder = "Mínimo 6 caracteres",
                    leadingIcon = Icons.Outlined.Password,
                    isVisible = showNewPwd,
                    onToggleVisibility = { showNewPwd = !showNewPwd }
                )

                Spacer(modifier = Modifier.height(10.dp))

                ProfilePasswordField(
                    label = "Confirmar Contraseña",
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    placeholder = "Repetir nueva contraseña",
                    leadingIcon = Icons.Outlined.VerifiedUser,
                    isVisible = showConfirmPwd,
                    onToggleVisibility = { showConfirmPwd = !showConfirmPwd }
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = if (newPassword.isEmpty() || hasValidNewPassword) WakuGreen else WakuGold,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "La nueva contraseña debe tener al menos 6 caracteres",
                        color = Color(0xFFE4BEB7).copy(alpha = 0.72f),
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(26.dp))

                // 5. BOTÓN DE ACCIÓN PRINCIPAL
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        val cleanName = usernameField.text.trim()
                        if (cleanName.length < 3) {
                            isErrorNotice = true
                            noticeMessage = "El nombre de usuario debe tener al menos 3 caracteres"
                            return@Button
                        }
                        if (usernameAvailability == UsernameAvailability.TAKEN) {
                            isErrorNotice = true
                            noticeMessage = "El nombre '$cleanName' ya está ocupado. Elige otro distinto."
                            return@Button
                        }

                        val wantsPasswordChange = isMandatorySetup ||
                                currentPassword.isNotBlank() ||
                                newPassword.isNotBlank() ||
                                confirmPassword.isNotBlank()

                        if (wantsPasswordChange) {
                            if ((!isMandatorySetup || requireCurrentPwdForSetup) && currentPassword.isBlank()) {
                                isErrorNotice = true
                                noticeMessage = "Ingresa tu contraseña actual para poder cambiarla"
                                return@Button
                            }
                            if (!hasValidNewPassword) {
                                isErrorNotice = true
                                noticeMessage = "Debes definir una nueva contraseña de mínimo 6 caracteres"
                                return@Button
                            }
                            if (newPassword != confirmPassword) {
                                isErrorNotice = true
                                noticeMessage = "La nueva contraseña y su confirmación no coinciden"
                                return@Button
                            }
                        }

                        if (uid == null || currentUser == null) return@Button
                        isSaving = true

                        val proceedWithSave = {
                            val profileData = mapOf(
                                "username" to cleanName,
                                "avatarUrl" to selectedAvatarUrl,
                                "email" to userEmail,
                                "memberSince" to memberSinceRaw,
                                "mustCompleteSetup" to false,
                                "updatedAt" to System.currentTimeMillis()
                            )

                            val profileUpdates = UserProfileChangeRequest.Builder()
                                .setDisplayName(cleanName)
                                .build()
                            currentUser.updateProfile(profileUpdates)
                            displayedUsername = cleanName

                            if (wantsPasswordChange) {
                                val applyNewPassword = {
                                    currentUser.updatePassword(newPassword)
                                        .addOnSuccessListener {
                                            db.collection("usuarios").document(uid)
                                                .set(profileData, SetOptions.merge())
                                                .addOnCompleteListener {
                                                    isSaving = false
                                                    currentPassword = ""
                                                    newPassword = ""
                                                    confirmPassword = ""
                                                    isErrorNotice = false
                                                    if (isMandatorySetup) {
                                                        onSetupCompleted()
                                                    } else {
                                                        noticeMessage = "Perfil y contraseña actualizados con éxito"
                                                    }
                                                }
                                        }
                                        .addOnFailureListener { err ->
                                            isSaving = false
                                            isErrorNotice = true
                                            if (err is FirebaseAuthRecentLoginRequiredException) {
                                                requireCurrentPwdForSetup = true
                                                noticeMessage = "Por seguridad, ingresa tu contraseña temporal actual arriba."
                                            } else {
                                                noticeMessage = err.localizedMessage ?: "Error al actualizar la contraseña"
                                            }
                                        }
                                }

                                if (currentPassword.isNotBlank() && currentUser.email != null) {
                                    val credential = EmailAuthProvider.getCredential(currentUser.email!!, currentPassword)
                                    currentUser.reauthenticate(credential)
                                        .addOnSuccessListener { applyNewPassword() }
                                        .addOnFailureListener {
                                            isSaving = false
                                            isErrorNotice = true
                                            noticeMessage = "La contraseña actual ingresada es incorrecta"
                                        }
                                } else {
                                    applyNewPassword()
                                }
                            } else {
                                db.collection("usuarios").document(uid)
                                    .set(profileData, SetOptions.merge())
                                    .addOnCompleteListener {
                                        isSaving = false
                                        isErrorNotice = false
                                        noticeMessage = "Perfil actualizado con éxito"
                                    }
                            }
                        }

                        // Comprobación final contra Firestore antes de escribir para garantizar unicidad absoluta
                        val candidates = listOf(
                            cleanName,
                            cleanName.lowercase(),
                            cleanName.uppercase(),
                            cleanName.replaceFirstChar { it.uppercase() },
                            cleanName.lowercase().replaceFirstChar { it.uppercase() }
                        ).distinct()

                        db.collection("usuarios")
                            .whereIn("username", candidates)
                            .get()
                            .addOnSuccessListener { snap ->
                                val takenByOther = snap.documents.any { doc ->
                                    doc.id != uid && doc.getString("username")?.trim().equals(cleanName, ignoreCase = true)
                                }
                                if (takenByOther) {
                                    isSaving = false
                                    usernameAvailability = UsernameAvailability.TAKEN
                                    isErrorNotice = true
                                    noticeMessage = "El nombre '$cleanName' ya está en uso por otro miembro"
                                } else {
                                    proceedWithSave()
                                }
                            }
                            .addOnFailureListener {
                                proceedWithSave()
                            }
                    },
                    enabled = !isSaving && usernameAvailability != UsernameAvailability.CHECKING,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WakuPrimary,
                        contentColor = WakuText
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .shadow(
                            elevation = 20.dp,
                            shape = CircleShape,
                            ambientColor = WakuPrimary,
                            spotColor = WakuPrimary
                        )
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            color = WakuText,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isMandatorySetup) "Completar Registro y Entrar" else "Guardar Cambios",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .clickable {
                            auth.signOut()
                            onLogoutSuccess()
                        }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Logout,
                        contentDescription = null,
                        tint = WakuPink,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Cerrar Sesión",
                        color = WakuPink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.6.sp
                    )
                }
            }

            // Notificación flotante de confirmación / error
            AnimatedVisibility(
                visible = noticeMessage != null,
                enter = fadeIn(tween(200)) + slideInVertically { it / 2 },
                exit = fadeOut(tween(250)) + slideOutVertically { it / 2 },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 20.dp,
                        end = 20.dp,
                        bottom = innerPadding.calculateBottomPadding() + 16.dp
                    )
            ) {
                Row(
                    modifier = Modifier
                        .shadow(18.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color(0xFF2F2926))
                        .border(
                            width = 1.dp,
                            color = if (isErrorNotice) WakuPrimary else WakuGreen.copy(alpha = 0.45f),
                            shape = CircleShape
                        )
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isErrorNotice) Icons.Outlined.Info else Icons.Outlined.Check,
                        contentDescription = null,
                        tint = if (isErrorNotice) WakuPink else WakuGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = noticeMessage.orEmpty(),
                        color = WakuText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // MODAL SELECTOR DE AVATARES POR SERIE (Con buscador en vivo conectado a AniList)
        if (showAvatarPickerSheet) {
            ModalBottomSheet(
                onDismissRequest = { showAvatarPickerSheet = false },
                containerColor = Color(0xFF1A1412),
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
                AvatarPickerSheetContent(
                    currentAvatarUrl = selectedAvatarUrl,
                    onSelectAvatar = { charItem ->
                        selectedAvatarUrl = charItem.imageUrl
                        avatarPopTrigger = true
                        showAvatarPickerSheet = false
                        if (uid != null && !isMandatorySetup) {
                            db.collection("usuarios").document(uid)
                                .set(
                                    mapOf(
                                        "avatarUrl" to charItem.imageUrl,
                                        "username" to displayedUsername,
                                        "email" to userEmail,
                                        "memberSince" to memberSinceRaw,
                                        "updatedAt" to System.currentTimeMillis()
                                    ),
                                    SetOptions.merge()
                                )
                        }
                        isErrorNotice = false
                        noticeMessage = "Avatar de ${charItem.name} seleccionado"
                    },
                    onClose = { showAvatarPickerSheet = false }
                )
            }
        }
    }
}

@Composable
private fun AvatarPickerSheetContent(
    currentAvatarUrl: String,
    onSelectAvatar: (CharacterAvatarItem) -> Unit,
    onClose: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    var searchQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }

    var seriesGroups by remember { mutableStateOf<List<SeriesAvatarGroup>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(searchQuery) {
        delay(400)
        debouncedQuery = searchQuery.trim()
    }

    LaunchedEffect(debouncedQuery) {
        isLoading = true
        errorMessage = null
        val res = WakuRepository.fetchCharacterAvatarsBySeries(debouncedQuery)
        res.onSuccess { list ->
            seriesGroups = list
            isLoading = false
        }.onFailure { err ->
            seriesGroups = emptyList()
            errorMessage = err.localizedMessage ?: "No se pudieron cargar los avatares"
            isLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.86f)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Elegir Avatar",
                    color = WakuText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Selecciona un personaje para tu perfil",
                    color = Color(0xFFE4BEB7).copy(alpha = 0.75f),
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

        var isSearchFocused by remember { mutableStateOf(false) }
        BasicTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            singleLine = true,
            textStyle = TextStyle(color = WakuText, fontSize = 14.sp),
            cursorBrush = SolidColor(WakuPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isSearchFocused = it.isFocused },
            decorationBox = { innerTextField ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF241E1C))
                        .border(
                            width = 1.5.dp,
                            color = if (isSearchFocused) WakuPrimary else Color(0xFF3A3331),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = WakuGold,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Buscar anime o personaje...",
                                color = Color(0xFFAA8983),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Limpiar",
                                tint = Color(0xFFE4BEB7),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = WakuPrimary, strokeWidth = 3.dp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Buscando personajes...",
                        color = Color(0xFFE4BEB7),
                        fontSize = 13.sp
                    )
                }
            }
        } else if (errorMessage != null && seriesGroups.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = errorMessage!!,
                    color = WakuPink,
                    fontSize = 14.sp
                )
            }
        } else if (seriesGroups.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No se encontraron personajes para \"$searchQuery\"",
                    color = Color(0xFFE4BEB7),
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                items(seriesGroups, key = { it.animeId }) { group ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = group.seriesTitle,
                                color = WakuGold,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(1.dp)
                                    .background(Color(0xFF3A3331))
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        val rows = group.characters.chunked(3)
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            rows.forEach { rowChars ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceAround
                                ) {
                                    rowChars.forEach { character ->
                                        val isCurrent = character.imageUrl == currentAvatarUrl
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .width(92.dp)
                                                .clickable { onSelectAvatar(character) }
                                        ) {
                                            Box(contentAlignment = Alignment.BottomEnd) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(74.dp)
                                                        .then(
                                                            if (isCurrent) {
                                                                Modifier.shadow(
                                                                    elevation = 12.dp,
                                                                    shape = CircleShape,
                                                                    ambientColor = WakuGold,
                                                                    spotColor = WakuGold
                                                                )
                                                            } else Modifier
                                                        )
                                                        .clip(CircleShape)
                                                        .background(
                                                            if (isCurrent) WakuGold else Color(0xFF3A3331)
                                                        )
                                                        .padding(if (isCurrent) 2.5.dp else 1.5.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0xFF241E1C))
                                                ) {
                                                    AsyncImage(
                                                        model = character.imageUrl,
                                                        contentDescription = character.name,
                                                        contentScale = ContentScale.Crop,
                                                        alignment = FaceTopAlignment,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                }

                                                if (isCurrent) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(20.dp)
                                                            .clip(CircleShape)
                                                            .background(WakuPrimary)
                                                            .border(1.5.dp, Color(0xFF1A1412), CircleShape),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Filled.Check,
                                                            contentDescription = null,
                                                            tint = WakuText,
                                                            modifier = Modifier.size(12.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(6.dp))

                                            Text(
                                                text = character.name,
                                                color = if (isCurrent) WakuGold else WakuText,
                                                fontSize = 11.sp,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }

                                    repeat(3 - rowChars.size) {
                                        Spacer(modifier = Modifier.width(92.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileTopBar(
    showSearchIcon: Boolean = true,
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

@Composable
private fun ProfileSectionHeader(
    icon: ImageVector,
    title: String,
    rightStatus: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = WakuGold,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = title,
                color = WakuGold,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp
            )
        }

        Text(
            text = rightStatus,
            color = Color(0xFFE4BEB7).copy(alpha = 0.60f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun ProfilePasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: ImageVector,
    isVisible: Boolean,
    onToggleVisibility: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = Color(0xFFE4BEB7).copy(alpha = 0.75f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = WakuText,
                fontSize = 14.sp,
                letterSpacing = if (isVisible || value.isEmpty()) 0.3.sp else 2.sp
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = if (isVisible) VisualTransformation.None else PasswordVisualTransformation(),
            cursorBrush = SolidColor(WakuPrimary),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused },
            decorationBox = { innerField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isFocused) Color(0xFF2F2926) else Color(0xFF241E1C))
                        .border(
                            width = 1.dp,
                            color = if (isFocused) WakuPrimary else Color.Transparent,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = Color(0xFFE4BEB7).copy(alpha = 0.55f),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                color = Color(0xFFAA8983),
                                fontSize = 14.sp
                            )
                        }
                        innerField()
                    }
                    IconButton(
                        onClick = onToggleVisibility,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                            contentDescription = "Mostrar u ocultar contraseña",
                            tint = Color(0xFFE4BEB7).copy(alpha = 0.55f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        )
    }
}