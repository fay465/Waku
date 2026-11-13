package com.faygath.waku

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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.faygath.waku.ui.theme.*
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

// Encuadre enfocado en el tercio superior para que los retratos verticales de AniList encuadren siempre la cara
val FaceTopAlignment = BiasAlignment(horizontalBias = 0f, verticalBias = -0.60f)

// Avatar oficial por defecto (Anya Forger desde AniList CDN) si el usuario aún no ha elegido uno
private const val DEFAULT_ANILIST_AVATAR =
    "https://s4.anilist.co/file/anilistcdn/character/large/b138101-768222I9331P.png"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onNavigateTab: (String) -> Unit,
    onLogoutSuccess: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val usernameFocusRequester = remember { FocusRequester() }

    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val currentUser = auth.currentUser
    val uid = currentUser?.uid

    val userEmail = currentUser?.email ?: "usuario@waku.app"

    val memberSinceText = remember(currentUser) {
        val creationTs = currentUser?.metadata?.creationTimestamp ?: 0L
        if (creationTs > 0L) {
            val zdt = Instant.ofEpochMilli(creationTs).atZone(ZoneId.systemDefault())
            val monthEs = zdt.month.getDisplayName(JavaTextStyle.FULL, Locale("es", "ES"))
                .replaceFirstChar { it.uppercase() }
            "Miembro desde $monthEs ${zdt.year}"
        } else {
            "Miembro desde Octubre 2023"
        }
    }

    var selectedAvatarUrl by remember { mutableStateOf(DEFAULT_ANILIST_AVATAR) }
    var displayedUsername by remember {
        mutableStateOf(
            currentUser?.displayName?.takeIf { it.isNotBlank() }
                ?: userEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
        )
    }
    var usernameInput by remember { mutableStateOf(displayedUsername) }

    // Estado para abrir el Modal Selector de Avatares por Serie
    var showAvatarPickerSheet by remember { mutableStateOf(false) }

    // Estados de contraseña
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

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

    // Cargar datos actuales del usuario desde Firestore
    LaunchedEffect(uid) {
        if (uid != null) {
            db.collection("usuarios").document(uid).get()
                .addOnSuccessListener { doc ->
                    if (doc != null && doc.exists()) {
                        doc.getString("avatarUrl")?.takeIf { it.isNotBlank() }?.let {
                            selectedAvatarUrl = it
                        }
                        val savedName = doc.getString("username")
                            ?: doc.getString("nombreUsuario")
                            ?: doc.getString("nombre")
                        if (!savedName.isNullOrBlank()) {
                            displayedUsername = savedName
                            usernameInput = savedName
                        }
                    }
                }
        }
    }

    val hasValidNewPassword = remember(newPassword) {
        newPassword.length >= 8 &&
                newPassword.any { it.isDigit() } &&
                newPassword.any { !it.isLetterOrDigit() }
    }

    Scaffold(
        containerColor = WakuBg,
        topBar = {
            ProfileTopBar(
                onSearchClick = { onNavigateTab("catalogo_search") }
            )
        },
        bottomBar = {
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
                // 1. CABECERA LIMPIA: "Mi Perfil •" (Sin el badge de Plan Premium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Mi Perfil",
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

                Spacer(modifier = Modifier.height(18.dp))

                // 2. AVATAR CENTRAL CON BORDE DORADO Y BOTÓN DE LÁPIZ (Abre el catálogo de avatares de AniList)
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

                        // Botón flotante con Lápiz (recorte circular antes del clickable)
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
                            text = displayedUsername,
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
                    rightStatus = "SINCRONIZADO"
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
                        text = if (usernameInput.trim().length >= 3) "DISPONIBLE" else "MÍN. 3 LETRAS",
                        color = if (usernameInput.trim().length >= 3) WakuGreen else WakuGold,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))

                var isUsernameFocused by remember { mutableStateOf(false) }
                BasicTextField(
                    value = usernameInput,
                    onValueChange = { usernameInput = it },
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
                        .onFocusChanged { isUsernameFocused = it.isFocused },
                    decorationBox = { innerField ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isUsernameFocused) Color(0xFF2F2926) else Color(0xFF241E1C))
                                .border(
                                    width = 1.dp,
                                    color = if (isUsernameFocused) WakuPrimary else Color.Transparent,
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
                                if (usernameInput.isEmpty()) {
                                    Text(
                                        text = "Ingresa tu apodo",
                                        color = Color(0xFFAA8983),
                                        fontSize = 15.sp
                                    )
                                }
                                innerField()
                            }
                            IconButton(
                                onClick = { usernameFocusRequester.requestFocus() },
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
                    title = "CAMBIAR CONTRASEÑA",
                    rightStatus = "SEGURIDAD SSL"
                )

                Spacer(modifier = Modifier.height(10.dp))

                ProfilePasswordField(
                    label = "Contraseña Actual",
                    value = currentPassword,
                    onValueChange = { currentPassword = it },
                    placeholder = "••••••••••••",
                    leadingIcon = Icons.Outlined.VpnKey,
                    isVisible = showCurrentPwd,
                    onToggleVisibility = { showCurrentPwd = !showCurrentPwd }
                )

                Spacer(modifier = Modifier.height(10.dp))

                ProfilePasswordField(
                    label = "Nueva Contraseña",
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    placeholder = "Mínimo 8 caracteres",
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
                        text = "Debe incluir al menos un número y símbolo especial",
                        color = Color(0xFFE4BEB7).copy(alpha = 0.72f),
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(26.dp))

                // 5. BOTONES DE ACCIÓN: GUARDAR CAMBIOS & CERRAR SESIÓN
                Button(
                    onClick = {
                        focusManager.clearFocus()
                        val cleanName = usernameInput.trim()
                        if (cleanName.length < 3) {
                            isErrorNotice = true
                            noticeMessage = "El nombre de usuario debe tener al menos 3 caracteres"
                            return@Button
                        }

                        val wantsPasswordChange = currentPassword.isNotBlank() ||
                                newPassword.isNotBlank() ||
                                confirmPassword.isNotBlank()

                        if (wantsPasswordChange) {
                            if (currentPassword.isBlank()) {
                                isErrorNotice = true
                                noticeMessage = "Ingresa tu contraseña actual para confirmar el cambio"
                                return@Button
                            }
                            if (!hasValidNewPassword) {
                                isErrorNotice = true
                                noticeMessage = "La nueva contraseña requiere 8+ caracteres, un número y un símbolo"
                                return@Button
                            }
                            if (newPassword != confirmPassword) {
                                isErrorNotice = true
                                noticeMessage = "La nueva contraseña y su confirmación no coinciden"
                                return@Button
                            }
                        }

                        isSaving = true

                        if (uid != null) {
                            val profileData = mapOf(
                                "username" to cleanName,
                                "avatarUrl" to selectedAvatarUrl,
                                "email" to userEmail,
                                "updatedAt" to System.currentTimeMillis()
                            )
                            db.collection("usuarios").document(uid)
                                .set(profileData, SetOptions.merge())
                        }

                        val profileUpdates = UserProfileChangeRequest.Builder()
                            .setDisplayName(cleanName)
                            .build()
                        currentUser?.updateProfile(profileUpdates)
                        displayedUsername = cleanName

                        if (wantsPasswordChange && currentUser != null && currentUser.email != null) {
                            val credential = EmailAuthProvider.getCredential(currentUser.email!!, currentPassword)
                            currentUser.reauthenticate(credential)
                                .addOnSuccessListener {
                                    currentUser.updatePassword(newPassword)
                                        .addOnSuccessListener {
                                            isSaving = false
                                            currentPassword = ""
                                            newPassword = ""
                                            confirmPassword = ""
                                            isErrorNotice = false
                                            noticeMessage = "Perfil y contraseña actualizados con éxito"
                                        }
                                        .addOnFailureListener { err ->
                                            isSaving = false
                                            isErrorNotice = true
                                            noticeMessage = err.localizedMessage ?: "Error al actualizar la contraseña"
                                        }
                                }
                                .addOnFailureListener {
                                    isSaving = false
                                    isErrorNotice = true
                                    noticeMessage = "La contraseña actual ingresada es incorrecta"
                                }
                        } else {
                            isSaving = false
                            isErrorNotice = false
                            noticeMessage = "Perfil actualizado con éxito"
                        }
                    },
                    enabled = !isSaving,
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
                            text = "Guardar Cambios",
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
                        if (uid != null) {
                            db.collection("usuarios").document(uid)
                                .set(mapOf("avatarUrl" to charItem.imageUrl), SetOptions.merge())
                        }
                        isErrorNotice = false
                        noticeMessage = "Avatar de ${charItem.name} aplicado"
                    },
                    onClose = { showAvatarPickerSheet = false }
                )
            }
        }
    }
}

/**
 * Contenido del Modal de Avatares:
 * - Buscador propio arriba para encontrar personajes de cualquier anime en AniList.
 * - Formato por secciones:
 *     Nombre Serie ──────────────
 *     [Avatar 1]  [Avatar 2]  [Avatar 3]
 */
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
        // Cabecera del Modal
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

        // Buscador de series dentro del modal
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
                        // CABECERA DE LA SERIE: "Nombre Serie ────────────────"
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

                        // Filas de 3 avatares por serie
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

                                    // Rellenar espacios si una fila tiene menos de 3 personajes para mantener la alineación
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
private fun ProfileTopBar(onSearchClick: () -> Unit) {
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