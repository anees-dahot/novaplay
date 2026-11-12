package com.novaplay.player.ui.library

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.novaplay.player.Accent
import com.novaplay.player.Navy
import com.novaplay.player.NavyHigh
import com.novaplay.player.NavySurface
import com.novaplay.player.data.ActionResult
import com.novaplay.player.data.LibraryStore
import com.novaplay.player.data.MediaActions
import com.novaplay.player.data.Video
import com.novaplay.player.data.VideoInfo
import com.novaplay.player.data.VideoRepository
import com.novaplay.player.ui.Destructive
import com.novaplay.player.ui.Motion
import com.novaplay.player.ui.PinPrompt
import com.novaplay.player.ui.TextPrompt
import com.novaplay.player.ui.Type
import com.novaplay.player.ui.glass
import com.novaplay.player.ui.pressable
import kotlinx.coroutines.launch

enum class SortBy(val label: String) { DATE("Newest"), NAME("Name"), SIZE("Size"), LENGTH("Longest") }

/** "Ep 2" before "Ep 10": compares digit runs as numbers, rest case-insensitively. */
private val NaturalChunk = Regex("\\d+|\\D+") // built once: sorting calls the comparator thousands of times
val NaturalOrder: Comparator<String> = Comparator { a, b ->
    val chunk = NaturalChunk
    val x = chunk.findAll(a.lowercase()).map { it.value }.toList()
    val y = chunk.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(x.size, y.size)) {
        val p = x[i]; val q = y[i]
        val c = if (p[0].isDigit() && q[0].isDigit()) {
            p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 } ?: p.trimStart('0').compareTo(q.trimStart('0'))
        } else p.compareTo(q)
        if (c != 0) return@Comparator c
    }
    x.size.compareTo(y.size)
}

/** Screens, encoded as strings so the back stack survives rotation. */
object Route {
    const val HOME = "home"
    const val FAV = "fav"
    const val PLAYLISTS = "playlists"
    const val PRIVATE = "private"
    const val HIDDEN = "hidden"
    const val HISTORY = "history"
    fun folder(name: String) = "folder:$name"
    fun playlist(name: String) = "playlist:$name"
}

private fun titleOf(route: String) = when {
    route == Route.HOME -> "Library"
    route == Route.FAV -> "Favourites"
    route == Route.PLAYLISTS -> "Playlists"
    route == Route.PRIVATE -> "Private"
    route == Route.HIDDEN -> "Hidden Folders"
    route == Route.HISTORY -> "History"
    else -> route.substringAfter(':')
}

/** Shows video lists (vs. menus of folders/playlists). */
private fun isVideoRoute(route: String) =
    route.startsWith("folder:") || route.startsWith("playlist:") || route == Route.FAV || route == Route.PRIVATE ||
        route == Route.HISTORY

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (videos: List<Video>, index: Int) -> Unit, onOpenUrl: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { LibraryStore.init(context) }
    val storeVersion = LibraryStore.version // recompose on any library change

    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    fun hasPermission() = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(hasPermission()) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    var all by remember { mutableStateOf<List<Video>?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val stack = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() })) {
        mutableStateListOf(Route.HOME)
    }
    // Restored after the app was killed: the PIN was never entered in this process, so don't reopen Private.
    remember {
        if (!LibraryStore.privateUnlocked) stack.removeAll { it == Route.PRIVATE }
        if (stack.isEmpty()) stack.add(Route.HOME)
    }
    var forward by remember { mutableStateOf(true) }
    val route = stack.last()
    var sort by rememberSaveable { mutableStateOf(runCatching { SortBy.valueOf(LibraryStore.sortKey) }.getOrDefault(SortBy.DATE)) }
    var sortMenu by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    // Selection (multi-select mode)
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }

    // Sheets / dialogs
    var menuFor by remember { mutableStateOf<Video?>(null) }
    var folderMenuFor by remember { mutableStateOf<String?>(null) }
    var playlistMenuFor by remember { mutableStateOf<String?>(null) }
    var infoFor by remember { mutableStateOf<Pair<Video, VideoInfo?>?>(null) }
    var renameFor by remember { mutableStateOf<Video?>(null) }
    var addToPlaylist by remember { mutableStateOf<List<Video>?>(null) }
    var newPlaylistFor by remember { mutableStateOf<List<Video>?>(null) }
    var pinPrompt by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmDelete by remember { mutableStateOf<List<Video>?>(null) }
    var urlPrompt by remember { mutableStateOf(false) }
    // Copy / move: picker target (videos, isMove), new-folder prompt, running progress.
    var fileOpFor by remember { mutableStateOf<Pair<List<Video>, Boolean>?>(null) }
    var newFolderFor by remember { mutableStateOf<Pair<List<Video>, Boolean>?>(null) }
    var renameFolderFor by remember { mutableStateOf<String?>(null) }
    var opProgress by remember { mutableStateOf<Pair<String, Float>?>(null) }

    // Pending work that waits for Android's own "Allow?" dialog.
    var afterConfirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    val confirmLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) afterConfirm?.invoke()
        afterConfirm = null
    }
    fun handle(result: ActionResult, onDone: () -> Unit) {
        when (result) {
            ActionResult.Done -> onDone()
            is ActionResult.NeedsConfirm -> {
                afterConfirm = onDone
                confirmLauncher.launch(IntentSenderRequest.Builder(result.sender).build())
            }
            is ActionResult.Failed -> Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
        }
    }

    fun navigate(to: String) { forward = true; selecting = false; selected.clear(); stack.add(to) }
    fun back() {
        if (selecting) { selecting = false; selected.clear(); return }
        if (stack.size > 1) { forward = false; stack.removeAt(stack.lastIndex) }
    }
    fun runFileOp(list: List<Video>, move: Boolean, dest: Destination) {
        val label = if (move) "Moving…" else "Copying…"
        fun go() {
            scope.launch {
                opProgress = label to 0f
                var replaced = emptyList<android.net.Uri>()
                val result = if (move) {
                    MediaActions.move(context, list, dest.path) { p -> opProgress = label to p }.also { replaced = it.second }.first
                } else MediaActions.copy(context, list, dest.path) { p -> opProgress = label to p }
                opProgress = null
                if (replaced.isNotEmpty()) LibraryStore.forget(replaced.map { it.toString() })
                selecting = false; selected.clear(); refreshTick++
                when (result) {
                    ActionResult.Done -> Toast.makeText(
                        context,
                        "${if (move) "Moved" else "Copied"} ${list.size} video${if (list.size == 1) "" else "s"} to ${dest.name}",
                        Toast.LENGTH_SHORT,
                    ).show()
                    else -> handle(result) { go() }
                }
            }
        }
        if (move) handle(MediaActions.requestWrite(context, list.map { it.uri })) { go() } else go()
    }
    /** Renames a folder by moving its videos into a sibling folder with the new name. */
    fun renameFolder(old: String, newName: String) {
        val list = all.orEmpty().filter { it.folder == old && it.path.isNotEmpty() }
        if (list.isEmpty()) return
        fun go() {
            scope.launch {
                opProgress = "Renaming…" to 0f
                var failed: ActionResult? = null
                val groups = list.groupBy { it.path }
                groups.entries.forEachIndexed { gi, (path, vids) ->
                    val parent = path.trimEnd('/').substringBeforeLast('/', "")
                    // MediaStore only allows videos under standard roots; top-level folders go under Movies/.
                    val dest = if (parent.isEmpty()) "Movies/$newName/" else "$parent/$newName/"
                    val (r, replaced) = MediaActions.move(context, vids, dest) { p -> opProgress = "Renaming…" to (gi + p) / groups.size }
                    if (replaced.isNotEmpty()) LibraryStore.forget(replaced.map { it.toString() })
                    if (r !is ActionResult.Done) failed = r
                }
                opProgress = null
                if (old in LibraryStore.hiddenFolders) { LibraryStore.setFolderHidden(old, false); LibraryStore.setFolderHidden(newName, true) }
                refreshTick++
                when (val f = failed) {
                    null -> Toast.makeText(context, "Renamed to $newName", Toast.LENGTH_SHORT).show()
                    else -> handle(f) { go() }
                }
            }
        }
        handle(MediaActions.requestWrite(context, list.map { it.uri })) { go() }
    }
    fun openPrivate() {
        if (LibraryStore.privateUnlocked) navigate(Route.PRIVATE)
        else pinPrompt = { navigate(Route.PRIVATE) }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasPermission(); refreshTick++ }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(permission) }
    LaunchedEffect(granted, refreshTick) {
        // A failed scan (storage unmounted, permission pulled mid-query) keeps the last list instead of crashing.
        if (granted) all = runCatching { VideoRepository.load(context) }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            all ?: emptyList()
        }
        all?.minByOrNull { it.size.takeIf { s -> s > 0 } ?: Long.MAX_VALUE }?.let { com.novaplay.player.VlcWarmup.runOnce(context, it.uri) }
        refreshing = false
    }
    BackHandler(enabled = stack.size > 1 || selecting) { back() }

    // ── Derived lists
    val videos = all.orEmpty()
    val byUri = remember(videos) { videos.associateBy { it.uri.toString() } }
    val privateSet = remember(storeVersion) { LibraryStore.privateVideos }
    val hiddenSet = remember(storeVersion) { LibraryStore.hiddenFolders }
    val visible = remember(videos, storeVersion) {
        videos.filter { it.uri.toString() !in privateSet && it.folder !in hiddenSet }
    }
    fun sorted(list: List<Video>) = when (sort) {
        SortBy.NAME -> list.sortedWith(compareBy(NaturalOrder) { it.name })
        SortBy.DATE -> list.sortedWith(compareByDescending<Video> { it.dateAdded }.thenBy(NaturalOrder) { it.name })
        SortBy.SIZE -> list.sortedByDescending { it.size }
        SortBy.LENGTH -> list.sortedByDescending { it.durationMs }
    }
    fun videosFor(route: String): List<Video> = when {
        route.startsWith("folder:") -> sorted(visible.filter { it.folder == route.substringAfter(':') })
        route.startsWith("playlist:") ->
            LibraryStore.playlists[route.substringAfter(':')].orEmpty().mapNotNull { byUri[it] }
                .filter { it.uri.toString() !in privateSet } // private videos stay behind the PIN
        route == Route.FAV -> sorted(visible.filter { it.uri.toString() in LibraryStore.favourites })
        route == Route.PRIVATE -> sorted(videos.filter { it.uri.toString() in privateSet })
        route == Route.HISTORY -> LibraryStore.recent.mapNotNull { byUri[it] }.filter { it.uri.toString() !in privateSet }
        else -> emptyList()
    }
    val routeVideos: List<Video> = remember(route, videos, storeVersion, sort) { videosFor(route) }
    /** Opens [list] at [i]. From Home / History the video goes back into the list it was last played in
     *  (its playlist, favourites or folder) instead of a "history playlist". */
    fun openFrom(r: String, list: List<Video>, i: Int) {
        val v = list[i]
        val sourced = r.startsWith("folder:") || r.startsWith("playlist:") || r == Route.FAV
        if (r == Route.HOME || r == Route.HISTORY) {
            val src = LibraryStore.sourceOf(v.uri.toString())?.takeIf { s -> videosFor(s).any { it.uri == v.uri } }
                ?: Route.folder(v.folder)
            val queue = videosFor(src)
            val at = queue.indexOfFirst { it.uri == v.uri }
            if (at >= 0) { LibraryStore.playSource = src; onOpen(queue, at); return }
        }
        LibraryStore.playSource = if (sourced) r else null
        onOpen(list, i)
    }
    val destinations = remember(videos) {
        val known = videos.filter { it.path.isNotEmpty() }.distinctBy { it.path.lowercase() }
            .map { Destination(it.folder, it.path) }
        val defaults = listOf(Destination("Movies", "Movies/"), Destination("Download", "Download/"))
            .filter { d -> known.none { it.path.equals(d.path, ignoreCase = true) } }
        (known + defaults).sortedBy { it.name.lowercase() }
    }
    val selectedVideos = routeVideos.filter { it.id in selected } +
        (if (route == Route.HOME) visible.filter { it.id in selected } else emptyList())

    val scroll = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = Navy,
        topBar = {
            TopAppBar(
                scrollBehavior = scroll,
                expandedHeight = 56.dp,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, scrolledContainerColor = NavySurface),
                title = {
                    Text(
                        if (selecting) "${selected.size} selected" else if (route == Route.HOME) "NovaPlay" else titleOf(route),
                        style = if (route == Route.HOME && !selecting) Type.title else Type.headline,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (stack.size > 1 && !selecting) {
                        IconButton(onClick = { back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                        }
                    }
                },
                actions = {
                    if (selecting && routeVideos.isNotEmpty()) {
                        val allOn = routeVideos.all { it.id in selected }
                        Text(
                            if (allOn) "None" else "All",
                            color = Accent, style = Type.headline,
                            modifier = Modifier.pressable {
                                selected.clear(); if (!allOn) selected.addAll(routeVideos.map { it.id })
                            }.padding(horizontal = 10.dp),
                        )
                    }
                    if (isVideoRoute(route) && routeVideos.isNotEmpty()) {
                        Text(
                            if (selecting) "Done" else "Select",
                            color = Accent, style = Type.headline,
                            modifier = Modifier.pressable { selecting = !selecting; selected.clear() }.padding(horizontal = 10.dp),
                        )
                    }
                    if (!selecting) {
                        IconButton(onClick = { LibraryStore.gridView = !LibraryStore.gridView }) {
                            Icon(
                                if (LibraryStore.gridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                "Toggle grid", tint = Color(0xDDFFFFFF),
                            )
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort", tint = Color(0xDDFFFFFF)) }
                            DropdownMenu(sortMenu, { sortMenu = false }, containerColor = NavyHigh, shape = RoundedCornerShape(14.dp)) {
                                SortBy.entries.forEach { o ->
                                    DropdownMenuItem(
                                        text = { Text(o.label, style = Type.body) },
                                        trailingIcon = { if (o == sort) Icon(Icons.Default.Check, null, tint = Accent) },
                                        onClick = { sort = o; LibraryStore.sortKey = o.name; sortMenu = false },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !granted -> EmptyState(
                    "See your videos",
                    "NovaPlay needs access to your videos to show them here. Nothing leaves your phone.",
                    "Allow Access",
                ) { permLauncher.launch(permission) }
                all == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
                else -> PullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = { refreshing = true; refreshTick++ },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AnimatedContent(
                        targetState = route,
                        transitionSpec = {
                            if (forward) {
                                slideInHorizontally(Motion.smooth()) { it } togetherWith
                                    (slideOutHorizontally(Motion.smooth()) { -it / 4 } + fadeOut(Motion.smooth()))
                            } else {
                                (slideInHorizontally(Motion.smooth()) { -it / 4 } + fadeIn(Motion.smooth())) togetherWith
                                    slideOutHorizontally(Motion.smooth()) { it }
                            }
                        },
                        label = "route",
                    ) { r ->
                        val grid = LibraryStore.gridView
                        val selectedIds = selected.toSet()
                        val onVideoClick: (List<Video>, Int) -> Unit = { list, i ->
                            if (selecting) {
                                val id = list[i].id
                                if (id in selected) selected.remove(id) else selected.add(id)
                            } else openFrom(r, list, i)
                        }
                        when {
                            r == Route.HOME -> HomeContent(
                                visible = visible, sort = sort, query = query, onQuery = { query = it },
                                grid = grid,
                                onVideo = onVideoClick, onVideoLong = { menuFor = it },
                                onFolder = { navigate(Route.folder(it)) }, onFolderLong = { folderMenuFor = it },
                                onFavourites = { navigate(Route.FAV) },
                                onPlaylists = { navigate(Route.PLAYLISTS) },
                                onPrivate = { openPrivate() },
                                onHidden = { navigate(Route.HIDDEN) },
                                onHistory = { navigate(Route.HISTORY) },
                                onNetwork = { urlPrompt = true },
                                onPlayFolder = { f ->
                                    val list = sorted(visible.filter { it.folder == f })
                                    if (list.isNotEmpty()) { LibraryStore.playSource = null; onOpen(list, 0) }
                                },
                            )
                            r == Route.PLAYLISTS -> PlaylistsContent(
                                onOpen = { navigate(Route.playlist(it)) },
                                onLong = { playlistMenuFor = it },
                                onNew = { newPlaylistFor = emptyList() },
                            )
                            r == Route.HIDDEN -> HiddenContent(onUnhide = { LibraryStore.setFolderHidden(it, false) })
                            else -> VideoCollection(
                                videos = routeVideos, grid = grid,
                                selecting = selecting, selected = selectedIds,
                                emptyText = when {
                                    r == Route.FAV -> "Long-press a video and choose Favourite."
                                    r == Route.PRIVATE -> "Long-press a video and choose Move to Private."
                                    r == Route.HISTORY -> "Videos you play show up here."
                                    r.startsWith("playlist:") -> "Long-press a video and choose Add to Playlist."
                                    else -> "No videos here."
                                },
                                onClick = { i -> onVideoClick(routeVideos, i) },
                                onLong = { i ->
                                    if (selecting) onVideoClick(routeVideos, i) else menuFor = routeVideos[i]
                                },
                            )
                        }
                    }
                }
            }

            // ── Selection action bar (glass, floats above content)
            AnimatedVisibility(
                visible = selecting,
                enter = slideInVertically(Motion.smooth()) { it } + fadeIn(Motion.smooth()),
                exit = slideOutVertically(Motion.smooth()) { it } + fadeOut(Motion.smooth()),
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            ) {
                val enabled = selectedVideos.isNotEmpty()
                val uris = selectedVideos.map { it.uri.toString() }
                Row(Modifier.glass().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarIcon(Icons.Default.Share, "Share", enabled) { MediaActions.share(context, selectedVideos.map { it.uri }) }
                    BarIcon(Icons.Default.FavoriteBorder, "Favourite", enabled) {
                        LibraryStore.setFavourite(uris, true); selecting = false; selected.clear()
                    }
                    BarIcon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist", enabled) { addToPlaylist = selectedVideos }
                    BarIcon(Icons.Default.Lock, "Private", enabled) {
                        val inPrivate = route == Route.PRIVATE
                        val act = { LibraryStore.setPrivate(uris, !inPrivate); selecting = false; selected.clear() }
                        if (!inPrivate && !LibraryStore.hasPin) pinPrompt = act else act()
                    }
                    BarIcon(Icons.Default.ContentCopy, "Copy to", enabled) { fileOpFor = selectedVideos to false }
                    BarIcon(Icons.AutoMirrored.Filled.DriveFileMove, "Move to", enabled) { fileOpFor = selectedVideos to true }
                    BarIcon(Icons.Default.Delete, "Delete", enabled, Destructive) { confirmDelete = selectedVideos }
                }
            }
        }
    }

    // ── Sheets & dialogs
    menuFor?.let { v ->
        VideoMenu(
            video = v,
            route = route,
            onDismiss = { menuFor = null },
            onPlay = { menuFor = null; LibraryStore.playSource = null; onOpen(listOf(v), 0) },
            onSelect = { menuFor = null; selecting = true; selected.clear(); selected.add(v.id) },
            onAddToPlaylist = { menuFor = null; addToPlaylist = listOf(v) },
            onPrivate = { makePrivate ->
                menuFor = null
                val act = { LibraryStore.setPrivate(listOf(v.uri.toString()), makePrivate) }
                if (makePrivate && !LibraryStore.hasPin) pinPrompt = act else act()
            },
            onRename = { menuFor = null; renameFor = v },
            onShare = { menuFor = null; MediaActions.share(context, listOf(v.uri)) },
            onInfo = {
                menuFor = null
                infoFor = v to null
                scope.launch { infoFor = v to MediaActions.info(context, v) }
            },
            onDelete = { menuFor = null; confirmDelete = listOf(v) },
            onCopy = { menuFor = null; fileOpFor = listOf(v) to false },
            onMove = { menuFor = null; fileOpFor = listOf(v) to true },
            onRemoveRecent = if ((route == Route.HOME || route == Route.HISTORY) && v.uri.toString() in LibraryStore.recent) {
                { menuFor = null; LibraryStore.removeRecent(listOf(v.uri.toString())) }
            } else null,
        )
    }
    folderMenuFor?.let { f ->
        FolderMenu(
            folder = f,
            onDismiss = { folderMenuFor = null },
            onPlayAll = {
                folderMenuFor = null
                val list = sorted(visible.filter { it.folder == f })
                if (list.isNotEmpty()) { LibraryStore.playSource = null; onOpen(list, 0) }
            },
            onShuffle = {
                folderMenuFor = null
                val list = visible.filter { it.folder == f }.shuffled()
                if (list.isNotEmpty()) { LibraryStore.playSource = null; onOpen(list, 0) }
            },
            onHide = { folderMenuFor = null; LibraryStore.setFolderHidden(f, true) },
            onRename = { folderMenuFor = null; renameFolderFor = f },
            onCopy = { folderMenuFor = null; fileOpFor = visible.filter { it.folder == f } to false },
            onMove = { folderMenuFor = null; fileOpFor = visible.filter { it.folder == f } to true },
        )
    }
    playlistMenuFor?.let { p ->
        PlaylistMenu(
            name = p,
            onDismiss = { playlistMenuFor = null },
            onPlay = {
                playlistMenuFor = null
                val list = LibraryStore.playlists[p].orEmpty().mapNotNull { byUri[it] }
                    .filter { it.uri.toString() !in privateSet }
                if (list.isNotEmpty()) { LibraryStore.playSource = null; onOpen(list, 0) }
            },
            onDelete = { playlistMenuFor = null; LibraryStore.deletePlaylist(p) },
        )
    }
    infoFor?.let { (v, info) -> InfoSheet(v, info) { infoFor = null } }
    addToPlaylist?.let { list ->
        PlaylistPicker(
            onDismiss = { addToPlaylist = null },
            onPick = { name ->
                LibraryStore.addToPlaylist(name, list.map { it.uri.toString() })
                Toast.makeText(context, "Added to $name", Toast.LENGTH_SHORT).show()
                addToPlaylist = null; selecting = false; selected.clear()
            },
            onNew = { newPlaylistFor = list; addToPlaylist = null },
        )
    }
    newPlaylistFor?.let { list ->
        TextPrompt("New Playlist", confirm = "Create", onDismiss = { newPlaylistFor = null }) { name ->
            LibraryStore.addToPlaylist(name, list.map { it.uri.toString() })
            newPlaylistFor = null; selecting = false; selected.clear()
        }
    }
    renameFor?.let { v ->
        val ext = v.name.substringAfterLast('.', "")
        TextPrompt("Rename", initial = v.name.substringBeforeLast('.'), onDismiss = { renameFor = null }) { base ->
            renameFor = null
            val clean = base.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            val newName = if (ext.isNotEmpty()) "$clean.$ext" else clean
            // Android 10 asks for access first, then the rename has to be run again (once).
            fun apply(retry: Boolean) {
                val r = MediaActions.applyRename(context, v.uri, newName)
                if (r is ActionResult.NeedsConfirm && retry) handle(r) { apply(false) } else handle(r) { refreshTick++ }
            }
            if (clean.isNotEmpty() && newName != v.name) handle(MediaActions.requestRename(context, v.uri)) { apply(true) }
        }
    }
    confirmDelete?.let { list ->
        ConfirmDeleteSheet(
            count = list.size,
            onDismiss = { confirmDelete = null },
            onConfirm = {
                confirmDelete = null
                // Android 10 grants access one file at a time: after each approval run the delete again.
                fun run(attemptsLeft: Int) {
                    val r = MediaActions.delete(context, list.map { it.uri })
                    if (r is ActionResult.NeedsConfirm && Build.VERSION.SDK_INT == 29 && attemptsLeft > 0) {
                        handle(r) { run(attemptsLeft - 1) }
                    } else handle(r) {
                        LibraryStore.forget(list.map { it.uri.toString() })
                        selecting = false; selected.clear(); refreshTick++
                    }
                }
                run(list.size)
            },
        )
    }
    fileOpFor?.let { (list, move) ->
        val single = list.map { it.path }.distinct().singleOrNull()
        DestinationPicker(
            title = (if (move) "Move " else "Copy ") + (if (list.size == 1) "to…" else "${list.size} videos to…"),
            destinations = destinations,
            exclude = if (move) single else null,
            onDismiss = { fileOpFor = null },
            onPick = { d -> fileOpFor = null; runFileOp(list, move, d) },
            onNew = { fileOpFor = null; newFolderFor = list to move },
        )
    }
    newFolderFor?.let { (list, move) ->
        TextPrompt("New Folder", confirm = if (move) "Move" else "Copy", placeholder = "Folder name (inside Movies)",
            onDismiss = { newFolderFor = null }) { name ->
            newFolderFor = null
            val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            if (clean.isNotEmpty()) runFileOp(list, move, Destination(clean, "Movies/$clean/"))
        }
    }
    renameFolderFor?.let { f ->
        TextPrompt("Rename Folder", initial = f, confirm = "Rename", onDismiss = { renameFolderFor = null }) { name ->
            renameFolderFor = null
            val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
            if (clean.isNotEmpty() && clean != f) renameFolder(f, clean)
        }
    }
    opProgress?.let { (label, p) -> FileOpProgress(label, p) }
    if (urlPrompt) {
        TextPrompt("Open Network Stream", confirm = "Play", placeholder = "https://… · rtsp://… · smb://…", onDismiss = { urlPrompt = false }) { url ->
            urlPrompt = false
            val fixed = if ("://" in url) url else "https://$url"
            if (Uri.parse(fixed).scheme in setOf("http", "https", "rtsp", "rtmp", "smb", "ftp", "udp", "mms")) {
                LibraryStore.addUrl(fixed); onOpenUrl(fixed)
            } else Toast.makeText(context, "Unsupported link", Toast.LENGTH_SHORT).show()
        }
    }
    pinPrompt?.let { then ->
        val creating = !LibraryStore.hasPin
        PinPrompt(creating = creating, onDismiss = { pinPrompt = null }) { pin ->
            if (creating) { LibraryStore.setPin(pin); LibraryStore.privateUnlocked = true; pinPrompt = null; then(); true }
            else if (LibraryStore.checkPin(pin)) { LibraryStore.privateUnlocked = true; pinPrompt = null; then(); true }
            else false
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, desc: String, enabled: Boolean, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).then(if (enabled) Modifier.pressable(pressedScale = 0.85f, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = if (enabled) tint else Color(0x55FFFFFF)) }
}

private fun <T> listSaver(save: (androidx.compose.runtime.snapshots.SnapshotStateList<T>) -> List<T>, restore: (List<T>) -> androidx.compose.runtime.snapshots.SnapshotStateList<T>) =
    androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<T>, T>(
        save = { save(it) }, restore = { restore(it) },
    )

private fun <T> List<T>.toMutableStateList() = mutableStateListOf<T>().also { it.addAll(this) }

