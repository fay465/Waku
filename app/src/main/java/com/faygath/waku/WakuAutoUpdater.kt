package com.faygath.waku

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.faygath.waku.ui.theme.*
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

private fun Context.getInstalledVersionCode(): Long {
    return runCatching {
        val pInfo = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pInfo.versionCode.toLong()
        }
    }.getOrDefault(1L)
}

@Composable
fun WakuAutoUpdateChecker() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var updateAvailable by remember { mutableStateOf(false) }
    var latestVersionName by remember { mutableStateOf("") }
    var changelogText by remember { mutableStateOf("") }
    var apkDownloadUrl by remember { mutableStateOf("") }
    var isMandatory by remember { mutableStateOf(false) }

    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val currentCode = context.getInstalledVersionCode()
        FirebaseFirestore.getInstance()
            .collection("app_config")
            .document("version")
            .get()
            .addOnSuccessListener { doc ->
                if (doc != null && doc.exists()) {
                    val remoteCode = doc.getLong("versionCode") ?: 1L
                    val url = doc.getString("apkUrl")?.trim().orEmpty()
                    if (remoteCode > currentCode && url.startsWith("http")) {
                        latestVersionName = doc.getString("versionName")?.trim() ?: "v$remoteCode"
                        changelogText = doc.getString("changelog")?.replace("\\n", "\n")?.trim()
                            ?: "Nuevas mejoras de estabilidad y reproducción."
                        apkDownloadUrl = url
                        isMandatory = doc.getBoolean("obligatoria") ?: false
                        updateAvailable = true
                    }
                }
            }
    }

    if (!updateAvailable) return

    AlertDialog(
        onDismissRequest = {
            if (!isMandatory && !isDownloading) updateAvailable = false
        },
        containerColor = Color(0xFF1E1614),
        shape = RoundedCornerShape(22.dp),
        title = {
            Column {
                Text(
                    text = "✦ ACTUALIZACIÓN DISPONIBLE",
                    color = WakuGold,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Waku $latestVersionName",
                    color = WakuText,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = changelogText,
                    color = Color(0xFFE4BEB7),
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )

                if (isDownloading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        color = WakuPrimary,
                        trackColor = Color(0xFF3A3331),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Descargando... ${(downloadProgress * 100).toInt()}%",
                        color = WakuGold,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.End)
                    )
                }

                if (downloadError != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = downloadError.orEmpty(),
                        color = WakuPink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isDownloading,
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        !context.packageManager.canRequestPackageInstalls()
                    ) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                        downloadError = "Autoriza 'Instalar apps desconocidas' y vuelve a pulsar Actualizar."
                        return@Button
                    }

                    isDownloading = true
                    downloadError = null
                    downloadProgress = 0f

                    scope.launch {
                        val result = downloadApkFile(
                            context = context,
                            apkUrl = apkDownloadUrl,
                            onProgress = { prog -> downloadProgress = prog }
                        )
                        isDownloading = false
                        result.onSuccess { apkFile ->
                            installApkFile(context, apkFile)
                        }.onFailure { err ->
                            downloadError = "Error al descargar: ${err.localizedMessage ?: "verifica tu conexión"}"
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = WakuPrimary),
                shape = CircleShape
            ) {
                Text(
                    text = if (isDownloading) "Descargando..." else "Actualizar ahora",
                    color = WakuText,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            if (!isMandatory && !isDownloading) {
                TextButton(onClick = { updateAvailable = false }) {
                    Text("Más tarde", color = Color(0xFFE4BEB7))
                }
            }
        },
        modifier = Modifier.border(1.dp, Color(0xFF3A3331), RoundedCornerShape(22.dp))
    )
}

private suspend fun downloadApkFile(
    context: Context,
    apkUrl: String,
    onProgress: (Float) -> Unit
): Result<File> = withContext(Dispatchers.IO) {
    runCatching {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
        val outFile = File(dir, "Waku-update.apk")
        if (outFile.exists()) outFile.delete()

        val req = Request.Builder().url(apkUrl).build()
        WakuRepository.httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("Archivo vacío")
            val totalBytes = body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    var downloaded = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalBytes > 0) {
                            onProgress((downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                    output.flush()
                }
            }
        }
        outFile
    }
}

private fun installApkFile(context: Context, apkFile: File) {
    val apkUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile
    )
    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(installIntent)
}