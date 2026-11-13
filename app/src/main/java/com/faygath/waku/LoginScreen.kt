package com.faygath.waku

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.faygath.waku.ui.theme.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL

// Si está en true: la cuenta queda casada con el PRIMER celular que inicie sesión (nadie más puede entrar).
// Si lo pasas a false: deja entrar al celular nuevo, pero expulsa en tiempo real al celular anterior.
private const val STRICT_HARDWARE_LOCK = true
private const val DEFAULT_MAX_DEVICES = 2

fun getUniqueDeviceId(context: android.content.Context): String {
    return android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ANDROID_ID
    ) ?: "unknown_device"
}

private fun verifyOrBindDeviceOnLogin(
    context: android.content.Context,
    db: com.google.firebase.firestore.FirebaseFirestore,
    auth: com.google.firebase.auth.FirebaseAuth,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    val uid = auth.currentUser?.uid
    if (uid == null) {
        onError("No se pudo verificar la sesión")
        return
    }

    val currentDeviceId = getUniqueDeviceId(context)
    val currentDeviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
    val userRef = db.collection("usuarios").document(uid)

    userRef.get()
        .addOnSuccessListener { doc ->
            if (doc.getBoolean("bloqueado") == true) {
                auth.signOut()
                onError("Esta cuenta ha sido suspendida por el administrador.")
                return@addOnSuccessListener
            }

            // Límite por defecto: 2 dispositivos (o el número que pongas en el campo "maxDevices" en Firestore)
            val maxAllowed = doc.getLong("maxDevices")?.toInt()?.coerceAtLeast(1) ?: DEFAULT_MAX_DEVICES

            // Leer lista actual de IDs y modelos (con compatibilidad por si ya existía boundDeviceId antiguo)
            val rawIds = (doc.get("boundDeviceIds") as? List<*>)
                ?.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
                .orEmpty()
            val rawNames = (doc.get("boundDeviceNames") as? List<*>)
                ?.mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
                .orEmpty()

            val legacyId = doc.getString("boundDeviceId")?.trim()?.takeIf { it.isNotEmpty() }
            val legacyName = doc.getString("boundDeviceName")?.trim()?.takeIf { it.isNotEmpty() }

            val boundIds = when {
                rawIds.isNotEmpty() -> rawIds.toMutableList()
                legacyId != null -> mutableListOf(legacyId)
                else -> mutableListOf()
            }
            val boundNames = when {
                rawNames.isNotEmpty() -> rawNames.toMutableList()
                legacyName != null -> mutableListOf(legacyName)
                else -> mutableListOf()
            }

            val existingIndex = boundIds.indexOf(currentDeviceId)

            if (existingIndex != -1) {
                // Caso 1: Este dispositivo (celular o tablet) ya es uno de los 2 autorizados
                if (existingIndex < boundNames.size) {
                    boundNames[existingIndex] = currentDeviceModel
                } else {
                    boundNames.add(currentDeviceModel)
                }

                val updateData = mapOf(
                    "boundDeviceIds" to boundIds,
                    "boundDeviceNames" to boundNames,
                    "lastLoginAt" to System.currentTimeMillis()
                )
                userRef.set(updateData, com.google.firebase.firestore.SetOptions.merge())
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener { onSuccess() }

            } else if (boundIds.size < maxAllowed || !STRICT_HARDWARE_LOCK) {
                // Caso 2: Aún tiene cupo libre (ej. tenía 0/2 o 1/2 dispositivos) -> registramos este nuevo
                boundIds.add(currentDeviceId)
                boundNames.add(currentDeviceModel)

                val updateData = mapOf(
                    "boundDeviceIds" to boundIds,
                    "boundDeviceNames" to boundNames,
                    "lastLoginAt" to System.currentTimeMillis()
                )
                userRef.set(updateData, com.google.firebase.firestore.SetOptions.merge())
                    .addOnSuccessListener { onSuccess() }
                    .addOnFailureListener { onSuccess() }

            } else {
                // Caso 3: Ya ocupó sus 2 cupos e intenta entrar un 3er dispositivo
                auth.signOut()
                val registeredDevicesText = if (boundNames.isNotEmpty()) {
                    boundNames.joinToString(" y ")
                } else {
                    "$maxAllowed dispositivos"
                }
                onError("Límite alcanzado ($maxAllowed/$maxAllowed): cuenta vinculada a $registeredDevicesText.")
            }
        }
        .addOnFailureListener {
            auth.signOut()
            onError("Error al verificar los dispositivos autorizados")
        }
}

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    onForgotPasswordClick: () -> Unit
) {
    var usernameOrEmail by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }

    val context = LocalContext.current

    val configuration = LocalConfiguration.current
    val topSpacerHeight = (configuration.screenHeightDp * 0.38f).dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WakuBg)
            .clipToBounds()
    ) {
        // 1. CAPA DE FONDO: Video vertical 1080x1920 a pantalla completa
        LoginVideoBackground(
            modifier = Modifier.fillMaxSize()
        )

        // 2. CAPA INTERMEDIA: Degradado continuo de arriba a abajo para que la mitad superior
        // luzca el video y la mitad inferior tenga contraste oscuro para el formulario
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to WakuBg.copy(alpha = 0.35f),
                        0.15f to Color.Transparent,
                        0.42f to WakuBg.copy(alpha = 0.55f),
                        0.68f to WakuBg.copy(alpha = 0.88f),
                        1.00f to WakuBg.copy(alpha = 0.96f)
                    )
                )
        )

        // 3. CAPA FRONTAL: Contenido del Login (Estrellas + Logo + Inputs + Footer)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {

                // Ventana superior donde se aprecia el video con las estrellas animadas
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(topSpacerHeight)
                ) {
                    SparkleStar(
                        symbol = "✦",
                        color = WakuGold,
                        fontSize = 13.sp,
                        delayMillis = 0,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 42.dp, end = 40.dp)
                    )
                    SparkleStar(
                        symbol = "★",
                        color = WakuGold,
                        fontSize = 18.sp,
                        delayMillis = 1200,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 110.dp, start = 32.dp)
                    )
                    SparkleStar(
                        symbol = "✦",
                        color = WakuGold,
                        fontSize = 14.sp,
                        delayMillis = 2100,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 36.dp, end = 32.dp)
                    )
                    SparkleStar(
                        symbol = "✧",
                        color = WakuPink,
                        fontSize = 12.sp,
                        delayMillis = 2100,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 80.dp, end = 110.dp)
                    )
                }

                // Cabecera de marca WAKU
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "WAKU",
                            color = WakuText,
                            fontSize = 36.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        SparkleStar(
                            symbol = "✦",
                            color = WakuGold,
                            fontSize = 20.sp,
                            delayMillis = 0
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Acceso privado",
                            color = WakuTextMuted.copy(alpha = 0.80f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = " • ",
                            color = WakuPink.copy(alpha = 0.70f),
                            fontSize = 12.sp
                        )
                        Text(
                            text = "¡Waku Waku!",
                            color = WakuPink,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                // Formulario de Login
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    WakuInputField(
                        value = usernameOrEmail,
                        onValueChange = {
                            usernameOrEmail = it
                            errorMessage = null
                        },
                        placeholder = "Usuario o correo electrónico",
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Person,
                                contentDescription = null,
                                tint = WakuPink,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        )
                    )

                    WakuInputField(
                        value = password,
                        onValueChange = {
                            password = it
                            errorMessage = null
                        },
                        placeholder = "Contraseña",
                        isPassword = !passwordVisible,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = WakuPink,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = "Mostrar u ocultar contraseña",
                                    tint = WakuPink.copy(alpha = 0.75f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        )
                    )

                    Box(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "¿Olvidaste tu contraseña?",
                            color = WakuGold,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .clickable { onForgotPasswordClick() }
                                .padding(vertical = 2.dp)
                        )
                    }

                    if (errorMessage != null) {
                        Text(
                            text = errorMessage!!,
                            color = WakuPink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    val buttonScale by animateFloatAsState(
                        targetValue = if (isPressed) 0.97f else 1f,
                        label = "buttonScale"
                    )

                    Button(
                        onClick = {
                            val input = usernameOrEmail.trim()
                            val pass = password.trim()
                            if (input.isEmpty() || pass.isEmpty()) {
                                errorMessage = "Completa tu usuario y contraseña"
                                return@Button
                            }
                            isLoading = true
                            errorMessage = null

                            val handlePostAuth = {
                                verifyOrBindDeviceOnLogin(
                                    context = context,
                                    db = db,
                                    auth = auth,
                                    onSuccess = {
                                        isLoading = false
                                        onLoginSuccess()
                                    },
                                    onError = { msg ->
                                        isLoading = false
                                        errorMessage = msg
                                    }
                                )
                            }

                            if (input.contains("@")) {
                                auth.signInWithEmailAndPassword(input, pass)
                                    .addOnCompleteListener { task ->
                                        if (task.isSuccessful) {
                                            handlePostAuth()
                                        } else {
                                            isLoading = false
                                            errorMessage = "Credenciales incorrectas o cuenta no autorizada"
                                        }
                                    }
                            } else {
                                db.collection("usuarios")
                                    .whereEqualTo("username", input)
                                    .limit(1)
                                    .get()
                                    .addOnSuccessListener { snapshot ->
                                        val email = snapshot.documents.firstOrNull()?.getString("email")
                                        if (!email.isNullOrEmpty()) {
                                            auth.signInWithEmailAndPassword(email, pass)
                                                .addOnCompleteListener { task ->
                                                    if (task.isSuccessful) {
                                                        handlePostAuth()
                                                    } else {
                                                        isLoading = false
                                                        errorMessage = "Contraseña incorrecta"
                                                    }
                                                }
                                        } else {
                                            isLoading = false
                                            errorMessage = "No se encontró el usuario '$input'"
                                        }
                                    }
                                    .addOnFailureListener {
                                        isLoading = false
                                        errorMessage = "Error de conexión al verificar el usuario"
                                    }
                            }
                        },
                        interactionSource = interactionSource,
                        enabled = !isLoading,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WakuPrimary,
                            contentColor = WakuText
                        ),
                        contentPadding = PaddingValues(vertical = 15.dp, horizontal = 24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .scale(buttonScale)
                            .shadow(
                                elevation = 20.dp,
                                shape = CircleShape,
                                ambientColor = WakuPrimary,
                                spotColor = WakuPrimary
                            )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = WakuText,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "Iniciar Sesión",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Pie de página
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp, top = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(WakuSurface.copy(alpha = 0.85f))
                        .border(1.dp, WakuPink.copy(alpha = 0.15f), CircleShape)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    PulsingGreenDot()
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = WakuGreen,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Solo miembros autorizados",
                        color = WakuTextMuted.copy(alpha = 0.85f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "IJOS DEL PIKO • 2026",
                    color = WakuTextMuted.copy(alpha = 0.45f),
                    fontSize = 10.sp,
                    letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun WakuInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false,
    leadingIcon: @Composable () -> Unit,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    var isFocused by remember { mutableStateOf(false) }
    val borderColor = if (isFocused) WakuPrimary.copy(alpha = 0.75f) else WakuPink.copy(alpha = 0.25f)
    val borderWidth = if (isFocused) 1.5.dp else 1.dp

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            color = WakuText,
            fontSize = 14.sp
        ),
        cursorBrush = SolidColor(WakuPrimary),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = keyboardOptions,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        decorationBox = { innerTextField ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(WakuInputBg.copy(alpha = 0.88f))
                    .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
                    .padding(
                        start = 16.dp,
                        end = if (trailingIcon != null) 4.dp else 16.dp,
                        top = 4.dp,
                        bottom = 4.dp
                    )
                    .heightIn(min = 46.dp)
            ) {
                leadingIcon()
                Spacer(modifier = Modifier.width(12.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = WakuTextMuted.copy(alpha = 0.45f),
                            fontSize = 14.sp
                        )
                    }
                    innerTextField()
                }
                if (trailingIcon != null) {
                    trailingIcon()
                }
            }
        }
    )
}

@Composable
fun SparkleStar(
    symbol: String,
    color: Color,
    fontSize: TextUnit,
    delayMillis: Int,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "stellaPulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1750,
                delayMillis = delayMillis,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseProgress"
    )

    val alpha = 0.35f + (0.60f * progress)
    val scale = 0.95f + (0.15f * progress)

    Text(
        text = symbol,
        color = color,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .scale(scale)
            .alpha(alpha)
    )
}

@Composable
fun PulsingGreenDot() {
    val transition = rememberInfiniteTransition(label = "pingDot")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 2.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pingScale"
    )
    val alpha by transition.animateFloat(
        initialValue = 0.75f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pingAlpha"
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(10.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .scale(scale)
                .alpha(alpha)
                .clip(CircleShape)
                .background(WakuGreen)
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(WakuGreen)
        )
    }
}

@Composable
fun LoginVideoBackground(
    modifier: Modifier = Modifier,
    primaryUrl: String = "https://res.cloudinary.com/nlm4bmp3/video/upload/vc_h264,q_auto/lasttry.mp4",
    fallbackUrl: String = "https://res.cloudinary.com/nlm4bmp3/video/upload/lasttry.mp4"
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isVideoReady by remember { mutableStateOf(false) }
    val videoAlpha by animateFloatAsState(
        targetValue = if (isVideoReady) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "loginVideoAlpha"
    )

    val cachedFile = remember { File(context.cacheDir, "waku_login_bg_v2.mp4") }

    LaunchedEffect(Unit) {
        if (!cachedFile.exists() || cachedFile.length() < 100_000L) {
            withContext(Dispatchers.IO) {
                val downloaded = runCatching {
                    val tempFile = File(context.cacheDir, "waku_login_temp.mp4")
                    URL(primaryUrl).openStream().use { input ->
                        FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                    }
                    if (tempFile.length() > 100_000L) {
                        tempFile.renameTo(cachedFile)
                        true
                    } else false
                }.getOrDefault(false)

                if (!downloaded) {
                    runCatching {
                        val tempFile = File(context.cacheDir, "waku_login_temp.mp4")
                        URL(fallbackUrl).openStream().use { input ->
                            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                        }
                        if (tempFile.length() > 100_000L) {
                            tempFile.renameTo(cachedFile)
                        }
                    }
                }
            }
        }
    }

    val mediaPlayer = remember { MediaPlayer() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    runCatching { if (mediaPlayer.isPlaying) mediaPlayer.pause() }
                }
                Lifecycle.Event.ON_RESUME -> {
                    runCatching { if (isVideoReady && !mediaPlayer.isPlaying) mediaPlayer.start() }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching {
                mediaPlayer.stop()
                mediaPlayer.release()
            }
        }
    }

    Box(
        modifier = modifier
            .background(WakuBg)
            .clipToBounds()
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .alpha(videoAlpha),
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = false

                    // Escalado proporcional desde el centro exacto de la pantalla
                    fun applyCenterCrop(videoWidth: Int, videoHeight: Int) {
                        if (width == 0 || height == 0 || videoWidth == 0 || videoHeight == 0) return
                        val viewWidth = width.toFloat()
                        val viewHeight = height.toFloat()
                        val viewRatio = viewWidth / viewHeight
                        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()

                        val scaleX: Float
                        val scaleY: Float
                        if (videoRatio > viewRatio) {
                            scaleX = videoRatio / viewRatio
                            scaleY = 1f
                        } else {
                            scaleX = 1f
                            scaleY = viewRatio / videoRatio
                        }

                        val matrix = Matrix()
                        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
                        setTransform(matrix)
                    }

                    fun startPlayerWithSource(
                        surfaceTexture: SurfaceTexture,
                        urlOrPath: String,
                        allowFallback: Boolean
                    ) {
                        runCatching {
                            mediaPlayer.reset()
                            if (urlOrPath.startsWith("http")) {
                                mediaPlayer.setDataSource(ctx, Uri.parse(urlOrPath))
                            } else {
                                mediaPlayer.setDataSource(urlOrPath)
                            }
                            mediaPlayer.setSurface(Surface(surfaceTexture))
                            mediaPlayer.isLooping = true
                            mediaPlayer.setVolume(0f, 0f)

                            mediaPlayer.setOnVideoSizeChangedListener { _, vw, vh ->
                                applyCenterCrop(vw, vh)
                            }

                            mediaPlayer.setOnPreparedListener { mp ->
                                applyCenterCrop(mp.videoWidth, mp.videoHeight)
                                mp.start()
                                isVideoReady = true
                            }

                            mediaPlayer.setOnErrorListener { _, _, _ ->
                                if (allowFallback) {
                                    startPlayerWithSource(surfaceTexture, fallbackUrl, false)
                                }
                                true
                            }

                            mediaPlayer.prepareAsync()
                        }
                    }

                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            w: Int,
                            h: Int
                        ) {
                            val initialSource = if (cachedFile.exists() && cachedFile.length() > 100_000L) {
                                cachedFile.absolutePath
                            } else {
                                primaryUrl
                            }
                            startPlayerWithSource(surfaceTexture, initialSource, true)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surfaceTexture: SurfaceTexture,
                            w: Int,
                            h: Int
                        ) {
                            runCatching {
                                applyCenterCrop(mediaPlayer.videoWidth, mediaPlayer.videoHeight)
                            }
                        }

                        override fun onSurfaceTextureDestroyed(
                            surfaceTexture: SurfaceTexture
                        ): Boolean = true

                        override fun onSurfaceTextureUpdated(
                            surfaceTexture: SurfaceTexture
                        ) {}
                    }
                }
            }
        )
    }
}