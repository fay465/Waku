package com.faygath.waku

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.faygath.waku.ui.theme.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

@Composable
fun ForgotPasswordScreen(
    onBackToLogin: () -> Unit
) {
    var identifier by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var emailSentSuccess by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WakuBg)
    ) {
        // Orbes de luz ambiental de fondo (bg-primary-container/10 y bg-secondary/5 blur-3xl)
        Box(
            modifier = Modifier
                .size(280.dp)
                .align(Alignment.TopCenter)
                .offset(y = (-20).dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(WakuPrimary.copy(alpha = 0.12f), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .size(220.dp)
                .align(Alignment.TopEnd)
                .offset(x = 60.dp, y = 180.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(WakuGold.copy(alpha = 0.07f), Color.Transparent)
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 0. TOP HEADER BAR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onBackToLogin,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = WakuText
                    )
                }

                Text(
                    text = "FORGOT PASSWORD",
                    color = WakuText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp
                )

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(WakuPink),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = null,
                        tint = Color(0xFF650900),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // CONTENIDO PRINCIPAL
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // 1. BRAND HERO IDENTIFIER
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "WAKU",
                        color = WakuText,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    SparkleStar(
                        symbol = "✦",
                        color = WakuGold,
                        fontSize = 18.sp,
                        delayMillis = 0
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(WakuGold)
                    )
                    Text(
                        text = "PRIVATE ARCHIVE ACCESS",
                        color = Color(0xFFE4BEB7),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(WakuGold)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 2. CENTRAL ICON & HEADER CONTENT
                Box(
                    modifier = Modifier.size(72.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Círculo exterior con degradado diagonal
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .shadow(12.dp, CircleShape)
                            .clip(CircleShape)
                            .background(Color(0xFF241E1C))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        WakuPrimary.copy(alpha = 0.20f),
                                        Color.Transparent,
                                        WakuGold.copy(alpha = 0.20f)
                                    )
                                )
                            )
                    )
                    // Círculo interior con ícono LockReset
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(WakuSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.LockReset,
                            contentDescription = null,
                            tint = WakuGold,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    // Tiny Status Ping en la esquina superior derecha del emblema
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-2).dp, y = 2.dp)
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(WakuBg)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(WakuPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(WakuPink)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "¿Olvidaste tu contraseña?",
                    color = WakuText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Ingresa el correo electrónico asociado a tu cuenta de WAKU y te enviaremos un enlace seguro para restablecerla.",
                    color = Color(0xFFE4BEB7),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 290.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 3. INTERACTIVE FORM & NOTIFICATION BANNER
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Cabecera del Input
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Identificador de Miembro",
                            color = Color(0xFFE4BEB7),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "SEGURIDAD SSL",
                            color = WakuGold,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                    }

                    // Campo de Correo o Usuario con botón X para borrar
                    var isFocused by remember { mutableStateOf(false) }
                    val borderColor = if (isFocused) WakuPrimary.copy(alpha = 0.6f) else Color.Transparent

                    BasicTextField(
                        value = identifier,
                        onValueChange = {
                            identifier = it
                            errorMessage = null
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = WakuText, fontSize = 14.sp),
                        cursorBrush = SolidColor(WakuPrimary),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isFocused = it.isFocused },
                        decorationBox = { innerTextField ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (isFocused) Color(0xFF241E1C) else WakuSurface)
                                    .border(2.dp, borderColor, RoundedCornerShape(16.dp))
                                    .padding(horizontal = 14.dp, vertical = 14.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Mail,
                                    contentDescription = null,
                                    tint = WakuPink,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Box(modifier = Modifier.weight(1f)) {
                                    if (identifier.isEmpty()) {
                                        Text(
                                            text = "anya.forger@eden-academy.edu",
                                            color = Color(0xFFE4BEB7).copy(alpha = 0.40f),
                                            fontSize = 14.sp
                                        )
                                    }
                                    innerTextField()
                                }
                                if (identifier.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF3A3331))
                                            .clickable { identifier = "" },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Close,
                                            contentDescription = "Borrar correo",
                                            tint = Color(0xFFE4BEB7),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    )

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage!!,
                            color = WakuPink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Botón de Acción Principal
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()
                    val buttonScale by animateFloatAsState(
                        targetValue = if (isPressed) 0.98f else 1f,
                        label = "recoverScale"
                    )

                    Button(
                        onClick = {
                            val input = identifier.trim()
                            if (input.isEmpty()) {
                                errorMessage = "Ingresa tu correo o nombre de usuario"
                                return@Button
                            }
                            isLoading = true
                            errorMessage = null

                            val sendResetToEmail: (String) -> Unit = { email ->
                                auth.sendPasswordResetEmail(email)
                                    .addOnCompleteListener { task ->
                                        isLoading = false
                                        if (task.isSuccessful) {
                                            emailSentSuccess = true
                                        } else {
                                            errorMessage = "No se pudo enviar el enlace. Verifica el correo."
                                        }
                                    }
                            }

                            if (input.contains("@")) {
                                sendResetToEmail(input)
                            } else {
                                db.collection("usuarios")
                                    .whereEqualTo("username", input)
                                    .limit(1)
                                    .get()
                                    .addOnSuccessListener { snapshot ->
                                        val email = snapshot.documents.firstOrNull()?.getString("email")
                                        if (!email.isNullOrEmpty()) {
                                            sendResetToEmail(email)
                                        } else {
                                            isLoading = false
                                            errorMessage = "No se encontró ninguna cuenta para '$input'"
                                        }
                                    }
                                    .addOnFailureListener {
                                        isLoading = false
                                        errorMessage = "Error de red al buscar el usuario"
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
                        contentPadding = PaddingValues(vertical = 16.dp, horizontal = 24.dp),
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = when {
                                    isLoading -> "ENVIANDO..."
                                    emailSentSuccess -> "REENVIAR ENLACE"
                                    else -> "RECUPERAR CONTRASEÑA"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.8.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.Send,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Banner de Éxito (Visible al enviar el correo)
                    AnimatedVisibility(
                        visible = emailSentSuccess,
                        enter = fadeIn() + scaleIn(initialScale = 0.95f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(WakuGreenDark.copy(alpha = 0.30f))
                                .border(
                                    1.dp,
                                    WakuGreen.copy(alpha = 0.25f),
                                    RoundedCornerShape(16.dp)
                                )
                                .padding(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF54774D)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.MarkEmailRead,
                                    contentDescription = null,
                                    tint = Color(0xFFD5FDC8),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "¡Enlace enviado con éxito!",
                                    color = WakuGreen,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Revisa tu bandeja de entrada o carpeta de spam para autorizar el restablecimiento de tu contraseña.",
                                    color = WakuText,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                                Text(
                                    text = "VÁLIDO DURANTE 15 MINUTOS",
                                    color = WakuGreen.copy(alpha = 0.80f),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Botón Secundario: Volver al inicio de sesión
                    Row(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .clip(CircleShape)
                            .clickable { onBackToLogin() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = WakuGold,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Volver al inicio de sesión",
                            color = WakuGold,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // 4. PINNED SECURITY FOOTER
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp, top = 16.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(WakuSurface)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Security,
                        contentDescription = null,
                        tint = WakuGold,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "SOLO MIEMBROS AUTORIZADOS • PLATAFORMA PRIVADA WAKU",
                        color = Color(0xFFE4BEB7),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "v2.4.0 • Eden Terminal Build",
                    color = Color(0xFFE4BEB7).copy(alpha = 0.40f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}