package com.xlollx.songport.ai

import com.xlollx.songport.providers.*
import com.xlollx.songport.platform.Env
import com.xlollx.songport.data.Tokens
import com.xlollx.songport.model.ProviderException
import com.xlollx.songport.model.Track
import com.xlollx.songport.net.Http
import com.xlollx.songport.net.arr
import com.xlollx.songport.net.int
import com.xlollx.songport.net.get
import com.xlollx.songport.net.jsonArr
import com.xlollx.songport.net.jsonObj
import com.xlollx.songport.net.parseJson
import com.xlollx.songport.net.str
import com.xlollx.songport.sync.PlaylistFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Generatore di playlist con l'AI dell'utente. Trackhop non ha un'AI propria: l'utente porta la
 * chiave del suo servizio (OpenAI, Anthropic, Google Gemini o qualunque endpoint compatibile con
 * l'API di OpenAI, anche locale) e l'app gli manda solo la descrizione scritta qui. La risposta
 * e' una lista "artista - titolo" che poi passa dalla normale ricerca sul servizio di destinazione,
 * quindi errori e allucinazioni finiscono fra i "non trovati", da rivedere come sempre.
 */
object AiClient {

    enum class Vendor(val label: String, val defaultBase: String, val keyUrl: String, val limitsUrl: String = "") {
        OPENAI("OpenAI", "https://api.openai.com/v1", "https://platform.openai.com/api-keys", "https://platform.openai.com/settings/organization/limits"),
        ANTHROPIC("Anthropic", "https://api.anthropic.com/v1", "https://console.anthropic.com/settings/keys", "https://console.anthropic.com/settings/limits"),
        GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "https://aistudio.google.com/apikey", "https://aistudio.google.com/apikey"),
        /** Livello gratuito generoso; parla l'API di OpenAI. */
        GROQ("Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys", "https://console.groq.com/settings/limits"),
        /** Molti modelli, alcuni gratuiti (quelli con ":free"); parla l'API di OpenAI. */
        OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys", "https://openrouter.ai/settings/keys"),
        /** Ollama, LM Studio, Mistral…: tutti parlano l'API chat di OpenAI. */
        CUSTOM("OpenAI-compatible", "", ""),
        ;
        /** Where a key costs nothing to try. */
        val freeTier: Boolean get() = this == GEMINI || this == GROQ || this == OPENROUTER
    }

    data class Config(
        val vendor: Vendor, val apiKey: String, val model: String, val baseUrl: String,
        /** The image model of an OpenAI-compatible server (blank: that server makes no images here). */
        val imageModel: String = "",
        /** The phone's own model ([localModel]): no key, no network; [model] is its name. Never saved. */
        val local: Boolean = false,
    ) {
        val base: String get() = (baseUrl.ifBlank { vendor.defaultBase }).trimEnd('/')
        /** The only host the key is ever sent to. */
        val host: String get() = runCatching { java.net.URI(base).host }.getOrNull() ?: base
        val complete: Boolean get() = local || (model.isNotBlank() && (apiKey.isNotBlank() || vendor == Vendor.CUSTOM) && base.isNotBlank())
        /** What to call it on screen: the service, or the on-device model's name. */
        val label: String get() = if (local) model else vendor.label
    }

    /** A model that runs on the device itself (Gemini Nano on Android). The app sets [localModel]; the server has none. */
    interface LocalModel {
        suspend fun complete(system: String, user: String): String
    }

    @Volatile var localModel: LocalModel? = null

    /** Where the on-device model stands: missing, can be fetched, being fetched, ready. */
    enum class LocalState { NONE, DOWNLOADABLE, DOWNLOADING, READY;
        /** Worth offering: there already, or the system can fetch it. */
        val offered: Boolean get() = this != NONE
    }

    fun onDevice(name: String) = Config(Vendor.GEMINI, "", name, "", local = true)

    /**
     * Which AI a feature uses: the user's own key when one is set up (it is the better model), else
     * the on-device one when the phone has it or can fetch it, else none (the feature says it needs a key).
     */
    fun pick(saved: Config?, local: LocalState, localName: String): Config? = when {
        saved != null && !saved.local && saved.complete -> saved
        local.offered -> onDevice(localName)
        else -> null
    }

    /** A small on-device model gets short rounds and a short context (its window is about 4,000 tokens). */
    const val LOCAL_ROUND = 15
    fun roundFor(c: Config): Int = if (c.local) LOCAL_ROUND else ROUND

    internal fun fit(c: Config, r: Prompt): Prompt = if (!c.local) r else r.copy(
        count = minOf(r.count, LOCAL_ROUND),
        seeds = r.seeds.take(15), existing = r.existing.takeLast(40), unavailable = r.unavailable.takeLast(20),
    )

    data class Prompt(
        val description: String,
        val count: Int,
        /** Brani di riferimento ("simili a"), gia' in forma "Artista - Titolo". */
        val seeds: List<String> = emptyList(),
        val language: String = "",
        /** Brani gia' nella playlist quando la si allunga: stesso spirito, nessuno di questi. */
        val existing: List<String> = emptyList(),
        /** Proposals the destination service turned out not to have: not again, and nothing like them by name only. */
        val unavailable: List<String> = emptyList(),
    )

    private const val STORE_ID = "__ai__"

    fun config(env: Env): Config? = env.tokens.get(STORE_ID)?.let { t ->
        Config(
            vendor = runCatching { Vendor.valueOf(t.extra["vendor"] ?: "") }.getOrDefault(Vendor.OPENAI),
            apiKey = t.accessToken,
            model = t.extra["model"].orEmpty(),
            baseUrl = t.extra["baseUrl"].orEmpty(),
            imageModel = t.extra["imageModel"].orEmpty(),
        )
    }

    fun save(env: Env, c: Config) {
        if (c.local) return
        env.tokens.set(STORE_ID, Tokens(accessToken = c.apiKey, extra = mapOf("vendor" to c.vendor.name, "model" to c.model, "baseUrl" to c.baseUrl, "imageModel" to c.imageModel)))
    }

    fun clear(env: Env) = env.tokens.clear(STORE_ID)

    // Generare 50 brani puo' prendere piu' dei 40 s del client condiviso.
    private val client = Http.client.newBuilder().readTimeout(180, TimeUnit.SECONDS).build()

    private suspend fun post(url: String, headers: Map<String, String>, body: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(Http.jsonBody(body)).build()
        // "Sovraccarico, riprova" (503/529) e i limiti di frequenza (429) passano da soli in pochi
        // secondi: si riprova tre volte con attese crescenti prima di darlo per fallito.
        var attempt = 0
        var result = 0 to ""
        while (true) {
            result = client.newCall(req).execute().use { resp -> resp.code to (resp.body?.string() ?: "") }
            if (result.first in RETRY_CODES && attempt < 3) {
                attempt++
                delay(3000L * attempt)
                continue
            }
            break
        }
        if (result.first !in 200..299) throw ProviderException(errorMessage(result.first, result.second))
        result.second
    }

    private val RETRY_CODES = setOf(429, 500, 502, 503, 529)

    private fun errorMessage(code: Int, body: String): String {
        val j = runCatching { parseJson(body) }.getOrNull()
        val msg = j["error"]["message"].str ?: j["error"].str ?: j["message"].str
        return "AI $code" + (msg?.let { ": ${it.take(200)}" } ?: "")
    }

    /** I modelli disponibili con questa chiave, per non farli scrivere a mano. */
    suspend fun models(c: Config): List<String> = if (c.local) listOf(c.model) else withContext(Dispatchers.IO) {
        val (url, headers) = when (c.vendor) {
            Vendor.ANTHROPIC -> "${c.base}/models?limit=100" to mapOf("x-api-key" to c.apiKey, "anthropic-version" to ANTHROPIC_VERSION)
            Vendor.GEMINI -> "${c.base}/models?pageSize=200&key=${Http.enc(c.apiKey)}" to emptyMap<String, String>()
            else -> "${c.base}/models" to bearer(c)
        }
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
        val text = client.newCall(req).execute().use { resp ->
            val t = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ProviderException(errorMessage(resp.code, t))
            t
        }
        val j = parseJson(text)
        val ids = when (c.vendor) {
            Vendor.GEMINI -> j["models"].arr.filter { m -> m["supportedGenerationMethods"].arr.any { it.str == "generateContent" } }
                .mapNotNull { it["name"].str?.removePrefix("models/") }
            else -> j["data"].arr.mapNotNull { it["id"].str }
        }
        // Anthropic lists newest first; the others come in no useful order.
        if (c.vendor == Vendor.ANTHROPIC) ids.distinct() else ids.distinct().sorted()
    }

    private val DATED = Regex("""-(\d{4}-\d{2}-\d{2}|\d{4}|\d{2}-\d{2}|\d{3}|preview-\d.*|exp.*)$""")
    private val NOT_CHAT = listOf(
        "embedding", "embed", "aqa", "tts", "audio", "image", "imagen", "veo", "vision", "live", "realtime",
        "transcribe", "whisper", "dall-e", "moderation", "search", "instruct", "davinci", "babbage", "ada", "curie",
        "codex", "computer-use", "learnlm", "gemma", "robotics", "native", "deep-research",
    )

    /**
     * La lista dell'API contiene di tutto: modelli per immagini, audio, embedding, versioni datate
     * e generazioni ritirate. Qui restano quelli che chattano e sono correnti; l'elenco intero si
     * apre a parte. Anthropic espone solo i modelli attivi, e' gia' pulito.
     */
    fun curated(vendor: Vendor, models: List<String>): List<String> {
        if (vendor == Vendor.ANTHROPIC || vendor == Vendor.CUSTOM) return models
        // OpenRouter is chosen for its free models: those first, the paid catalogue behind "show all".
        if (vendor == Vendor.OPENROUTER) return models.filter { it.endsWith(":free") }.ifEmpty { models }
        if (vendor == Vendor.GROQ) return models.filter { m -> val l = m.lowercase(); NOT_CHAT.none { it in l } && "guard" !in l }
        val chat = models.filter { m -> val l = m.lowercase(); NOT_CHAT.none { it in l } && "-exp" !in l && "experimental" !in l }
        val current = chat.filter { m ->
            val l = m.lowercase()
            when (vendor) {
                Vendor.GEMINI -> l.startsWith("gemini-") && !l.startsWith("gemini-1.")
                else -> (l.startsWith("gpt-") && !l.startsWith("gpt-3")) || Regex("""^o\d""").containsMatchIn(l)
            }
        }
        // A dated or preview variant is hidden when its plain name is also there.
        val plain = current.filter { !DATED.containsMatchIn(it) }.toSet()
        val kept = current.filter { m -> m in plain || DATED.replace(m, "") !in plain }
        return kept.sortedWith(compareByDescending<String> { versionOf(it) }.thenBy { it })
    }

    private fun versionOf(m: String): Double = Regex("""(\d+(?:\.\d+)?)""").find(m)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    private fun bearer(c: Config): Map<String, String> = if (c.apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${c.apiKey}")

    private const val ANTHROPIC_VERSION = "2023-06-01"

    private fun system(r: Prompt): String = """
        You build music playlists. Reply with ONLY a JSON array, no prose, no code fences.
        Each element is {"artist": "...", "title": "...", "album": "..."} with real, existing recordings;
        use the main artist's name as it appears on streaming services and the original song title,
        with no year, no annotations. Exactly ${r.count} distinct tracks: never the same recording twice, not
        even as a remaster, live or alternate version, and never a track the request lists as already present.
        No repeated artist more than 3 times unless asked. ${if (r.language.isNotBlank()) "Song choice may follow this request language: ${r.language}." else ""}
    """.trimIndent()

    private fun user(r: Prompt): String = buildString {
        val wish = r.description.trim().ifEmpty { "Continue this playlist in the same spirit as the tracks it already contains (genre, era, mood, energy)." }
        append("Playlist request: ").append(wish).append('\n')
        if (r.seeds.isNotEmpty()) {
            append("The listener already likes these; pick tracks in the same spirit, do not include them:\n")
            r.seeds.forEach { append("- ").append(it).append('\n') }
        }
        if (r.existing.isNotEmpty()) {
            append("The playlist already contains these tracks; add new ones that fit with them, and do not repeat any of them:\n")
            r.existing.forEach { append("- ").append(it).append('\n') }
        }
        if (r.unavailable.isNotEmpty()) {
            append("These were proposed before and are NOT available on the listener's streaming service; do not propose them again:\n")
            r.unavailable.forEach { append("- ").append(it).append('\n') }
        }
        append("Number of NEW tracks to return: ").append(r.count)
    }

    /** La playlist proposta, come brani senza id (li trovera' la ricerca del servizio di destinazione). */
    suspend fun generate(c: Config, r: Prompt): List<Track> = fit(c, r).let { parseTracks(complete(c, system(it), user(it))) }

    /** One answer holds about a hundred tracks at most: a larger request goes in rounds, each told what came before. */
    const val ROUND = 100

    suspend fun generateAll(c: Config, r: Prompt): List<Track> {
        val round = roundFor(c)
        if (r.count <= round) return generate(c, r)
        val out = ArrayList<Track>()
        var rounds = 0
        while (out.size < r.count && rounds < r.count / round + 3) {
            rounds++
            val ask = r.copy(count = minOf(round, r.count - out.size), existing = (r.existing + out.map { it.toString() }).distinct().take(400))
            val got = AiMatch.fresh(generate(c, ask), out)
            if (got.isEmpty()) break
            out += got
        }
        return out.take(r.count)
    }

    data class Suggestion(val match: Int?, val queries: List<String>, val note: String)

    /**
     * Aiuto nella revisione di un brano non trovato: dati il brano d'origine e i risultati gia' visti,
     * il modello indica quale risultato e' lo stesso brano (se c'e'), oppure con quali altre parole
     * cercarlo (titolo originale, artista principale, versione), o dice che sul servizio non c'e'.
     */
    suspend fun suggestMatch(c: Config, source: Track, candidates: List<Track>, service: String, language: String): Suggestion {
        val system = """
            You help a playlist sync app match one recording on $service. Reply with ONLY a JSON object:
            {"match": <1-based number of the candidate that is the same recording, or null>,
             "queries": [<up to 3 alternative search strings likely to find it on $service, empty if it is probably not there>],
             "note": "<one short sentence in $language explaining, for a person>"}
            A remaster, the album version or the same song on a compilation counts as a match; a live, cover,
            karaoke, remix or sped-up version does not. Good alternative queries: the original title when the
            given one is a translation or alias, the main artist when the listed one is a guest, the title alone.
        """.trimIndent()
        val user = buildString {
            append("Track to find: ").append(source.toString())
            if (source.album.isNotBlank()) append(" · album: ").append(source.album)
            if (source.durationMs > 0) append(" · ").append(source.durationMs / 1000).append(" s")
            append('\n')
            if (candidates.isEmpty()) append("No candidates were found.\n")
            else {
                append("Candidates found on ").append(service).append(":\n")
                candidates.take(15).forEachIndexed { i, t ->
                    append(i + 1).append(". ").append(t.toString())
                    if (t.album.isNotBlank()) append(" · ").append(t.album)
                    if (t.durationMs > 0) append(" · ").append(t.durationMs / 1000).append(" s")
                    append('\n')
                }
            }
        }
        val text = complete(c, system, user)
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        val j = parseJson(if (start >= 0 && end > start) text.substring(start, end + 1) else text)
        val match = j["match"].int?.takeIf { it in 1..candidates.size }
        val queries = j["queries"].arr.mapNotNull { it.str?.trim()?.takeIf { q -> q.isNotEmpty() } }.take(3)
        return Suggestion(match, queries, j["note"].str.orEmpty())
    }

    /**
     * Prova il modello con una richiesta minima: chiave, quota, modello ritirato o non abilitato
     * danno errori diversi, e conviene vederli subito invece che a playlist descritta. Null = va bene.
     */
    suspend fun check(c: Config): String? = try {
        val text = complete(c, "Reply with the single word OK.", "ping")
        if (text.isBlank()) "Empty answer" else null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    /** Una richiesta, una risposta testuale, qualunque sia il fornitore. */
    internal suspend fun complete(c: Config, system: String, user: String): String {
        if (c.local) return (localModel ?: throw ProviderException("On-device AI not available")).complete(system, user)
        return when (c.vendor) {
            Vendor.ANTHROPIC -> {
                val body = jsonObj(
                    "model" to c.model, "max_tokens" to 8192, "system" to system,
                    "messages" to jsonArr(listOf(jsonObj("role" to "user", "content" to user))),
                )
                val resp = post("${c.base}/messages", mapOf("x-api-key" to c.apiKey, "anthropic-version" to ANTHROPIC_VERSION), body.toString())
                parseJson(resp)["content"].arr.mapNotNull { it["text"].str }.joinToString("")
            }
            Vendor.GEMINI -> {
                val body = jsonObj(
                    "systemInstruction" to jsonObj("parts" to jsonArr(listOf(jsonObj("text" to system)))),
                    "contents" to jsonArr(listOf(jsonObj("role" to "user", "parts" to jsonArr(listOf(jsonObj("text" to user)))))),
                    "generationConfig" to jsonObj("responseMimeType" to "application/json"),
                )
                val resp = post("${c.base}/models/${c.model}:generateContent?key=${Http.enc(c.apiKey)}", emptyMap(), body.toString())
                parseJson(resp)["candidates"][0]["content"]["parts"].arr.mapNotNull { it["text"].str }.joinToString("")
            }
            else -> {
                val body = jsonObj(
                    "model" to c.model,
                    "messages" to jsonArr(listOf(jsonObj("role" to "system", "content" to system), jsonObj("role" to "user", "content" to user))),
                )
                // OpenRouter asks apps to say who they are; the others ignore the headers.
                val headers = bearer(c) + mapOf("X-Title" to "Vivi")
                val resp = post("${c.base}/chat/completions", headers, body.toString())
                parseJson(resp)["choices"][0]["message"]["content"].str ?: ""
            }
        }
    }

    // ------------------------------------------------------------------ images

    /** Whether this configuration can draw a picture, and why not when it cannot. */
    enum class ImageSupport { YES, NO_IMAGES, NEEDS_IMAGE_MODEL }

    fun imageSupport(c: Config): ImageSupport = if (c.local) ImageSupport.NO_IMAGES else when (c.vendor) {
        Vendor.OPENAI, Vendor.GEMINI -> ImageSupport.YES
        Vendor.CUSTOM -> if (c.imageModel.isBlank()) ImageSupport.NEEDS_IMAGE_MODEL else ImageSupport.YES
        // Claude does not make images; Groq and OpenRouter have no image endpoint of this kind.
        Vendor.ANTHROPIC, Vendor.GROQ, Vendor.OPENROUTER -> ImageSupport.NO_IMAGES
    }

    const val GEMINI_IMAGE_MODEL = "gemini-2.5-flash-image"

    // An image takes longer than a chat answer, but not three minutes.
    private val imageClient = Http.client.newBuilder().readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()

    /**
     * A square picture drawn by the user's own AI from [prompt], as PNG/JPEG bytes. OpenAI:
     * gpt-image-1, else dall-e-3; Gemini: [GEMINI_IMAGE_MODEL]; an OpenAI-compatible server: its
     * image model. Errors name what went wrong (key, quota, content policy, model not enabled).
     */
    suspend fun generateImage(c: Config, prompt: String): ByteArray = withContext(Dispatchers.IO) {
        if (c.local) throw ProviderException("${c.model} does not make images")
        when (c.vendor) {
            Vendor.GEMINI -> {
                val body = jsonObj(
                    "contents" to jsonArr(listOf(jsonObj("role" to "user", "parts" to jsonArr(listOf(jsonObj("text" to prompt)))))),
                    "generationConfig" to jsonObj("responseModalities" to jsonArr(listOf("IMAGE"))),
                )
                val (code, text) = postImage("${c.base}/models/$GEMINI_IMAGE_MODEL:generateContent?key=${Http.enc(c.apiKey)}", emptyMap(), body.toString())
                if (code == 404) throw ProviderException("Gemini: $GEMINI_IMAGE_MODEL is not available for this key")
                if (code !in 200..299) throw ProviderException(imageError(code, text))
                val parts = parseJson(text)["candidates"][0]["content"]["parts"].arr
                val data = parts.firstNotNullOfOrNull { it["inlineData"]["data"].str ?: it["inline_data"]["data"].str }
                    ?: throw ProviderException("Gemini made no image" + (parseJson(text)["candidates"][0]["finishReason"].str?.let { " ($it)" } ?: ""))
                java.util.Base64.getDecoder().decode(data)
            }
            Vendor.OPENAI -> runCatching { openAiImage(c, "gpt-image-1", prompt, b64Param = false) }.getOrElse { e ->
                // A key without access to gpt-image-1 (organisation not verified, model not offered): DALL·E 3.
                val m = e.message.orEmpty().lowercase()
                if ("model" in m || "verif" in m || "not found" in m || "does not exist" in m) openAiImage(c, "dall-e-3", prompt, b64Param = true) else throw e
            }
            Vendor.CUSTOM -> openAiImage(c, c.imageModel, prompt, b64Param = true)
            else -> throw ProviderException("${c.vendor.label} does not make images")
        }
    }

    private fun openAiImage(c: Config, model: String, prompt: String, b64Param: Boolean): ByteArray {
        val fields = mutableListOf<Pair<String, Any?>>("model" to model, "prompt" to prompt, "size" to "1024x1024", "n" to 1)
        if (b64Param) fields += "response_format" to "b64_json"
        val body = jsonObj(*fields.toTypedArray()).toString()
        val (code, text) = postImage("${c.base}/images/generations", bearer(c), body)
        if (code !in 200..299) throw ProviderException(imageError(code, text))
        val item = parseJson(text)["data"][0]
        item["b64_json"].str?.let { return java.util.Base64.getDecoder().decode(it) }
        // Some compatible servers answer with a link instead.
        val url = item["url"].str ?: throw ProviderException("AI image: empty answer")
        return imageClient.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw ProviderException("AI image ${r.code}")
            r.body?.bytes() ?: throw ProviderException("AI image: empty answer")
        }
    }

    private fun postImage(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.post(Http.jsonBody(body)).build()
        return imageClient.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
    }

    /** The service's own reason, said plainly for the cases people meet: key, quota, content policy. */
    private fun imageError(code: Int, body: String): String {
        val base = errorMessage(code, body)
        val l = body.lowercase()
        return when {
            code == 401 || (code == 403 && "key" in l) -> "$base (key refused)"
            // Google gives its image models no free quota: a free AI Studio key gets 429 "limit: 0".
            code == 429 && ("free_tier" in l || "limit: 0" in l) -> "$base (Gemini draws images only on a key with billing turned on: the free tier has no image quota)"
            code == 429 || "quota" in l || "billing" in l || "insufficient" in l -> "$base (quota or billing)"
            "safety" in l || "content_policy" in l || "policy" in l || "blocked" in l -> "$base (refused by the content policy)"
            else -> base
        }
    }

    /** Dal testo del modello alla lista: si isola l'array JSON (i modelli aggiungono a volte prosa o recinti). */
    fun parseTracks(text: String): List<Track> {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        val body = if (start >= 0 && end > start) text.substring(start, end + 1) else text
        val tracks = PlaylistFiles.parseJson(body).ifEmpty { PlaylistFiles.parseText(text) }
        return tracks.distinctBy { AiMatch.key(it) }
    }
}
