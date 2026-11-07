package com.xlollx.songport.providers

import android.content.Context
import com.xlollx.songport.platform.Env
import com.xlollx.songport.platform.android
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import com.xlollx.songport.BuildConfig

/**
 * Discovery of a "connector plugin": a separately installed app that implements Trackhop's open
 * connector interface (a ContentProvider answering `call()` methods, plus login activities).
 *
 * The plugin advertises itself with an activity handling [ACTION] and a `<meta-data>` entry naming
 * its provider authority. Trackhop never bundles, downloads or installs a plugin: it only uses one
 * the user has already installed. The direct-download build may show where to get one; the Google
 * Play build does not link to anything and simply offers the services when a plugin is present.
 *
 * The GitHub and F-Droid build carries the same connectors inside ([BuiltIn]): every call and
 * sign-in then stays in this app, and an installed plugin is not used.
 */
object BridgePlugin {
    const val ACTION = "com.xlollx.songport.action.CONNECTOR"
    private const val META_AUTHORITY = "com.xlollx.songport.connector.authority"

    // Known implementation for plugin versions that predate the discovery action (full build only).
    private const val LEGACY_PACKAGE = "com.xlollx.songport.ytmbridge"
    private const val LEGACY_AUTHORITY = "com.xlollx.songport.ytmbridge.provider"

    /** True when the connectors are built into this app (GitHub and F-Droid build). */
    val builtIn: Boolean get() = BuiltIn.available

    /**
     * A plugin reporting this "version" (or more) in its status answers every method the built-in
     * connectors do: albums, artists, history, rename, delete, reorder, visibility, unplayable tracks.
     * Trackhop Bridge has done so since 1.4.0.
     */
    const val FULL_VERSION = 34

    // The version the plugin's last status call reported; 0 until one has answered.
    @Volatile private var pluginVersion = 0

    /** Every feature of the built-in connectors is available, built in or through the plugin. */
    val full: Boolean get() = builtIn || pluginVersion >= FULL_VERSION

    /** Where to get a plugin: nowhere to go when the connectors are built in, and never in the Play build. */
    val installUrl: String? = if (BuildConfig.PLUGIN_LINKS && !BuiltIn.available) "https://github.com/xlollx/Trackhop-Bridge/releases/latest" else null

    class Info(val packageName: String, val authority: Uri)

    @Volatile private var cache: Pair<Long, Info?>? = null

    fun find(ctx: Context): Info? {
        cache?.let { (at, info) -> if (System.currentTimeMillis() - at < 10_000) return info }
        val pm = ctx.packageManager
        val ri = runCatching { pm.queryIntentActivities(Intent(ACTION), PackageManager.GET_META_DATA) }.getOrNull()?.firstOrNull()
        val info = if (ri != null) {
            val authority = ri.activityInfo.metaData?.getString(META_AUTHORITY) ?: LEGACY_AUTHORITY
            Info(ri.activityInfo.packageName, Uri.parse("content://$authority"))
        } else if (BuildConfig.PLUGIN_LINKS && runCatching { pm.getPackageInfo(LEGACY_PACKAGE, 0) }.isSuccess) {
            Info(LEGACY_PACKAGE, Uri.parse("content://$LEGACY_AUTHORITY"))
        } else null
        cache = System.currentTimeMillis() to info
        return info
    }

    fun installed(ctx: Context): Boolean = builtIn || find(ctx) != null

    /** An installed plugin as the plugins section shows it: its own name and version, nothing else. */
    class Installed(val packageName: String, val label: String, val version: String?)

    /** Every installed app that declares itself a plugin through [ACTION]; none in the GitHub/F-Droid build's view. */
    fun installedPlugins(ctx: Context): List<Installed> {
        val pm = ctx.packageManager
        return runCatching { pm.queryIntentActivities(Intent(ACTION), 0) }.getOrDefault(emptyList())
            .map { it.activityInfo.packageName }.distinct()
            .map { p ->
                Installed(
                    p,
                    runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p),
                    runCatching { pm.getPackageInfo(p, 0).versionName }.getOrNull(),
                )
            }
    }
    fun packageName(ctx: Context): String? = if (builtIn) ctx.packageName else find(ctx)?.packageName
    fun authority(ctx: Context): Uri = find(ctx)?.authority ?: Uri.parse("content://$LEGACY_AUTHORITY")

    /** One call to the connectors: in-process when built in, otherwise through the plugin's provider. */
    fun call(ctx: Context, method: String, arg: String? = null, extras: Bundle? = null): Bundle? =
        if (builtIn) BuiltIn.call(ctx, method, arg, extras)
        else ctx.contentResolver.call(authority(ctx), method, arg, extras)?.also {
            if (method == "status" || method.endsWith(".status")) {
                pluginVersion = it.getInt("version", 0)
                // A plugin that says `slots` (true or false) keys its sessions by slot; an older one says nothing.
                if (it.containsKey("slots")) pluginSlots = System.currentTimeMillis() to true
            }
        }

    // Whether the plugin takes a slot, and when that was last learnt; null until a status call answered.
    @Volatile private var pluginSlots: Pair<Long, Boolean>? = null

    /**
     * True when the installed plugin keys its sessions by slot. A plugin from before slots would
     * serve the primary account to every slot, so an extra account is never asked of it: asked
     * through `net.status`, and asked again after a minute when it said no (it may be updated).
     */
    private fun pluginTakesSlots(ctx: Context): Boolean {
        pluginSlots?.let { (at, ok) -> if (ok || System.currentTimeMillis() - at < 60_000) return ok }
        val ok = runCatching { call(ctx, "net.status")?.containsKey("slots") == true }.getOrDefault(false)
        pluginSlots = System.currentTimeMillis() to ok
        return ok
    }

    /**
     * The same call for one account of the service: an extra account ([slot] "2", "3"…) travels as
     * the `slot` extra, which the connectors key their sessions by (see [WebAccounts]). The primary
     * account (slot "") sends exactly what it always sent, so connectors from before slots serve it;
     * an extra account is not sent to a plugin that would not tell it apart (an error says so instead).
     */
    fun call(ctx: Context, method: String, arg: String?, extras: Bundle?, slot: String): Bundle? {
        if (slot.isEmpty()) return call(ctx, method, arg, extras)
        if (!builtIn && !pluginTakesSlots(ctx)) return Bundle().apply { putString("error", ctx.getString(com.xlollx.songport.R.string.th_web_accounts_need_plugin)) }
        return call(ctx, method, arg, withSlot(extras, slot))
    }

    /** [extras] with the `slot` extra when [slot] names an extra account; unchanged for the primary. */
    fun withSlot(extras: Bundle?, slot: String): Bundle? =
        if (slot.isEmpty()) extras else Bundle(extras ?: Bundle()).apply { putString(EXTRA_SLOT, slot) }

    /** The extra naming the account in the connectors' calls and sign-ins, as the Bridge reads it. */
    const val EXTRA_SLOT = "slot"

    /** Opens a connector's sign-in: the built-in screen, or the plugin's through its action; for an extra account, with its slot. */
    fun startLogin(ctx: Context, action: String, slot: String = "") {
        val intent = BuiltIn.loginIntent(ctx, action, slot) ?: Intent(action).setPackage(packageName(ctx) ?: return)
        if (slot.isNotEmpty()) intent.putExtra(EXTRA_SLOT, slot)
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // The same, from the engine's Env (the web connectors implement the engine's MusicProvider).
    fun installed(env: Env): Boolean = installed(env.android)
    fun packageName(env: Env): String? = packageName(env.android)
    fun call(env: Env, method: String, arg: String? = null, extras: Bundle? = null): Bundle? = call(env.android, method, arg, extras)
    fun call(env: Env, method: String, arg: String?, extras: Bundle?, slot: String): Bundle? = call(env.android, method, arg, extras, slot)
    fun startLogin(env: Env, action: String, slot: String = "") = startLogin(env.android, action, slot)
}
