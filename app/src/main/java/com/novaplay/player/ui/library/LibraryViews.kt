package com.novaplay.player.ui.library

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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Movie
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.novaplay.player.Accent
import com.novaplay.player.NavyHigh
import com.novaplay.player.NavySurface
import com.novaplay.player.data.LibraryStore
import com.novaplay.player.data.ResumeStore
import com.novaplay.player.data.Video
import com.novaplay.player.data.VideoInfo
import com.novaplay.player.data.VideoRepository
import com.novaplay.player.data.formatSize
import com.novaplay.player.data.formatTime
import com.novaplay.player.ui.AppSheet
import com.novaplay.player.ui.CardDivider
import com.novaplay.player.ui.CardGroup
import com.novaplay.player.ui.Destructive
import com.novaplay.player.ui.Motion
import com.novaplay.player.ui.SecondaryText
import com.novaplay.player.ui.SheetAction
import com.novaplay.player.ui.SheetHeader
import com.novaplay.player.ui.Type
import com.novaplay.player.ui.pressable
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
    onHistory: () -> Unit,
    onNetwork: () -> Unit,
    onPlayFolder: (String) -> Unit,
) {
    val context = LocalContext.current
    val resume = remember { ResumeStore(context) }
    val marks = rememberMarks()
    val v = LibraryStore.version
    val byUri = remember(visible) { visible.associateBy { it.uri.toString() } }
    // One shelf: everything you opened recently, newest first. Finished videos drop off.
    val continueWatching = remember(v, visible) {
        val watched = LibraryStore.watched
        LibraryStore.recent.mapNotNull { byUri[it] }
            .filter { it.uri.toString() !in watched || resume.get(it.uri.toString()) > 0 }
            .take(15)
    }
    val folders = remember(visible, sort) {
        visible.groupBy { it.folder }.toList().let { g ->
            when (sort) {
                SortBy.NAME -> g.sortedWith(compareBy(NaturalOrder) { it.first })
                SortBy.DATE -> g.sortedByDescending { x -> x.second.maxOf { it.dateAdded } }
                SortBy.SIZE -> g.sortedByDescending { x -> x.second.sumOf { it.size } }
                SortBy.LENGTH -> g.sortedByDescending { x -> x.second.sumOf { it.durationMs } }
            }
        }
    }
    var filter by remember { mutableStateOf(SearchFilter.ALL) }
    val results = remember(query, visible, filter, v) {
        if (query.isBlank()) emptyList()
        else {
            val q = query.trim()
            val watched = LibraryStore.watched
            visible.filter { it.name.contains(q, ignoreCase = true) || it.folder.contains(q, ignoreCase = true) }
                .filter {
                    when (filter) {
                        SearchFilter.ALL -> true
                        SearchFilter.UNWATCHED -> it.uri.toString() !in watched
                        SearchFilter.IN_PROGRESS -> resume.get(it.uri.toString()) > 0
                        SearchFilter.HD -> it.quality != null
                        SearchFilter.LONG -> it.durationMs >= 30 * 60_000
                        SearchFilter.SHORT -> it.durationMs in 1 until 5 * 60_000
                    }
                }
                .sortedBy { it.name.lowercase() }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { SearchField(query, onQuery, Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp)) }

        if (query.isNotBlank()) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(SearchFilter.entries.size) { i ->
                        val f = SearchFilter.entries[i]
                        FilterPill(f.label, f == filter) { filter = f }
                    }
                }
            }
            if (results.isEmpty()) item {
                Text("No results for “$query”", style = Type.subhead, color = SecondaryText,
                    modifier = Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center)
            }
            item { Spacer(Modifier.height(8.dp)) }
            groupedVideos(results, marks, selecting = false, selected = emptySet(),
                onClick = { onVideo(results, it) }, onLong = { onVideoLong(results[it]) })
            return@LazyColumn
        }

        item {
            val favCount = remember(v, visible) { visible.count { it.uri.toString() in LibraryStore.favourites } }
            val plCount = remember(v) { LibraryStore.playlists.size }
            val hidden = remember(v) { LibraryStore.hiddenFolders }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Chip(Icons.Default.Favorite, "Favourites", favCount.takeIf { it > 0 }, onFavourites) }
                item { Chip(Icons.AutoMirrored.Filled.PlaylistPlay, "Playlists", plCount.takeIf { it > 0 }, onPlaylists) }
                item { Chip(Icons.Default.History, "History", null, onHistory) }
                item { Chip(Icons.Default.Language, "Network", null, onNetwork) }
                item { Chip(Icons.Default.Lock, "Private", null, onPrivate) }
                if (hidden.isNotEmpty()) item { Chip(Icons.Default.VisibilityOff, "Hidden", hidden.size, onHidden) }
            }
        }

        if (continueWatching.isNotEmpty()) {
            item {
                Header("Continue watching", action = "Clear") { LibraryStore.clearRecent() }
            }
            item {
                ShelfRow(continueWatching, marks,
                    onClick = { onVideo(continueWatching, it) }, onLong = { onVideoLong(continueWatching[it]) })
            }
        }

        item { Header("Folders", detail = "${folders.size}") }
        if (folders.isEmpty() && query.isBlank()) item {
            EmptyState("No Videos Yet", "Videos saved on this phone show up here. Pull down to scan again.")
        }
        if (grid) {
            val rows = folders.chunked(2)
            itemsIndexed(rows, key = { _, r -> "fr:" + r.first().first }) { _, row ->
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp).animateItem(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { (name, vids) ->
                        FolderTile(name, vids, marks, Modifier.weight(1f),
                            onClick = { onFolder(name) }, onLong = { onFolderLong(name) })
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            itemsIndexed(folders, key = { _, f -> "f:" + f.first }) { _, (name, vids) ->
                FolderRow(name, vids, marks, Modifier.animateItem(),
                    onClick = { onFolder(name) }, onLong = { onFolderLong(name) }, onPlay = { onPlayFolder(name) })
            }
        }
    }
}

enum class SearchFilter(val label: String) {
    ALL("All"), UNWATCHED("Unwatched"), IN_PROGRESS("In progress"), HD("HD+"), LONG("30 min+"), SHORT("Under 5 min")
}

@Composable
private fun FilterPill(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label, style = Type.footnote, color = if (on) Color.Black else Color.White,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Color.White else NavySurface)
            .pressable(pressedScale = 0.95f, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Section title, sentence case, with optional count or trailing text action. */
@Composable
private fun Header(title: String, detail: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Type.headline, color = Color.White)
        if (detail != null) {
            Spacer(Modifier.width(8.dp))
            Text(detail, style = Type.subhead, color = SecondaryText)
        }
        Spacer(Modifier.weight(1f))
        if (action != null) {
            Text(action, style = Type.subhead, color = SecondaryText,
                modifier = Modifier.pressable(onClick = onAction).padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun Chip(icon: ImageVector, label: String, count: Int?, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(NavySurface)
            .border(0.5.dp, Color(0x14FFFFFF), RoundedCornerShape(50))
            .pressable(pressedScale = 0.95f, onClick = onClick)
            .padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color(0xCCFFFFFF), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = Type.footnote.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = Color.White)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text("$count", style = Type.footnote, color = SecondaryText)
        }
    }
}

private fun folderSubtitle(vids: List<Video>, newCount: Int): String {
    val total = vids.sumOf { it.durationMs }
    return "${vids.size} video${if (vids.size == 1) "" else "s"} · ${formatSize(vids.sumOf { it.size })}" +
        (if (total > 0) " · ${formatTime(total)}" else "") + if (newCount > 0) " · $newCount new" else ""
}

/** Flat folder glyph (tab + body), like classic file-manager / MX-style players. */
@Composable
fun FolderGlyph(modifier: Modifier, count: Int? = null, hasNew: Boolean = false) {
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            val r = w * 0.07f
            val tabW = w * 0.42f; val tabH = h * 0.16f; val top = h * 0.08f
            // Back panel + tab (darker)
            val back = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, top + r)
                quadraticTo(0f, top, r, top)
                lineTo(tabW - r, top)
                quadraticTo(tabW, top, tabW + r * 0.8f, top + tabH * 0.7f)
                lineTo(tabW + r * 1.4f, top + tabH)
                lineTo(w - r, top + tabH)
                quadraticTo(w, top + tabH, w, top + tabH + r)
                lineTo(w, h - r)
                quadraticTo(w, h, w - r, h)
                lineTo(r, h)
                quadraticTo(0f, h, 0f, h - r)
                close()
            }
            drawPath(back, Color(0xFFE08A00))
            // Front body (lighter, gradient)
            val fTop = top + tabH * 1.9f
            drawRoundRect(
                brush = Brush.verticalGradient(listOf(Color(0xFFFFC23D), Color(0xFFFFA415)), startY = fTop, endY = h),
                topLeft = androidx.compose.ui.geometry.Offset(0f, fTop),
                size = androidx.compose.ui.geometry.Size(w, h - fTop),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            )
            // Little sheen line along the top of the body
            drawRect(Color(0x33FFFFFF), androidx.compose.ui.geometry.Offset(r, fTop + h * 0.02f),
                androidx.compose.ui.geometry.Size(w - 2 * r, h * 0.025f))
        }
        Box(Modifier.fillMaxSize().padding(top = 0.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize(0.62f).padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, null, tint = Color(0x8C7A4300), modifier = Modifier.fillMaxSize(0.62f))
            }
        }
        if (count != null) Text(
            "$count", style = Type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                .background(Color(0x66000000), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp),
        )
        if (hasNew) NewDot(Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp))
    }
}

/** Folder as a tile: folder icon, name and count. */
@Composable
private fun FolderTile(
    name: String, vids: List<Video>, marks: (Video) -> Marks, modifier: Modifier,
    onClick: () -> Unit, onLong: () -> Unit,
) {
    val newCount = vids.count { marks(it).isNew }
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(NavySurface)
            .pressable(pressedScale = 0.97f, onLongClick = onLong, onClick = onClick).padding(12.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f), contentAlignment = Alignment.Center) {
            FolderGlyph(Modifier.fillMaxHeight(0.86f).aspectRatio(1.25f), count = vids.size, hasNew = newCount > 0)
        }
        Spacer(Modifier.height(8.dp))
        Text(name, style = Type.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${vids.size} video${if (vids.size == 1) "" else "s"} · ${formatSize(vids.sumOf { it.size })}" +
                if (newCount > 0) " · $newCount new" else "",
            style = Type.caption, color = if (newCount > 0) Accent else SecondaryText, maxLines = 1,
        )
    }
}

@Composable
private fun FolderRow(
    name: String, vids: List<Video>, marks: (Video) -> Marks, modifier: Modifier,
    onClick: () -> Unit, onLong: () -> Unit, onPlay: () -> Unit,
) {
    val newCount = vids.count { marks(it).isNew }
    Row(
        modifier.fillMaxWidth().pressable(pressedScale = 0.98f, onLongClick = onLong, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolderGlyph(Modifier.size(width = 60.dp, height = 48.dp), hasNew = newCount > 0)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(folderSubtitle(vids, newCount), style = Type.footnote, color = SecondaryText,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(40.dp).pressable(pressedScale = 0.85f, onClick = onPlay), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayArrow, "Play folder", tint = Color(0x99FFFFFF), modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun NewDot(modifier: Modifier = Modifier) =
    Box(modifier.size(8.dp).clip(CircleShape).background(Accent))

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

/** Horizontal shelf of cards (Continue watching). */
@Composable
private fun ShelfRow(
    videos: List<Video>, marks: (Video) -> Marks,
    onClick: (Int) -> Unit, onLong: (Int) -> Unit,
) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(videos, key = { _, v -> v.id }) { i, v ->
            val m = marks(v)
            Column(Modifier.width(184.dp).pressable(pressedScale = 0.96f, onLongClick = { onLong(i) }) { onClick(i) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
                    Thumb(v)
                    if (m.progress > 0f) ProgressBar(m.progress, Modifier.align(Alignment.BottomStart))
                    else DurationBadge(v, Modifier.align(Alignment.BottomEnd))
                }
                Spacer(Modifier.height(6.dp))
                Text(v.name.substringBeforeLast('.'), style = Type.footnote, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = if (m.progress > 0f) "${formatTime(v.durationMs - (m.progress * v.durationMs).toLong())} left"
                else v.folder
                Text(sub, style = Type.caption, color = SecondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Box(Modifier.animateItem()) {
            VideoRow(v, marks(v), selecting, v.id in selected, showDivider = false,
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) { SelectMark(isSelected); Spacer(Modifier.width(12.dp)) }
            Box(Modifier.size(width = 128.dp, height = 72.dp).clip(RoundedCornerShape(8.dp))) {
                Thumb(video)
                DurationBadge(video, Modifier.align(Alignment.BottomEnd))
                if (m.progress > 0f) ProgressBar(m.progress, Modifier.align(Alignment.BottomStart))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(video.name.substringBeforeLast('.'), maxLines = 2, overflow = TextOverflow.Ellipsis, style = Type.subhead,
                    color = if (m.watched) Color(0xFF9A9AA2) else Color.White)
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
            NewDot()
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
        video.quality?.let { q ->
            Text(
                q, style = Type.caption, color = Color(0xDDFFFFFF),
                modifier = Modifier.border(0.5.dp, Color(0x55FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
            )
            Spacer(Modifier.width(6.dp))
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
    Box(modifier.fillMaxWidth().height(3.dp).background(Color(0x59FFFFFF))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(Color(0xFFFF453A)))
    }
}

@Composable
private fun Thumb(video: Video, fallbacks: List<Video> = emptyList()) {
    val context = LocalContext.current
    val bitmap by produceState(VideoRepository.cachedThumb(video.id), video.id, fallbacks.size) {
        if (value == null) value = VideoRepository.thumbnail(context, video)
        // Folder covers: if the newest video can't be decoded, try the next ones.
        for (v in fallbacks) { if (value != null) break; value = VideoRepository.thumbnail(context, v) }
    }
    val alpha by animateFloatAsState(if (bitmap != null) 1f else 0f, Motion.smooth(), label = "thumb")
    Box(Modifier.fillMaxSize().background(NavyHigh), contentAlignment = Alignment.Center) {
        if (bitmap == null) Icon(Icons.Default.Movie, null, tint = Color(0x33FFFFFF), modifier = Modifier.size(22.dp))
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
                        IconTile(Icons.AutoMirrored.Filled.PlaylistPlay, SolidColor(NavyHigh))
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
    onCopy: () -> Unit, onMove: () -> Unit,
    onRemoveRecent: (() -> Unit)? = null,
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
            if (onRemoveRecent != null) {
                CardDivider()
                SheetAction("Remove from Continue watching", Icons.Default.Close, onClick = onRemoveRecent)
            }
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
            SheetAction("Copy to…", Icons.Default.ContentCopy, onClick = onCopy)
            CardDivider()
            SheetAction("Move to…", Icons.AutoMirrored.Filled.DriveFileMove, onClick = onMove)
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
fun FolderMenu(
    folder: String, onDismiss: () -> Unit, onPlayAll: () -> Unit, onShuffle: () -> Unit, onHide: () -> Unit,
    onCopy: () -> Unit, onMove: () -> Unit, onRename: () -> Unit,
) {
    AppSheet(onDismiss) {
        SheetHeader(folder)
        CardGroup {
            SheetAction("Play All", Icons.Default.PlayArrow, onClick = onPlayAll)
            CardDivider()
            SheetAction("Shuffle Play", Icons.Default.Shuffle, onClick = onShuffle)
        }
        Spacer(Modifier.height(16.dp))
        CardGroup {
            SheetAction("Rename Folder", Icons.Default.Edit, onClick = onRename)
            CardDivider()
            SheetAction("Copy All to…", Icons.Default.ContentCopy, onClick = onCopy)
            CardDivider()
            SheetAction("Move All to…", Icons.AutoMirrored.Filled.DriveFileMove, onClick = onMove)
        }
        Spacer(Modifier.height(16.dp))
        CardGroup {
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

// ───────────────────────── Copy / move ─────────────────────────

/** A destination folder: label shown, relative storage path. */
data class Destination(val name: String, val path: String)

@Composable
fun DestinationPicker(
    title: String, destinations: List<Destination>, exclude: String?, onDismiss: () -> Unit,
    onPick: (Destination) -> Unit, onNew: () -> Unit,
) {
    AppSheet(onDismiss) {
        SheetHeader(title)
        CardGroup { SheetAction("New Folder…", Icons.Default.CreateNewFolder, onClick = onNew) }
        Spacer(Modifier.height(16.dp))
        CardGroup {
            val list = destinations.filter { !it.path.equals(exclude, ignoreCase = true) }
            list.forEachIndexed { i, d ->
                if (i > 0) CardDivider(start = 64)
                Row(
                    Modifier.fillMaxWidth().pressable(pressedScale = 0.98f) { onPick(d) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FolderGlyph(Modifier.size(width = 36.dp, height = 29.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(d.path.ifEmpty { "Storage" }, style = Type.caption, color = SecondaryText,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
fun FileOpProgress(label: String, progress: Float) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        containerColor = NavySurface,
        shape = RoundedCornerShape(18.dp),
        title = { Text(label, style = Type.headline) },
        text = {
            Column {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) }, color = Accent, trackColor = NavyHigh,
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
                Spacer(Modifier.height(8.dp))
                Text("${(progress * 100).toInt().coerceIn(0, 100)}%", style = Type.footnote, color = SecondaryText)
            }
        },
        confirmButton = {},
    )
}
