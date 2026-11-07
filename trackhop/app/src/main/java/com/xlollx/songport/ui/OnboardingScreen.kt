package com.xlollx.songport.ui

import com.xlollx.songport.providers.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import com.xlollx.songport.BuildConfig
import com.xlollx.songport.R
import com.xlollx.songport.ads.Ads
import kotlinx.coroutines.launch

private const val PAGES = 4

/**
 * First start, in five pages: what Trackhop does (the services it joins), the three moves, the
 * Discover tab, how logins are kept and why the app is free (or has ads), and where the
 * documentation and the code are. Skip goes straight to the app; the last page's button starts it.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit, onTour: () -> Unit = onDone) {
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGES - 1
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> StepsPage()
                    2 -> DiscoverIntroPage()
                    else -> TrustPage()
                }
            }
            // Skip, the dots and Next stay in the same place on every page.
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (!last) TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.th_onboarding_skip)) }
                    // The end of the welcome offers the guided first sync, once; Start goes straight in.
                    else TextButton(onClick = onTour, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.th_tour_offer)) }
                }
                Dots(pager.currentPage)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (last) {
                        Button(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.th_onboarding_start)) }
                    } else {
                        Button(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.th_onboarding_next))
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Dots(current: Int) {
    val label = stringResource(R.string.th_onboarding_page, current + 1, PAGES)
    Row(Modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(PAGES) { i ->
            val on = i == current
            val w by animateDpAsState(if (on) 22.dp else 8.dp, label = "dot")
            val c by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "dotColor")
            Box(Modifier.height(8.dp).width(w).clip(CircleShape).background(c))
        }
    }
}

/** The page's art on the brand beam, under the status bar. */
@Composable
private fun Hero(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .brandBeam(edgeY = 0.3f)
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        content = content,
    )
}

/** Page 1: the mark among the services it joins, and what the app is for. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WelcomePage() {
    val logos = remember {
        // Amazon Music only where the build carries its connector: the Play build reaches it through
        // a separately installed plugin, so it does not show it as a service of its own.
        // The same goes for the other quick sign-in services (Qobuz, Anghami): only where built in.
        val builtInOnly = setOf("amazon", "qobuz_web", "anghami_web")
        listOf("spotify", "apple", "ytm", "amazon", "tidal", "deezer", "qobuz_web", "soundcloud", "anghami_web", "subsonic", "jellyfin", "emby", "plex")
            .filter { it !in builtInOnly || com.xlollx.songport.providers.BridgePlugin.builtIn }
            .mapNotNull { id -> Providers.services().firstOrNull { it.serviceId == id } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Hero {
            BrandTile(88.dp)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                logos.forEach { ServiceLogo(it, 44.dp) }
            }
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.th_onboarding_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.th_onboarding_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Page 2: connect, create, let it run, as three linked steps. */
@Composable
private fun StepsPage() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Hero {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroIcon(Icons.Filled.AccountCircle)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White.copy(alpha = 0.7f))
                HeroIcon(Icons.Filled.PlaylistAdd)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White.copy(alpha = 0.7f))
                HeroIcon(Icons.Filled.Sync)
            }
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            StepRow(Icons.Filled.AccountCircle, stringResource(R.string.th_onboarding_step1_title), stringResource(R.string.th_onboarding_step1_desc))
            StepRow(Icons.Filled.PlaylistAdd, stringResource(R.string.th_onboarding_step2_title), stringResource(R.string.th_onboarding_step2_desc))
            StepRow(Icons.Filled.Schedule, stringResource(R.string.th_onboarding_step3_title), stringResource(R.string.th_onboarding_step3_desc))
        }
    }
}

/** Page 3: the Discover tab, the fun side: Wrapped, links for friends, how compatible you are. */
@Composable
private fun DiscoverIntroPage() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Hero { HeroIcon(Icons.Filled.AutoAwesome, 72.dp) }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(R.string.th_onboarding_discover_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            StepRow(Icons.Filled.AutoAwesome, stringResource(R.string.th_onboarding_discover_wrapped_title), stringResource(R.string.th_onboarding_discover_wrapped_desc))
            StepRow(Icons.Filled.Link, stringResource(R.string.th_onboarding_discover_link_title), stringResource(R.string.th_onboarding_discover_link_desc))
            StepRow(Icons.Filled.QueueMusic, stringResource(R.string.th_onboarding_discover_mix_title), stringResource(R.string.th_onboarding_discover_mix_desc))
        }
    }
}

/** Page 3: logins stay on the phone; why the app is free (or has ads). */
@Composable
private fun TrustPage() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Hero { HeroIcon(Icons.Filled.Lock, 72.dp) }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.th_security_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.th_onboarding_security), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.th_onboarding_credentials), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun HeroIcon(icon: ImageVector, size: Dp = 56.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun StepRow(icon: ImageVector, title: String, desc: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
