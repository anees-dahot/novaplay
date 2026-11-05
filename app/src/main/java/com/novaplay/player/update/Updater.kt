package com.novaplay.player.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.novaplay.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

/** Checks GitHub Releases for a newer NovaPlay and installs it. */
object Updater {

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 20_000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "NovaPlay-Updater")
    }

    /** Returns the latest release if it's newer than this build, else null. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = open("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            if (conn.responseCode != 200) return@runCatching null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").removePrefix("v")
            if (!isNewer(version, BuildConfig.VERSION_NAME)) return@runCatching null

            // Prefer the APK built for this phone's CPU, fall back to the universal one.
            val assets = json.getJSONArray("assets")
            val names = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.getString("name").endsWith(".apk") }
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
            val asset = names.firstOrNull { it.getString("name").contains(abi) }
                ?: names.firstOrNull { it.getString("name").contains("universal") }
                ?: names.firstOrNull()
                ?: return@runCatching null
            Release(version, json.optString("body"), asset.getString("browser_download_url"), asset.optLong("size"))
        }.getOrNull()
    }

    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Downloads the APK into cache; reports progress 0..1. */
    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "NovaPlay-${release.version}.apk")
            val conn = open(release.apkUrl).apply { instanceFollowRedirects = true }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val f = (done.toFloat() / total).coerceIn(0f, 1f)
                            val pct = (f * 100).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                withContext(Dispatchers.Main) { onProgress(f) }
                            }
                        }
                    }
                }
            }
            file
        }

    /** Hands the APK to Android's installer (user taps "Update" once). */
    fun install(context: Context, apk: File) = runCatching {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return@runCatching
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
}

/** Checks once per app launch and offers the update in a dialog. */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<Release?>(null) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var apk by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { release = Updater.check() }

    val r = release ?: return
    AlertDialog(
        onDismissRequest = { if (progress < 0) release = null },
        title = { Text("Update available: v${r.version}") },
        text = {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                if (r.notes.isNotBlank()) Text(r.notes)
                if (progress >= 0 && apk == null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
                    )
                    val mb = r.size / 1_048_576f
                    Text(
                        "Downloading… %d%%  ·  %.1f of %.1f MB".format((progress * 100).toInt(), progress * mb, mb),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (apk != null) Text("Downloaded. Tap Install.", modifier = Modifier.padding(top = 8.dp))
                error?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = progress < 0 || apk != null,
                onClick = {
                    apk?.let {
                        if (!Updater.install(context, it)) error = "Couldn't open installer."
                        return@TextButton
                    }
                    progress = 0f
                    error = null
                    scope.launch {
                        runCatching { Updater.download(context, r) { p -> progress = p } }
                            .onSuccess {
                                apk = it
                                if (!Updater.install(context, it)) error = "Couldn't open installer."
                            }
                            .onFailure { error = "Download failed. Check your connection."; progress = -1f }
                    }
                },
            ) { Text(if (apk != null) "Install" else "Update") }
        },
        dismissButton = {
            if (progress < 0) TextButton(onClick = { release = null }) { Text("Later") }
        },
    )
}
