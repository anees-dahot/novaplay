package com.novaplay.player

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.novaplay.player.ui.library.LibraryScreen
import com.novaplay.player.update.UpdatePrompt
import com.novaplay.player.ui.player.PlayerActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                LibraryScreen(onOpen = { all, at ->
                    // Android caps how much an intent can carry (~1 MB): a huge folder would crash the launch,
                    // so pass a window of videos around the one that was tapped.
                    val from = (at - PLAYLIST_WINDOW / 2).coerceIn(0, (all.size - PLAYLIST_WINDOW).coerceAtLeast(0))
                    val videos = all.subList(from, minOf(all.size, from + PLAYLIST_WINDOW))
                    val index = at - from
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putParcelableArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(videos.map { it.uri }))
                            .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(videos.map { it.name }))
                            .putExtra(PlayerActivity.EXTRA_INDEX, index)
                    )
                }, onOpenUrl = { url ->
                    startActivity(Intent(this, PlayerActivity::class.java).setData(android.net.Uri.parse(url)))
                })
                UpdatePrompt()
                CrashPrompt()
            }
        }
    }
}

private const val PLAYLIST_WINDOW = 500

val Accent = Color(0xFF3D8BFF)     // single accent, used sparingly
val Navy = Color(0xFF0B0B0D)        // app background (neutral graphite, not tinted)
val NavySurface = Color(0xFF161618) // cards, bars, dialogs
val NavyHigh = Color(0xFF232327)    // raised elements / thumb placeholders

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            secondary = Accent,
            background = Navy,
            surface = Navy,
            surfaceContainer = NavySurface,
            surfaceContainerHigh = NavySurface,
            surfaceContainerHighest = NavyHigh,
            surfaceVariant = NavyHigh,
            onSurfaceVariant = Color(0xFF9A9AA2),
            outline = Color(0xFF3A3A40),
        ),
        content = content,
    )
}

/** After a crash, offer to share the log so it can be fixed. */
@Composable
private fun CrashPrompt() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var log by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(CrashLog.read(context)) }
    val text = log ?: return
    fun close() { CrashLog.clear(context); log = null }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = ::close,
        containerColor = NavySurface,
        title = { androidx.compose.material3.Text("NovaPlay closed unexpectedly") },
        text = { androidx.compose.material3.Text("Share the crash report so it can be fixed? It only contains technical details, no videos or names.") },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                context.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "NovaPlay crash report")
                            .putExtra(Intent.EXTRA_TEXT, text),
                        "Share crash report",
                    )
                )
                close()
            }) { androidx.compose.material3.Text("Share", color = Accent) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = ::close) { androidx.compose.material3.Text("Not now", color = Accent) } },
    )
}
