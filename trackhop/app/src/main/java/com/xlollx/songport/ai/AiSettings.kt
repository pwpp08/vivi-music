package com.xlollx.songport.ai

import android.content.Context
import com.xlollx.songport.platform.env

// AiClient lives in core, shared with the server's web page; the app keeps calling it with a Context.

fun AiClient.config(ctx: Context): AiClient.Config? = config(ctx.env)

fun AiClient.save(ctx: Context, c: AiClient.Config) = save(ctx.env, c)

fun AiClient.clear(ctx: Context) = clear(ctx.env)

suspend fun AiMatch.available(
    ctx: Context, c: AiClient.Config, provider: com.xlollx.songport.providers.MusicProvider, prompt: AiClient.Prompt,
    existing: List<com.xlollx.songport.model.Track>, onProgress: (Int, Int) -> Unit = { _, _ -> },
) = available(ctx.env, c, provider, prompt, existing, onProgress)
