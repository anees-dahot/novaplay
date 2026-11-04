package com.adfree.player

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.adfree.player.ui.library.LibraryScreen
import com.adfree.player.update.UpdatePrompt
import com.adfree.player.ui.player.PlayerActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                LibraryScreen(onOpen = { videos, index ->
                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                            .putParcelableArrayListExtra(PlayerActivity.EXTRA_URIS, ArrayList(videos.map { it.uri }))
                            .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(videos.map { it.name }))
                            .putExtra(PlayerActivity.EXTRA_INDEX, index)
                    )
                })
                UpdatePrompt()
            }
        }
    }
}

val Accent = Color(0xFF0A84FF)      // system blue accent
val Navy = Color(0xFF05080F)        // app background
val NavySurface = Color(0xFF0F1B33) // cards, bars, dialogs
val NavyHigh = Color(0xFF17284A)    // raised elements / thumb placeholders

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
            onSurfaceVariant = Color(0xFF9DB0D0),
            outline = Color(0xFF3A4E75),
        ),
        content = content,
    )
}
