package com.adfree.player.ui.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.adfree.player.NavyHigh
import com.adfree.player.NavySurface
import com.adfree.player.data.ResumeStore
import com.adfree.player.data.Video
import com.adfree.player.data.VideoRepository
import com.adfree.player.data.formatSize
import com.adfree.player.data.formatTime
import java.text.DateFormat
import java.util.Date

enum class SortBy(val label: String) { DATE("Newest"), NAME("Name"), SIZE("Size") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (videos: List<Video>, index: Int) -> Unit) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    fun hasPermission() =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(hasPermission()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    var videos by remember { mutableStateOf<List<Video>?>(null) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(SortBy.DATE) }
    var sortMenu by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val resume = remember { ResumeStore(context) }

    // Re-scan every time the screen comes back (new downloads, updated resume bars).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = hasPermission()
        refreshTick++
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(permission) }
    LaunchedEffect(granted, refreshTick) {
        if (granted) videos = VideoRepository.load(context)
    }
    BackHandler(enabled = folder != null) { folder = null }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NavySurface),
                title = { Text(folder ?: "Folders", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (folder != null) {
                        IconButton(onClick = { folder = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, "Sort")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            SortBy.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    trailingIcon = {
                                        if (option == sort) Icon(Icons.Default.Check, null)
                                    },
                                    onClick = { sort = option; sortMenu = false },
                                )
                            }
                        }
                    }
                },
            )
        }
    ) { padding ->
        val list = videos
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !granted -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Allow access to your videos to see them here.")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { launcher.launch(permission) }) { Text("Allow access") }
                }

                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                list.isEmpty() -> Text("No videos found", Modifier.align(Alignment.Center))

                folder == null -> {
                    val folders = list.groupBy { it.folder }.toList().let { groups ->
                        when (sort) {
                            SortBy.NAME -> groups.sortedBy { it.first.lowercase() }
                            SortBy.DATE -> groups.sortedByDescending { g -> g.second.maxOf { it.dateAdded } }
                            SortBy.SIZE -> groups.sortedByDescending { g -> g.second.sumOf { it.size } }
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(folders, key = { it.first }) { (name, vids) ->
                            FolderRow(name, vids.size) { folder = name }
                        }
                    }
                }

                else -> {
                    val inFolder = list.filter { it.folder == folder }.let { v ->
                        when (sort) {
                            SortBy.NAME -> v.sortedBy { it.name.lowercase() }
                            SortBy.DATE -> v.sortedByDescending { it.dateAdded }
                            SortBy.SIZE -> v.sortedByDescending { it.size }
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(inFolder, key = { _, v -> v.id }) { i, video ->
                            VideoRow(
                                video = video,
                                progress = remember(video.id, refreshTick) {
                                    val pos = resume.get(video.uri.toString())
                                    if (pos > 0 && video.durationMs > 0) pos.toFloat() / video.durationMs else 0f
                                },
                                onClick = { onOpen(inFolder, i) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(name: String, count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Folder, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count video${if (count == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VideoRow(video: Video, progress: Float, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 128.dp, height = 72.dp).clip(RoundedCornerShape(6.dp))) {
            VideoThumb(video)
            Text(
                formatTime(video.durationMs),
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    trackColor = Color(0x66000000),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(video.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${formatSize(video.size)} • ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(video.dateAdded * 1000))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VideoThumb(video: Video) {
    val context = LocalContext.current
    val bitmap by produceState(VideoRepository.cachedThumb(video.id), video.id) {
        if (value == null) value = VideoRepository.thumbnail(context, video)
    }
    Box(Modifier.fillMaxSize().background(NavyHigh)) {
        bitmap?.let {
            Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
