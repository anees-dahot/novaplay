package com.adfree.player.ui.library

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
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.LargeTopAppBar
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
import com.adfree.player.Accent
import com.adfree.player.Navy
import com.adfree.player.NavyHigh
import com.adfree.player.NavySurface
import com.adfree.player.data.ActionResult
import com.adfree.player.data.LibraryStore
import com.adfree.player.data.MediaActions
import com.adfree.player.data.Video
import com.adfree.player.data.VideoInfo
import com.adfree.player.data.VideoRepository
import com.adfree.player.ui.Destructive
import com.adfree.player.ui.Motion
import com.adfree.player.ui.PinPrompt
import com.adfree.player.ui.TextPrompt
import com.adfree.player.ui.Type
import com.adfree.player.ui.glass
import com.adfree.player.ui.pressable
import kotlinx.coroutines.launch

enum class SortBy(val label: String) { DATE("Newest"), NAME("Name"), SIZE("Size") }

/** Screens, encoded as strings so the back stack survives rotation. */
object Route {
    const val HOME = "home"
    const val FAV = "fav"
    const val PLAYLISTS = "playlists"
    const val PRIVATE = "private"
    const val HIDDEN = "hidden"
    fun folder(name: String) = "folder:$name"
    fun playlist(name: String) = "playlist:$name"
}

private fun titleOf(route: String) = when {
    route == Route.HOME -> "Library"
    route == Route.FAV -> "Favourites"
    route == Route.PLAYLISTS -> "Playlists"
    route == Route.PRIVATE -> "Private"
    route == Route.HIDDEN -> "Hidden Folders"
    else -> route.substringAfter(':')
}

/** Shows video lists (vs. menus of folders/playlists). */
private fun isVideoRoute(route: String) =
    route.startsWith("folder:") || route.startsWith("playlist:") || route == Route.FAV || route == Route.PRIVATE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (videos: List<Video>, index: Int) -> Unit) {
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
    var forward by remember { mutableStateOf(true) }
    val route = stack.last()
    var sort by rememberSaveable { mutableStateOf(SortBy.DATE) }
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
    fun openPrivate() {
        if (LibraryStore.privateUnlocked) navigate(Route.PRIVATE)
        else pinPrompt = { navigate(Route.PRIVATE) }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasPermission(); refreshTick++ }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(permission) }
    LaunchedEffect(granted, refreshTick) {
        if (granted) all = VideoRepository.load(context)
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
        SortBy.NAME -> list.sortedBy { it.name.lowercase() }
        SortBy.DATE -> list.sortedByDescending { it.dateAdded }
        SortBy.SIZE -> list.sortedByDescending { it.size }
    }
    val routeVideos: List<Video> = remember(route, videos, storeVersion, sort) {
        when {
            route.startsWith("folder:") -> sorted(visible.filter { it.folder == route.substringAfter(':') })
            route.startsWith("playlist:") ->
                LibraryStore.playlists[route.substringAfter(':')].orEmpty().mapNotNull { byUri[it] }
            route == Route.FAV -> sorted(visible.filter { it.uri.toString() in LibraryStore.favourites })
            route == Route.PRIVATE -> sorted(videos.filter { it.uri.toString() in privateSet })
            else -> emptyList()
        }
    }
    val selectedVideos = routeVideos.filter { it.id in selected } +
        (if (route == Route.HOME) visible.filter { it.id in selected } else emptyList())

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = Navy,
        topBar = {
            LargeTopAppBar(
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(containerColor = Navy, scrolledContainerColor = NavySurface),
                title = {
                    Text(
                        if (selecting) "${selected.size} Selected" else titleOf(route),
                        style = if (scroll.state.collapsedFraction > 0.5f) Type.headline else Type.largeTitle,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (stack.size > 1 && !selecting) {
                        Row(Modifier.padding(start = 8.dp).pressable { back() }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", tint = Accent, modifier = Modifier.size(18.dp))
                            Text(titleOf(stack[stack.lastIndex - 1]), color = Accent, style = Type.body, maxLines = 1)
                        }
                    }
                },
                actions = {
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
                                "Toggle grid", tint = Accent,
                            )
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort", tint = Accent) }
                            DropdownMenu(sortMenu, { sortMenu = false }, containerColor = NavyHigh, shape = RoundedCornerShape(14.dp)) {
                                SortBy.entries.forEach { o ->
                                    DropdownMenuItem(
                                        text = { Text(o.label, style = Type.body) },
                                        trailingIcon = { if (o == sort) Icon(Icons.Default.Check, null, tint = Accent) },
                                        onClick = { sort = o; sortMenu = false },
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
                            } else onOpen(list, i)
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
            onPlay = { menuFor = null; onOpen(listOf(v), 0) },
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
        )
    }
    folderMenuFor?.let { f ->
        FolderMenu(
            folder = f,
            onDismiss = { folderMenuFor = null },
            onPlayAll = {
                folderMenuFor = null
                val list = sorted(visible.filter { it.folder == f })
                if (list.isNotEmpty()) onOpen(list, 0)
            },
            onHide = { folderMenuFor = null; LibraryStore.setFolderHidden(f, true) },
        )
    }
    playlistMenuFor?.let { p ->
        PlaylistMenu(
            name = p,
            onDismiss = { playlistMenuFor = null },
            onPlay = {
                playlistMenuFor = null
                val list = LibraryStore.playlists[p].orEmpty().mapNotNull { byUri[it] }
                if (list.isNotEmpty()) onOpen(list, 0)
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
            val newName = if (ext.isNotEmpty()) "$base.$ext" else base
            val apply = {
                handle(MediaActions.applyRename(context, v.uri, newName)) { refreshTick++ }
            }
            handle(MediaActions.requestRename(context, v.uri)) { apply() }
        }
    }
    confirmDelete?.let { list ->
        ConfirmDeleteSheet(
            count = list.size,
            onDismiss = { confirmDelete = null },
            onConfirm = {
                confirmDelete = null
                handle(MediaActions.delete(context, list.map { it.uri })) {
                    LibraryStore.forget(list.map { it.uri.toString() })
                    selecting = false; selected.clear(); refreshTick++
                }
            },
        )
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
        Modifier.size(52.dp).then(if (enabled) Modifier.pressable(pressedScale = 0.85f, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = if (enabled) tint else Color(0x55FFFFFF)) }
}

private fun <T> listSaver(save: (androidx.compose.runtime.snapshots.SnapshotStateList<T>) -> List<T>, restore: (List<T>) -> androidx.compose.runtime.snapshots.SnapshotStateList<T>) =
    androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<T>, T>(
        save = { save(it) }, restore = { restore(it) },
    )

private fun <T> List<T>.toMutableStateList() = mutableStateListOf<T>().also { it.addAll(this) }

