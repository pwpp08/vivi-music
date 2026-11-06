package com.music.vivi.projector

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.music.innertube.YouTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.random.Random

/** What a detail page lists: a title plus a way to load its songs. */
class DetailTarget(val title: String, val subtitle: String, val thumbnail: String?, val load: suspend () -> Result<List<SongItem>>)

fun detailFor(item: YTItem): DetailTarget? = when (item) {
    is AlbumItem -> DetailTarget(item.title, item.subtitle(), item.thumbnail) { YouTube.albumSongs(item.playlistId, item) }
    is PlaylistItem -> DetailTarget(item.title, item.subtitle(), item.thumbnail) { YouTube.playlist(item.id).map { it.songs } }
    else -> null
}

private enum class Tab(val label: String) { Home("Home"), Search("Search"), Library("Library"), Account("Account") }

@Composable
fun ProjectorRoot(settings: Settings) {
    var tab by remember { mutableStateOf(if (settings.hasAccount) Tab.Home else Tab.Account) }
    var detail by remember { mutableStateOf<DetailTarget?>(null) }
    var showPlayer by remember { mutableStateOf(false) }
    var syncVersion by remember { mutableIntStateOf(0) }

    fun open(item: YTItem, siblings: List<YTItem>) {
        when (item) {
            is SongItem -> {
                val songs = siblings.filterIsInstance<SongItem>().ifEmpty { listOf(item) }
                PlayerController.playQueue(songs, songs.indexOf(item).coerceAtLeast(0))
            }
            else -> detailFor(item)?.let { detail = it }
        }
    }

    BackHandler(enabled = showPlayer) { showPlayer = false }
    BackHandler(enabled = !showPlayer && detail != null) { detail = null }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f)) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    Spacer(Modifier.height(16.dp))
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = tab == t && detail == null,
                            onClick = {
                                tab = t
                                detail = null
                            },
                            icon = {
                                Icon(
                                    when (t) {
                                        Tab.Home -> Icons.Filled.Home
                                        Tab.Search -> Icons.Filled.Search
                                        Tab.Library -> Icons.Filled.LibraryMusic
                                        Tab.Account -> Icons.Filled.Person
                                    },
                                    contentDescription = t.label,
                                )
                            },
                            label = { Text(t.label) },
                            colors = NavigationRailItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 24.dp, vertical = 16.dp)) {
                    val d = detail
                    if (d != null) {
                        DetailScreen(d, ::open)
                    } else {
                        when (tab) {
                            Tab.Home -> HomeScreen(syncVersion, ::open)
                            Tab.Search -> SearchScreen(::open)
                            Tab.Library -> LibraryScreen(settings, syncVersion, ::open) { detail = it }
                            Tab.Account -> SyncScreen(settings) {
                                syncVersion++
                                tab = Tab.Home
                            }
                        }
                    }
                }
            }
            if (PlayerController.current != null) MiniPlayer { showPlayer = true }
        }
        if (showPlayer && PlayerController.current != null) NowPlaying { showPlayer = false }
    }
}

@Composable
private fun HomeScreen(syncVersion: Int, onItem: (YTItem, List<YTItem>) -> Unit) {
    val state = rememberLoad(syncVersion) { YouTube.home() }
    if (state !is Load.Ok) {
        LoadingOrError(state, Modifier.fillMaxSize())
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text("Vivi", style = MaterialTheme.typography.headlineLarge, color = ViviAccent) }
        items(state.value.sections.filter { s -> s.items.any { it !is com.music.innertube.models.ArtistItem } }) { section ->
            val entries = section.items.filter { it !is com.music.innertube.models.ArtistItem }
            Column {
                Text(section.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries) { item -> ItemCard(item) { onItem(item, entries) } }
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(onItem: (YTItem, List<YTItem>) -> Unit) {
    var text by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(YouTube.SearchFilter.FILTER_SONG) }
    val filters = listOf(
        "Songs" to YouTube.SearchFilter.FILTER_SONG,
        "Albums" to YouTube.SearchFilter.FILTER_ALBUM,
        "Playlists" to YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST,
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search songs, albums, playlists") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { query = text.trim() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            filters.forEach { (label, f) ->
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(label) })
            }
        }
        if (query.isBlank()) return@Column
        val state = rememberLoad(query to filter) { YouTube.search(query, filter) }
        if (state !is Load.Ok) {
            LoadingOrError(state)
            return@Column
        }
        val results = state.value.items
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results) { item ->
                ItemRow(item, playing = item is SongItem && PlayerController.current?.id == item.id) { onItem(item, results) }
            }
        }
    }
}

@Composable
private fun LibraryScreen(settings: Settings, syncVersion: Int, onItem: (YTItem, List<YTItem>) -> Unit, openDetail: (DetailTarget) -> Unit) {
    if (!settings.hasAccount) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Sign in from the Account tab to see your library.", style = MaterialTheme.typography.titleLarge)
        }
        return
    }
    val state = rememberLoad(syncVersion) { YouTube.library("FEmusic_liked_playlists") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 8.dp)) }
        item {
            val liked = DetailTarget("Liked songs", "Your likes", null) { YouTube.playlist("LM").map { it.songs } }
            Box(
                Modifier
                    .fillMaxWidth()
                    .focusHighlight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable { openDetail(liked) }
                    .padding(20.dp),
            ) { Text("Liked songs", style = MaterialTheme.typography.titleLarge) }
        }
        when (state) {
            is Load.Ok -> {
                val playlists = state.value.items.filter { it is PlaylistItem || it is AlbumItem }
                items(playlists) { item -> ItemRow(item) { onItem(item, playlists) } }
            }
            else -> item { LoadingOrError(state) }
        }
    }
}

@Composable
private fun DetailScreen(target: DetailTarget, onItem: (YTItem, List<YTItem>) -> Unit) {
    val state = rememberLoad(target) { target.load() }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(state is Load.Ok) { if (state is Load.Ok) runCatching { playFocus.requestFocus() } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                Art(hiRes(target.thumbnail, 400), 160.dp)
                Spacer(Modifier.width(24.dp))
                Column {
                    Text(target.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(target.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state is Load.Ok) {
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier
                                .focusRequester(playFocus)
                                .focusHighlight(RoundedCornerShape(24.dp))
                                .clip(RoundedCornerShape(24.dp))
                                .background(ViviAccent)
                                .clickable { PlayerController.playQueue(state.value, 0) }
                                .padding(horizontal = 28.dp, vertical = 12.dp),
                        ) { Text("Play all", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary) }
                    }
                }
            }
        }
        when (state) {
            is Load.Ok -> items(state.value) { song ->
                ItemRow(song, playing = PlayerController.current?.id == song.id) { onItem(song, state.value) }
            }
            else -> item { LoadingOrError(state) }
        }
    }
}

@Composable
private fun SyncScreen(settings: Settings, onSynced: () -> Unit) {
    val ip = remember { localIp() }
    var link by remember { mutableStateOf<String?>(null) }
    var endpoint by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val newCode = Random.nextInt(100000, 1000000).toString()
        val server = SyncServer(newCode) { json ->
            settings.save(json)
            android.os.Handler(android.os.Looper.getMainLooper()).post(onSynced)
        }
        if (ip != null) {
            server.start()
            code = newCode
            endpoint = "$ip:${server.port}"
            link = "vivimusic://projector?host=$ip&port=${server.port}&code=$newCode"
        }
        onDispose { server.stop() }
    }

    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Account", style = MaterialTheme.typography.headlineLarge)
            if (settings.hasAccount) {
                Text(
                    "Signed in" + (settings.accountName?.let { " as $it" } ?: "") + ". Your phone is no longer needed.",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("To switch accounts, scan again.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Sign in once with your phone:", style = MaterialTheme.typography.titleMedium)
            }
            Text("1. Install Vivi on your phone and sign in.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("2. Scan the code with the phone camera.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("3. Done. The projector keeps the login by itself.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ip == null) Text("Connect the projector to Wi-Fi first.", color = ViviAccent)
        }
        Spacer(Modifier.width(48.dp))
        link?.let {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val bmp = remember(it) { qrBitmap(it) }
                Image(bmp.asImageBitmap(), contentDescription = "Sync QR code", modifier = Modifier.size(300.dp).clip(RoundedCornerShape(16.dp)))
                Spacer(Modifier.height(12.dp))
                Text("$endpoint   code $code", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MiniPlayer(onOpen: () -> Unit) {
    val song = PlayerController.current ?: return
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        val progress = if (PlayerController.durationMs > 0) PlayerController.positionMs / PlayerController.durationMs.toFloat() else 0f
        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = ViviAccent)
        Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .focusHighlight()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpen)
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Art(song.thumbnail, 56.dp, shape = RoundedCornerShape(8.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        PlayerController.error ?: song.subtitle(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            RoundIcon(Icons.Filled.SkipPrevious, "Previous") { PlayerController.previous() }
            RoundIcon(if (PlayerController.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", filled = true) {
                PlayerController.toggle()
            }
            RoundIcon(Icons.Filled.SkipNext, "Next") { PlayerController.next() }
        }
    }
}

@Composable
private fun NowPlaying(onClose: () -> Unit) {
    val song = PlayerController.current ?: return
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF23311B), MaterialTheme.colorScheme.background))),
    ) {
        Row(Modifier.fillMaxSize().padding(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(hiRes(song.thumbnail), 420.dp, shape = RoundedCornerShape(24.dp))
            Spacer(Modifier.width(56.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(song.title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(song.subtitle(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))

                val duration = PlayerController.durationMs
                val progress = if (duration > 0) PlayerController.positionMs / duration.toFloat() else 0f
                Column(
                    Modifier
                        .focusHighlight(RoundedCornerShape(10.dp))
                        .onKeyEvent {
                            if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (it.key) {
                                Key.DirectionLeft -> PlayerController.seekBy(-10_000).let { true }
                                Key.DirectionRight -> PlayerController.seekBy(10_000).let { true }
                                else -> false
                            }
                        }
                        .focusable()
                        .padding(8.dp),
                ) {
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp), color = ViviAccent)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(PlayerController.positionMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Left / Right to seek 10s", color = MaterialTheme.colorScheme.outline)
                        Text(formatTime(duration), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIcon(Icons.Filled.SkipPrevious, "Previous", 72.dp) { PlayerController.previous() }
                    RoundIcon(
                        if (PlayerController.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        "Play or pause",
                        88.dp,
                        filled = true,
                    ) { PlayerController.toggle() }
                    RoundIcon(Icons.Filled.SkipNext, "Next", 72.dp) { PlayerController.next() }
                }

                val upNext = PlayerController.queue.drop(PlayerController.index + 1).take(3)
                if (upNext.isNotEmpty()) {
                    Text("Up next", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    upNext.forEach { Text(it.title + "  -  " + it.subtitle(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

private fun qrBitmap(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300)
    val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            bmp.setPixel(x, y, if (matrix[x, y]) AColor.BLACK else AColor.WHITE)
        }
    }
    return bmp
}

private fun localIp(): String? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress
}.getOrNull()
