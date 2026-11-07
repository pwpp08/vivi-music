/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.music.vivi.constants.DarkModeKey
import com.music.vivi.constants.PureBlackKey
import com.music.vivi.constants.SelectedThemeColorKey
import com.music.vivi.ui.screens.settings.DarkMode
import com.music.vivi.utils.rememberEnumPreference
import com.music.vivi.utils.rememberPreference

/** Vivi's theme for the playlist sync screens: the same dark mode, pure black and theme colour as the rest of the app. */
@Composable
fun PlaylistSyncTheme(content: @Composable () -> Unit) {
    val darkMode by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    val systemDark = isSystemInDarkTheme()
    val dark = if (darkMode == DarkMode.AUTO) systemDark else darkMode == DarkMode.ON
    val pureBlack by rememberPreference(PureBlackKey, defaultValue = false)
    val themeColor by rememberPreference(SelectedThemeColorKey, defaultValue = DefaultThemeColor.toArgb())
    vivimusicTheme(
        darkTheme = dark,
        pureBlack = pureBlack && dark,
        themeColor = Color(themeColor),
        content = content,
    )
}
