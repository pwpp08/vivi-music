package com.xlollx.songport.providers

import android.content.Context
import com.xlollx.songport.model.Playlist
import com.xlollx.songport.model.Track
import com.xlollx.songport.platform.Res
import com.xlollx.songport.platform.env
import java.io.File

// The connectors live in the engine (android/core) and take its Env. The app calls them with a
// Context, as before: these overloads hand them the app's Env, so screens and workers stay as they are.

fun MusicProvider.libraryEntries(ctx: Context, asTarget: Boolean): List<Playlist> = libraryEntries(ctx.env, asTarget)
suspend fun MusicProvider.reorderTracks(ctx: Context, playlistId: String, current: List<Track>, ordered: List<Track>) = reorderTracks(ctx.env, playlistId, current, ordered)
suspend fun MusicProvider.setPlaylistCover(ctx: Context, playlistId: String, jpeg: ByteArray) = setPlaylistCover(ctx.env, playlistId, jpeg)
fun MusicProvider.isConfigured(ctx: Context): Boolean = isConfigured(ctx.env)
fun MusicProvider.usesOwnCredentials(ctx: Context): Boolean = usesOwnCredentials(ctx.env)
fun MusicProvider.isConnected(ctx: Context): Boolean = isConnected(ctx.env)
fun MusicProvider.accountName(ctx: Context): String? = accountName(ctx.env)
fun MusicProvider.label(ctx: Context): String = label(ctx.env)
fun MusicProvider.startAuth(ctx: Context) = startAuth(ctx.env)
suspend fun MusicProvider.completeAuth(ctx: Context, params: Map<String, String>, verifier: String) = completeAuth(ctx.env, params, verifier)
fun MusicProvider.disconnect(ctx: Context) = disconnect(ctx.env)
suspend fun MusicProvider.playlists(ctx: Context): List<Playlist> = playlists(ctx.env)
suspend fun MusicProvider.tracks(ctx: Context, playlistId: String): List<Track> = tracks(ctx.env, playlistId)
suspend fun MusicProvider.unplayable(ctx: Context, playlistId: String): List<com.xlollx.songport.model.UnplayableEntry> = unplayable(ctx.env, playlistId)
suspend fun MusicProvider.search(ctx: Context, track: Track): List<Track> = search(ctx.env, track)
suspend fun MusicProvider.createPlaylist(ctx: Context, name: String, description: String): Playlist = createPlaylist(ctx.env, name, description)
suspend fun MusicProvider.addTracks(ctx: Context, playlistId: String, tracks: List<Track>) = addTracks(ctx.env, playlistId, tracks)
suspend fun MusicProvider.removeTracks(ctx: Context, playlistId: String, tracks: List<Track>) = removeTracks(ctx.env, playlistId, tracks)
suspend fun MusicProvider.renamePlaylist(ctx: Context, playlistId: String, name: String) = renamePlaylist(ctx.env, playlistId, name)
suspend fun MusicProvider.deletePlaylist(ctx: Context, playlistId: String) = deletePlaylist(ctx.env, playlistId)
suspend fun MusicProvider.setPlaylistVisibility(ctx: Context, playlistId: String, public: Boolean) = setPlaylistVisibility(ctx.env, playlistId, public)
fun MusicProvider.webSearchUrl(ctx: Context, query: String): String? = webSearchUrl(ctx.env, query)
/** The service's web search for MatchSearch's "Open in" button, or null when it has none (the button is hidden). */
fun MusicProvider.webSearch(ctx: Context): ((String) -> String?)? =
    if (hasWebSearch(ctx.env)) { q -> webSearchUrl(ctx.env, q) } else null
fun MusicProvider.trackUrl(ctx: Context, trackId: String): String? = trackUrl(ctx.env, trackId)
suspend fun MusicProvider.searchWide(ctx: Context, track: Track): List<Track> = searchWide(ctx.env, track)
suspend fun MusicProvider.track(ctx: Context, trackId: String): Track? = track(ctx.env, trackId)
suspend fun MusicProvider.playlistInfo(ctx: Context, playlistId: String): Playlist = playlistInfo(ctx.env, playlistId)
suspend fun MusicProvider.playlistVersion(ctx: Context, playlistId: String): String? = playlistVersion(ctx.env, playlistId)
fun MusicProvider.canRead(ctx: Context, playlistId: String): Boolean = canRead(ctx.env, playlistId)

fun OAuthProvider.clientId(ctx: Context): String = clientId(ctx.env)
fun OAuthProvider.ownClientId(ctx: Context): String? = ownClientId(ctx.env)
fun OAuthProvider.ownClientSecret(ctx: Context): String? = ownClientSecret(ctx.env)
fun OAuthProvider.authUrl(ctx: Context, state: String, codeChallenge: String): String = authUrl(ctx.env, state, codeChallenge)
fun OAuthProvider.authUrl(ctx: Context, state: String, codeChallenge: String, redirect: String): String = authUrl(ctx.env, state, codeChallenge, redirect)
suspend fun CredentialsProvider.login(ctx: Context, url: String, user: String, secret: String) = login(ctx.env, url, user, secret)
fun AppleMusicProvider.developerToken(ctx: Context): String = developerToken(ctx.env)

fun LocalFilesProvider.dir(ctx: Context): File = dir(ctx.env)
fun LocalFilesProvider.importFile(ctx: Context, fileName: String, text: String): Int = importFile(ctx.env, fileName, text)
fun LocalFilesProvider.importTracks(ctx: Context, name: String, tracks: List<Track>): String = importTracks(ctx.env, name, tracks)
fun LocalFilesProvider.exportText(ctx: Context, playlistId: String, format: LocalFilesProvider.Export): String = exportText(ctx.env, playlistId, format)
fun LocalFilesProvider.delete(ctx: Context, playlistId: String) = delete(ctx.env, playlistId)
fun LocalFilesProvider.entries(ctx: Context): List<LocalFilesProvider.Entry> = entries(ctx.env)
fun LocalFilesProvider.writeBackup(
    ctx: Context, service: String, playlistName: String, tracks: List<Track>, protectedIds: Set<String>, now: Long = System.currentTimeMillis(),
): LocalFilesProvider.BackupOutcome = writeBackup(ctx.env, service, playlistName, tracks, protectedIds, now)
fun LocalFilesProvider.protectedIds(ctx: Context): Set<String> = protectedIds(ctx.env)

// Resource ids for the names the engine gives (connector notes, setup guides, login forms).
val MusicProvider.noteRes: Int get() = Res.id(noteName)
val MusicProvider.routeNoteRes: Int? get() = routeNoteName?.let { Res.id(it) }
val MusicProvider.notConfiguredRes: Int? get() = notConfiguredName?.let { Res.id(it) }
val SetupGuide.whyRes: Int get() = Res.id(whyName)
val SetupGuide.stepsArrayRes: Int get() = Res.id(stepsArrayName, "array")
val SetupGuide.fieldLabelRes: Int get() = Res.id(fieldLabelName)
val LoginForm.secretLabelRes: Int get() = Res.id(secretLabelName)
val LoginForm.hintRes: Int get() = Res.id(hintName)
