package com.music.vivi.projector

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of a network load shown by a screen. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Fail(val message: String) : Load<Nothing>
}

@Composable
fun <T> rememberLoad(key: Any?, block: suspend () -> Result<T>): Load<T> {
    val state = produceState<Load<T>>(Load.Loading, key) {
        value = Load.Loading
        value = withContext(Dispatchers.IO) {
            block().fold({ Load.Ok(it) }, { Load.Fail(it.message ?: "Something went wrong") })
        }
    }
    return state.value
}

/** Red outline while D-pad focused. Must come before the clickable in the chain. */
@Composable
fun Modifier.focusHighlight(shape: Shape = RoundedCornerShape(14.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .then(if (focused) Modifier.border(3.dp, ViviAccent, shape) else Modifier)
        .onFocusChanged { focused = it.isFocused }
}

fun hiRes(url: String?, size: Int = 544): String? =
    url?.replace(Regex("=w\\d+-h\\d+"), "=w$size-h$size")?.replace(Regex("=s\\d+"), "=s$size")

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

fun YTItem.subtitle(): String = when (this) {
    is SongItem -> artists.joinToString { it.name }
    is AlbumItem -> artists?.joinToString { it.name }.orEmpty()
    is PlaylistItem -> author?.name.orEmpty()
    is ArtistItem -> subtext.orEmpty()
    else -> ""
}

@Composable
fun Art(url: String?, size: Dp, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp)) {
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) {
            Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.outline)
        } else {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        }
    }
}

/** Square card for shelves. */
@Composable
fun ItemCard(item: YTItem, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .width(180.dp)
            .focusHighlight(shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        Art(item.thumbnail, 168.dp)
        Spacer(Modifier.height(8.dp))
        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            item.subtitle(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Wide row for lists (search results, playlists, queues). */
@Composable
fun ItemRow(item: YTItem, playing: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .focusHighlight(shape)
            .clip(shape)
            .background(if (playing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Art(item.thumbnail, 64.dp, shape = RoundedCornerShape(10.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.subtitle(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item is SongItem && item.duration != null) {
            Text(formatTime(item.duration!! * 1000L), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun RoundIcon(icon: ImageVector, label: String, size: Dp = 56.dp, filled: Boolean = false, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(size)
            .focusHighlight(CircleShape)
            .clip(CircleShape)
            .then(if (filled) Modifier.background(ViviAccent) else Modifier),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

@Composable
fun LoadingOrError(state: Load<*>, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        when (state) {
            is Load.Loading -> CircularProgressIndicator(color = ViviAccent)
            is Load.Fail -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Couldn't load", style = MaterialTheme.typography.titleLarge)
                Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is Load.Ok -> {}
        }
    }
}
