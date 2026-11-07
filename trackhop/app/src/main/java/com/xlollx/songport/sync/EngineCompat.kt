package com.xlollx.songport.sync

import android.content.Context
import com.xlollx.songport.platform.env

/** The engine on this Context's Env: the app's screens and worker build it from a Context. */
fun SyncEngine(ctx: Context): SyncEngine = SyncEngine(ctx.env)

fun SyncState.checkpoint(ctx: Context, jobId: String) = checkpoint(ctx.env, jobId)
fun SyncState.stopCheck(ctx: Context, jobId: String) = stopCheck(ctx.env, jobId)
