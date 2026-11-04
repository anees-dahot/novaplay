package com.adfree.player.ui.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adfree.player.Accent
import com.adfree.player.NavyHigh
import com.adfree.player.NavySurface
import com.adfree.player.data.LibraryStore
import com.adfree.player.data.ResumeStore
import com.adfree.player.data.Video
import com.adfree.player.data.VideoInfo
import com.adfree.player.data.VideoRepository
import com.adfree.player.data.formatSize
import com.adfree.player.data.formatTime
import com.adfree.player.ui.AppSheet
import com.adfree.player.ui.CardDivider
import com.adfree.player.ui.CardGroup
import com.adfree.player.ui.Destructive
import com.adfree.player.ui.Motion
import com.adfree.player.ui.SecondaryText
import com.adfree.player.ui.SectionLabel
import com.adfree.player.ui.SheetAction
import com.adfree.player.ui.SheetHeader
import com.adfree.player.ui.Type
import com.adfree.player.ui.pressable
import java.text.DateFormat
import java.util.Date

private val Separator = Color(0x1FFFFFFF)
private const val WEEK_SECONDS = 7 * 24 * 3600L

private fun groupShape(i: Int, count: Int) = when {
    count == 1 -> RoundedCornerShape(14.dp)
    i == 0 -> RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
    i == count - 1 -> RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
    else -> RoundedCornerShape(0.dp)
}

/** Badges shared by rows and tiles. */
private data class Marks(val progress: Float, val isNew: Boolean, val watched: Boolean, val favourite: Boolean)

@Composable
private fun rememberMarks(): (Video) -> Marks {
    val context = LocalContext.current
    val resume = remember { ResumeStore(context) }
    val v = LibraryStore.version
    val played = remember(v) { LibraryStore.played }
    val watched = remember(v) { LibraryStore.watched }
    val favs = remember(v) { LibraryStore.favourites }
    val now = System.currentTimeMillis() / 1000
    return { video ->
        val key = video.uri.toString()
        val pos = resume.get(key)
        Marks(
            progress = if (pos > 0 && video.durationMs > 0) pos.toFloat() / video.durationMs else 0f,
            isNew = key !in played && now - video.dateAdded < WEEK_SECONDS,
            watched = key in watched,
            favourite = key in favs,
        )
    }
}

// ───────────────────────── Home ─────────────────────────

@Composable
fun HomeContent(
    visible: List<Video>,
    sort: SortBy,
    query: String,
    onQuery: (String) -> Unit,
    grid: Boolean,
    onVideo: (List<Video>, Int) -> Unit,
    onVideoLong: (Video) -> Unit,
    onFolder: (String) -> Unit,
    onFolderLong: (String) -> Unit,
    onFavourites: () -> Unit,
    onPlaylists: () -> Unit,
    onPrivate: () -> Unit,
    onHidden: () -> Unit,
) {
    val context = LocalContext.current
    val resume = remember { ResumeStore(context) }
    val marks = rememberMarks()
    val v = LibraryStore.version
    val byUri = remember(visible) { visible.associateBy { it.uri.toString() } }
    val recent = remember(v, visible) { LibraryStore.recent.mapNotNull { byUri[it] } }
    val continueWatching = remember(recent, v) { recent.filter { resume.get(it.uri.toString()) > 0 }.take(12) }
    val recentlyPlayed = remember(recent) { recent.take(15) }
    val folders = remember(visible, sort) {
        visible.groupBy { it.folder }.toList().let { g ->
            when (sort) {
                SortBy.NAME -> g.sortedBy { it.first.lowercase() }
                SortBy.DATE -> g.sortedByDescending { x -> x.second.maxOf { it.dateAdded } }
                SortBy.SIZE -> g.sortedByDescending { x -> x.second.sumOf { it.size } }
            }
        }
    }
    val results = remember(query, visible) {
        if (query.isBlank()) emptyList()
        else visible.filter { it.name.contains(query.trim(), ignoreCase = true) }.sortedBy { it.name.lowercase() }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { SearchField(query, onQuery, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

        if (query.isNotBlank()) {
            if (results.isEmpty()) item {
                Text("No results for “$query”", style = Type.subhead, color = SecondaryText,
                    modifier = Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center)
            }
            groupedVideos(results, marks, selecting = false, selected = emptySet(),
                onClick = { onVideo(results, it) }, onLong = { onVideoLong(results[it]) })
            return@LazyColumn
        }

        if (continueWatching.isNotEmpty()) {
            item { SectionLabel("Continue Watching") }
            item { ShelfRow(continueWatching, marks, onClick = { onVideo(continueWatching, it) }, onLong = { onVideoLong(continueWatching[it]) }) }
        }
        if (recentlyPlayed.isNotEmpty()) {
            item { SectionLabel("Recently Played") }
            item { ShelfRow(recentlyPlayed, marks, small = true, onClick = { onVideo(recentlyPlayed, it) }, onLong = { onVideoLong(recentlyPlayed[it]) }) }
        }

        item { SectionLabel("Collections") }
        item {
            val favCount = remember(v, visible) { visible.count { it.uri.toString() in LibraryStore.favourites } }
            val plCount = remember(v) { LibraryStore.playlists.size }
            val hidden = remember(v) { LibraryStore.hiddenFolders }
            CardGroup(Modifier.padding(horizontal = 16.dp)) {
                NavRow(Icons.Default.Favorite, Color(0xFFFF375F), "Favourites", "$favCount", onFavourites)
                CardDivider(62)
                NavRow(Icons.AutoMirrored.Filled.PlaylistPlay, Color(0xFFBF5AF2), "Playlists", "$plCount", onPlaylists)
                CardDivider(62)
                NavRow(Icons.Default.Lock, Color(0xFF8E8E93), "Private", "", onPrivate)
                if (hidden.isNotEmpty()) {
                    CardDivider(62)
                    NavRow(Icons.Default.VisibilityOff, Color(0xFF636366), "Hidden Folders", "${hidden.size}", onHidden)
                }
            }
        }

        item { SectionLabel("Folders") }
        itemsIndexed(folders, key = { _, f -> "f:" + f.first }) { i, (name, vids) ->
            Box(
                Modifier.padding(horizontal = 16.dp).animateItem().clip(groupShape(i, folders.size)).background(NavySurface),
            ) {
                Column(Modifier.pressable(pressedScale = 0.98f, onLongClick = { onFolderLong(name) }) { onFolder(name) }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.Default.Folder, Brush.verticalGradient(listOf(Color(0xFF5AA2FF), Color(0xFF2F6FEB))))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val newCount = vids.count { marks(it).isNew }
                            Text(
                                "${vids.size} video${if (vids.size == 1) "" else "s"}" + if (newCount > 0) " · $newCount new" else "",
                                style = Type.footnote, color = SecondaryText,
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color(0x55FFFFFF))
                    }
                    if (i < folders.lastIndex) CardDividerLine(64)
                }
            }
        }
    }
}

@Composable
private fun CardDividerLine(start: Int) = androidx.compose.material3.HorizontalDivider(
    Modifier.padding(start = start.dp), thickness = 0.5.dp, color = Separator,
)

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(11.dp)).background(Color(0x1FFFFFFF)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, tint = SecondaryText, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search videos", style = Type.body, color = SecondaryText)
            BasicTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                textStyle = Type.body.copy(color = Color.White), cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Icon(Icons.Default.Close, "Clear", tint = SecondaryText,
                modifier = Modifier.size(20.dp).pressable { onQuery("") })
        }
    }
}

@Composable
private fun IconTile(icon: ImageVector, brush: Brush) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(brush), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun NavRow(icon: ImageVector, color: Color, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(pressedScale = 0.98f, onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, SolidColor(color))
        Spacer(Modifier.width(14.dp))
        Text(title, style = Type.body, modifier = Modifier.weight(1f))
        Text(detail, style = Type.body, color = SecondaryText)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color(0x55FFFFFF))
    }
}

/** Horizontal shelf of large cards (Continue Watching / Recently Played). */
@Composable
private fun ShelfRow(
    videos: List<Video>, marks: (Video) -> Marks, small: Boolean = false,
    onClick: (Int) -> Unit, onLong: (Int) -> Unit,
) {
    val w = if (small) 150.dp else 220.dp
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(videos, key = { _, v -> v.id }) { i, v ->
            Column(Modifier.width(w).pressable(pressedScale = 0.96f, onLongClick = { onLong(i) }) { onClick(i) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
                    Thumb(v)
                    val m = marks(v)
                    if (m.progress > 0f) ProgressBar(m.progress, Modifier.align(Alignment.BottomStart))
                    if (!small) {
                        Box(
                            Modifier.align(Alignment.Center).size(40.dp).clip(CircleShape).background(Color(0x8C0B1220)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.PlayArrow, null, tint = Color.White) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(v.name, style = Type.footnote, maxLines = if (small) 1 else 2, overflow = TextOverflow.Ellipsis)
                if (!small) {
                    val left = v.durationMs - (marks(v).progress * v.durationMs).toLong()
                    Text("${formatTime(left)} left", style = Type.caption, color = SecondaryText)
                }
            }
        }
    }
}

// ───────────────────────── Video lists ─────────────────────────

private fun LazyListScope.groupedVideos(
    videos: List<Video>, marks: (Video) -> Marks, selecting: Boolean, selected: Set<Long>,
    onClick: (Int) -> Unit, onLong: (Int) -> Unit,
) {
    itemsIndexed(videos, key = { _, v -> v.id }) { i, v ->
        Box(Modifier.padding(horizontal = 16.dp).animateItem().clip(groupShape(i, videos.size)).background(NavySurface)) {
            VideoRow(v, marks(v), selecting, v.id in selected, showDivider = i < videos.lastIndex,
                onClick = { onClick(i) }, onLong = { onLong(i) })
        }
    }
}

@Composable
fun VideoCollection(
    videos: List<Video>, grid: Boolean, selecting: Boolean, selected: Set<Long>, emptyText: String,
    onClick: (Int) -> Unit, onLong: (Int) -> Unit,
) {
    val marks = rememberMarks()
    if (videos.isEmpty()) {
        EmptyState("Nothing Here", emptyText)
        return
    }
    if (grid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(videos, key = { _, v -> v.id }) { i, v ->
                VideoTile(v, marks(v), selecting, v.id in selected, Modifier.animateItem(), { onClick(i) }, { onLong(i) })
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
            groupedVideos(videos, marks, selecting, selected, onClick, onLong)
        }
    }
}

@Composable
private fun VideoRow(
    video: Video, m: Marks, selecting: Boolean, isSelected: Boolean, showDivider: Boolean,
    onClick: () -> Unit, onLong: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().pressable(pressedScale = 0.98f, onLongClick = onLong, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) { SelectMark(isSelected); Spacer(Modifier.width(10.dp)) }
            Box(Modifier.size(width = 120.dp, height = 68.dp).clip(RoundedCornerShape(10.dp))) {
                Thumb(video)
                DurationBadge(video, Modifier.align(Alignment.BottomEnd))
                if (m.progress > 0f) ProgressBar(m.progress, Modifier.align(Alignment.BottomStart))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(video.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = Type.subhead,
                    color = if (m.watched) Color(0xFFB8C2D6) else Color.White)
                MetaLine(video, m)
            }
        }
        if (showDivider) CardDividerLine(if (selecting) 178 else 144)
    }
}

@Composable
private fun VideoTile(
    video: Video, m: Marks, selecting: Boolean, isSelected: Boolean, modifier: Modifier,
    onClick: () -> Unit, onLong: () -> Unit,
) {
    Column(modifier.pressable(pressedScale = 0.96f, onLongClick = onLong, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            Thumb(video)
            DurationBadge(video, Modifier.align(Alignment.BottomEnd))
            if (m.progress > 0f) ProgressBar(m.progress, Modifier.align(Alignment.BottomStart))
            if (selecting) Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { SelectMark(isSelected) }
        }
        Spacer(Modifier.height(6.dp))
        Text(video.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = Type.footnote)
        MetaLine(video, m)
    }
}

@Composable
private fun MetaLine(video: Video, m: Marks) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (m.isNew) {
            Text("NEW", style = Type.caption, color = Color.White,
                modifier = Modifier.background(Accent, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp))
            Spacer(Modifier.width(6.dp))
        }
        if (m.watched) {
            Icon(Icons.Default.TaskAlt, "Watched", tint = Color(0xFF30D158), modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        if (m.favourite) {
            Icon(Icons.Default.Favorite, "Favourite", tint = Color(0xFFFF375F), modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            "${formatSize(video.size)} · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(video.dateAdded * 1000))}",
            style = Type.footnote, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SelectMark(selected: Boolean) {
    val scale by animateFloatAsState(if (selected) 1f else 0.9f, Motion.bouncy(), label = "sel")
    Icon(
        if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
        if (selected) "Selected" else "Not selected",
        tint = if (selected) Accent else Color(0x99FFFFFF),
        modifier = Modifier.size((24 * scale).dp).background(if (selected) Color.White else Color.Transparent, CircleShape),
    )
}

@Composable
private fun DurationBadge(video: Video, modifier: Modifier) {
    Text(
        formatTime(video.durationMs), color = Color.White, style = Type.caption,
        modifier = modifier.padding(5.dp).background(Color(0x99000000), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun ProgressBar(progress: Float, modifier: Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Color(0x66000000))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Accent))
    }
}

@Composable
private fun Thumb(video: Video) {
    val context = LocalContext.current
    val bitmap by produceState(VideoRepository.cachedThumb(video.id), video.id) {
        if (value == null) value = VideoRepository.thumbnail(context, video)
    }
    val alpha by animateFloatAsState(if (bitmap != null) 1f else 0f, Motion.smooth(), label = "thumb")
    Box(Modifier.fillMaxSize().background(NavyHigh)) {
        bitmap?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, alpha = alpha, modifier = Modifier.fillMaxSize()) }
    }
}

// ───────────────────────── Playlists / hidden ─────────────────────────

@Composable
fun PlaylistsContent(onOpen: (String) -> Unit, onLong: (String) -> Unit, onNew: () -> Unit) {
    val lists = remember(LibraryStore.version) { LibraryStore.playlists.toList() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            CardGroup { SheetAction("New Playlist", Icons.Default.Add, Accent, onNew) }
            Spacer(Modifier.height(20.dp))
        }
        itemsIndexed(lists, key = { _, p -> p.first }) { i, (name, uris) ->
            Box(Modifier.animateItem().clip(groupShape(i, lists.size)).background(NavySurface)) {
                Column(Modifier.pressable(pressedScale = 0.98f, onLongClick = { onLong(name) }) { onOpen(name) }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.AutoMirrored.Filled.PlaylistPlay, SolidColor(Color(0xFFBF5AF2)))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${uris.size} video${if (uris.size == 1) "" else "s"}", style = Type.footnote, color = SecondaryText)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color(0x55FFFFFF))
                    }
                    if (i < lists.lastIndex) CardDividerLine(64)
                }
            }
        }
    }
}

@Composable
fun HiddenContent(onUnhide: (String) -> Unit) {
    val hidden = remember(LibraryStore.version) { LibraryStore.hiddenFolders.sorted() }
    if (hidden.isEmpty()) { EmptyState("No Hidden Folders", "Long-press a folder and choose Hide Folder."); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            CardGroup {
                hidden.forEachIndexed { i, f ->
                    SheetAction("Show “$f”", Icons.Default.Visibility) { onUnhide(f) }
                    if (i < hidden.lastIndex) CardDivider()
                }
            }
        }
    }
}

// ───────────────────────── Sheets ─────────────────────────

@Composable
fun VideoMenu(
    video: Video, route: String, onDismiss: () -> Unit,
    onPlay: () -> Unit, onSelect: () -> Unit, onAddToPlaylist: () -> Unit, onPrivate: (Boolean) -> Unit,
    onRename: () -> Unit, onShare: () -> Unit, onInfo: () -> Unit, onDelete: () -> Unit,
) {
    val key = video.uri.toString()
    val v = LibraryStore.version
    val fav = remember(v) { key in LibraryStore.favourites }
    val watched = remember(v) { key in LibraryStore.watched }
    val isPrivate = remember(v) { key in LibraryStore.privateVideos }
    AppSheet(onDismiss) {
        Row(Modifier.padding(start = 4.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(8.dp))) { Thumb(video) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(video.name, style = Type.headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${formatTime(video.durationMs)} · ${formatSize(video.size)}", style = Type.footnote, color = SecondaryText)
            }
        }
        CardGroup {
            SheetAction("Play", Icons.Default.PlayArrow, onClick = onPlay)
            CardDivider()
            SheetAction("Select", Icons.Default.CheckCircle, onClick = onSelect)
        }
        Spacer(Modifier.height(16.dp))
        CardGroup {
            SheetAction(if (fav) "Remove from Favourites" else "Favourite", if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder) {
                LibraryStore.setFavourite(listOf(key), !fav); onDismiss()
            }
            CardDivider()
            SheetAction("Add to Playlist…", Icons.AutoMirrored.Filled.PlaylistAdd, onClick = onAddToPlaylist)
            if (route.startsWith("playlist:")) {
                CardDivider()
                SheetAction("Remove from Playlist", Icons.Default.Close) {
                    LibraryStore.removeFromPlaylist(route.substringAfter(':'), listOf(key)); onDismiss()
                }
            }
            CardDivider()
            SheetAction(if (watched) "Mark as Unwatched" else "Mark as Watched", Icons.Default.TaskAlt) {
                LibraryStore.setWatched(listOf(key), !watched); onDismiss()
            }
            CardDivider()
            SheetAction(if (isPrivate) "Remove from Private" else "Move to Private", if (isPrivate) Icons.Default.LockOpen else Icons.Default.Lock) {
                onPrivate(!isPrivate)
            }
        }
        Spacer(Modifier.height(16.dp))
        CardGroup {
            SheetAction("Rename", Icons.Default.Edit, onClick = onRename)
            CardDivider()
            SheetAction("Share", Icons.Default.Share, onClick = onShare)
            CardDivider()
            SheetAction("Info", Icons.Default.Info, onClick = onInfo)
        }
        Spacer(Modifier.height(16.dp))
        CardGroup { SheetAction("Delete", Icons.Default.Delete, Destructive, onDelete) }
    }
}

@Composable
fun FolderMenu(folder: String, onDismiss: () -> Unit, onPlayAll: () -> Unit, onHide: () -> Unit) {
    AppSheet(onDismiss) {
        SheetHeader(folder)
        CardGroup {
            SheetAction("Play All", Icons.Default.PlayArrow, onClick = onPlayAll)
            CardDivider()
            SheetAction("Hide Folder", Icons.Default.VisibilityOff, onClick = onHide)
        }
        Text("Hidden folders can be shown again from Library › Hidden Folders.",
            style = Type.footnote, color = SecondaryText, modifier = Modifier.padding(16.dp, 6.dp))
    }
}

@Composable
fun PlaylistMenu(name: String, onDismiss: () -> Unit, onPlay: () -> Unit, onDelete: () -> Unit) {
    AppSheet(onDismiss) {
        SheetHeader(name)
        CardGroup { SheetAction("Play", Icons.Default.PlayArrow, onClick = onPlay) }
        Spacer(Modifier.height(16.dp))
        CardGroup { SheetAction("Delete Playlist", Icons.Default.Delete, Destructive, onDelete) }
    }
}

@Composable
fun PlaylistPicker(onDismiss: () -> Unit, onPick: (String) -> Unit, onNew: () -> Unit) {
    val lists = remember(LibraryStore.version) { LibraryStore.playlists.keys.toList() }
    AppSheet(onDismiss) {
        SheetHeader("Add to Playlist")
        CardGroup { SheetAction("New Playlist…", Icons.Default.Add, Accent, onNew) }
        if (lists.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            CardGroup {
                lists.forEachIndexed { i, n ->
                    SheetAction(n, Icons.AutoMirrored.Filled.PlaylistPlay) { onPick(n) }
                    if (i < lists.lastIndex) CardDivider()
                }
            }
        }
    }
}

@Composable
fun InfoSheet(video: Video, info: VideoInfo?, onDismiss: () -> Unit) {
    AppSheet(onDismiss) {
        SheetHeader("Info")
        if (info == null) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent)
            }
        } else CardGroup {
            info.rows.forEachIndexed { i, (k, v) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(k, style = Type.body, color = SecondaryText, modifier = Modifier.width(120.dp))
                    Text(v, style = Type.body, color = Color.White, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
                if (i < info.rows.lastIndex) CardDivider()
            }
        }
    }
}

@Composable
fun ConfirmDeleteSheet(count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AppSheet(onDismiss) {
        Text(
            if (count == 1) "This video will be deleted from your phone." else "These $count videos will be deleted from your phone.",
            style = Type.subhead, color = SecondaryText, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        )
        Spacer(Modifier.height(8.dp))
        CardGroup { SheetAction(if (count == 1) "Delete Video" else "Delete $count Videos", null, Destructive, onConfirm) }
        Spacer(Modifier.height(10.dp))
        CardGroup { SheetAction("Cancel", null, Accent, onDismiss) }
    }
}

@Composable
fun EmptyState(title: String, message: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.VideoLibrary, null, tint = SecondaryText, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = Type.title, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, style = Type.subhead, color = SecondaryText, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            Text(
                action, style = Type.headline, color = Color.White,
                modifier = Modifier.pressable(onClick = onAction).background(Accent, RoundedCornerShape(50))
                    .border(0.5.dp, Color(0x33FFFFFF), RoundedCornerShape(50)).padding(horizontal = 28.dp, vertical = 12.dp),
            )
        }
    }
}
