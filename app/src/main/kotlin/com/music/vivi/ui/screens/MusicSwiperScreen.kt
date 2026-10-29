/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.ui.screens

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.music.innertube.models.SongItem
import com.music.vivi.LocalPlayerAwareWindowInsets
import com.music.vivi.LocalPlayerBottomSheetState
import com.music.vivi.LocalPlayerConnection
import com.music.vivi.R
import com.music.vivi.extensions.toMediaItem
import com.music.vivi.models.toMediaMetadata
import com.music.vivi.playback.queues.ListQueue
import com.music.vivi.utils.makeTimeString
import com.music.vivi.viewmodels.MusicSwiperViewModel
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicSwiperScreen(
    navController: NavController,
    pureBlack: Boolean = false,
    viewModel: MusicSwiperViewModel = hiltViewModel(),
) {
    val cards by viewModel.cards.collectAsState()
    val likedCount by viewModel.likedCount.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val playerConnection = LocalPlayerConnection.current
    val playerBottomSheetState = LocalPlayerBottomSheetState.current
    val currentMediaMetadata by playerConnection?.mediaMetadata?.collectAsState() ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }.collectAsState()
    val isPlaying by playerConnection?.isPlaying?.collectAsState() ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }.collectAsState()

    val view = LocalView.current

    // Handle system back gesture smoothly without any lag
    BackHandler {
        navController.navigateUp()
    }

    val currentTopCard = cards.firstOrNull()
    val isCurrentCardPlaying = currentTopCard != null && isPlaying && currentMediaMetadata?.id == currentTopCard.id

    val surfaceBg = if (pureBlack) Color.Black else MaterialTheme.colorScheme.background

    // Calculate player aware insets so bottom action buttons sit comfortably above the mini player
    val playerAwareBottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding()

    fun playCard(song: SongItem) {
        playerConnection?.playQueue(
            ListQueue(
                title = "Music Match",
                items = listOf(song.toMediaMetadata().toMediaItem()),
                startIndex = 0,
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(surfaceBg)
            .statusBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = playerAwareBottomPadding.coerceAtLeast(16.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top Bar
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.music_match_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        if (likedCount > 0) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.favorite),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = "$likedCount",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .padding(end = 16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        IconButton(onClick = { viewModel.loadRecommendations() }) {
                            Icon(
                                painter = painterResource(R.drawable.refresh),
                                contentDescription = stringResource(R.string.refresh),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )

            // Subtitle hint
            Text(
                text = stringResource(R.string.music_match_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // Card Deck Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (cards.isEmpty()) {
                    // Empty state
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.music_note),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            modifier = Modifier.size(72.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.music_match_caught_up),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.music_match_caught_up_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(24.dp))
                        Surface(
                            onClick = { viewModel.loadRecommendations() },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            shadowElevation = 4.dp,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.refresh),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Text(
                                    text = stringResource(R.string.discover_more),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                } else {
                    // Render up to 3 cards in stack (from back to front)
                    val stackCards = cards.take(3).reversed()
                    stackCards.forEachIndexed { reversedIdx, card ->
                        val index = (stackCards.size - 1) - reversedIdx
                        val isTop = index == 0
                        key(card.id) {
                            SwipeableSongCard(
                                song = card,
                                isTop = isTop,
                                index = index,
                                isPlaying = isPlaying && currentMediaMetadata?.id == card.id,
                                onPlayToggle = {
                                    if (isPlaying && currentMediaMetadata?.id == card.id) {
                                        playerConnection?.player?.pause()
                                    } else {
                                        playCard(card)
                                    }
                                },
                                onSwipedRight = {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                    val nextCard = cards.getOrNull(1)
                                    viewModel.swipeRight(card)
                                    if (nextCard != null) {
                                        playCard(nextCard)
                                    }
                                },
                                onSwipedLeft = {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                    val nextCard = cards.getOrNull(1)
                                    viewModel.swipeLeft(card)
                                    if (nextCard != null) {
                                        playCard(nextCard)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // Bottom Action Controls (positioned comfortably above the song bar)
            if (cards.isNotEmpty() && currentTopCard != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Undo button
                    IconButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            viewModel.undo()
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.size(46.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.replay),
                            contentDescription = stringResource(R.string.undo),
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    // Pass (X) button
                    Surface(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            val nextCard = cards.getOrNull(1)
                            viewModel.swipeLeft(currentTopCard)
                            if (nextCard != null) {
                                playCard(nextCard)
                            }
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                        shadowElevation = 6.dp,
                        modifier = Modifier.size(60.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.close),
                                contentDescription = stringResource(R.string.action_pass),
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }

                    // Play / Preview button (Plays THIS EXACT CARD)
                    Surface(
                        onClick = {
                            if (isCurrentCardPlaying) {
                                playerConnection?.player?.pause()
                            } else {
                                playCard(currentTopCard)
                            }
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shadowElevation = 6.dp,
                        modifier = Modifier.size(54.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(if (isCurrentCardPlaying) R.drawable.pause else R.drawable.play),
                                contentDescription = stringResource(if (isCurrentCardPlaying) R.string.pause else R.string.play),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }

                    // Like (Heart) button
                    Surface(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            val nextCard = cards.getOrNull(1)
                            viewModel.swipeRight(currentTopCard)
                            if (nextCard != null) {
                                playCard(nextCard)
                            }
                        },
                        shape = CircleShape,
                        color = Color(0xFF2E7D32),
                        shadowElevation = 6.dp,
                        modifier = Modifier.size(60.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.favorite),
                                contentDescription = stringResource(R.string.action_like),
                                tint = Color.White,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }

                    // Expand to full-screen player button
                    IconButton(
                        onClick = {
                            if (currentMediaMetadata?.id != currentTopCard.id) {
                                playCard(currentTopCard)
                            }
                            playerBottomSheetState?.expandSoft()
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.size(46.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.expand_less),
                            contentDescription = stringResource(R.string.action_expand_player),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun SwipeableSongCard(
    song: SongItem,
    isTop: Boolean,
    index: Int,
    isPlaying: Boolean,
    onPlayToggle: () -> Unit,
    onSwipedRight: () -> Unit,
    onSwipedLeft: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var isDragging by remember(song.id) { mutableStateOf(false) }
    var dragX by remember(song.id) { mutableFloatStateOf(0f) }
    var dragY by remember(song.id) { mutableFloatStateOf(0f) }

    val animX = remember(song.id) { Animatable(0f) }
    val animY = remember(song.id) { Animatable(0f) }

    // Depth scaling for stack layers underneath
    val targetScale = when (index) {
        0 -> 1f
        1 -> 0.94f
        else -> 0.88f
    }
    val targetYOffset = when (index) {
        0 -> 0.dp
        1 -> 14.dp
        else -> 28.dp
    }

    val animatedScale by animateFloatAsState(targetValue = targetScale, label = "cardScale")

    val shape = RoundedCornerShape(26.dp)
    val cardZIndex = 10f - index.toFloat()

    val currentX = if (isDragging) dragX else animX.value
    val currentY = if (isDragging) dragY else animY.value

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .zIndex(cardZIndex)
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
                translationY = targetYOffset.toPx()
                if (isTop) {
                    translationX = currentX
                    translationY = targetYOffset.toPx() + currentY
                    rotationZ = (currentX / 20f).coerceIn(-28f, 28f)
                }
            }
            .shadow(
                elevation = if (isTop) 16.dp else (10.dp - (index * 3).dp),
                shape = shape,
                clip = false,
            )
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .pointerInput(isTop, song.id) {
                if (!isTop) return@pointerInput
                detectDragGestures(
                    onDragStart = {
                        isDragging = true
                        dragX = 0f
                        dragY = 0f
                    },
                    onDragEnd = {
                        isDragging = false
                        val distance = dragX
                        coroutineScope.launch {
                            animX.snapTo(distance)
                            animY.snapTo(dragY)
                            if (distance > 280f) {
                                launch { animX.animateTo(1400f, tween(200)) }
                                onSwipedRight()
                            } else if (distance < -280f) {
                                launch { animX.animateTo(-1400f, tween(200)) }
                                onSwipedLeft()
                            } else {
                                launch { animX.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) }
                                launch { animY.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) }
                            }
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        coroutineScope.launch {
                            animX.snapTo(dragX)
                            animY.snapTo(dragY)
                            launch { animX.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) }
                            launch { animY.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)) }
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragX += dragAmount.x
                        dragY += dragAmount.y * 0.45f
                    },
                )
            },
    ) {
        // Full artwork background
        AsyncImage(
            model = song.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Gradient vignette overlay for readable text and controls
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.15f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.65f),
                            Color.Black.copy(alpha = 0.95f),
                        ),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY,
                    )
                ),
        )

        // Floating dynamic LIKE badge (when dragging right)
        if (isTop && currentX > 40f) {
            val alpha = (currentX / 200f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp)
                    .graphicsLayer { this.alpha = alpha; rotationZ = -12f }
                    .border(3.dp, Color(0xFF4CAF50), RoundedCornerShape(8.dp))
                    .background(Color(0xFF4CAF50).copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.badge_like),
                    color = Color(0xFF4CAF50),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                )
            }
        }

        // Floating dynamic NOPE badge (when dragging left)
        if (isTop && currentX < -40f) {
            val alpha = (abs(currentX) / 200f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
                    .graphicsLayer { this.alpha = alpha; rotationZ = 12f }
                    .border(3.dp, Color(0xFFE53935), RoundedCornerShape(8.dp))
                    .background(Color(0xFFE53935).copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    text = stringResource(R.string.badge_nope),
                    color = Color(0xFFE53935),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                )
            }
        }

        // Play/Pause circular preview trigger right on card center
        Surface(
            onClick = onPlayToggle,
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.55f),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White.copy(alpha = 0.6f)),
            modifier = Modifier
                .align(Alignment.Center)
                .size(68.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        // Playing pill indicator
        if (isPlaying) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 18.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.volume_up),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.playing_status),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        // Song Title, Artists, Album badge at the bottom of the card
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = song.artists.joinToString { it.name },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val album = song.album
            val duration = song.duration
            if (album != null || duration != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (album != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.White.copy(alpha = 0.15f),
                        ) {
                            Text(
                                text = album.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.9f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (duration != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.White.copy(alpha = 0.15f),
                        ) {
                            Text(
                                text = makeTimeString(duration * 1000L),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
