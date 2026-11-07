package com.xlollx.songport.ytmbridge

import kotlinx.serialization.json.JsonArray

/**
 * Cookies pasted by a person, for a client with no browser of its own (the server): the `Cookie`
 * request header copied from the browser's developer tools ("cookie: a=b; c=d", with or without its
 * name), a Netscape `cookies.txt` export, or a JSON export (a list of objects with domain, name and
 * value, as cookie editor extensions write it). Only YouTube's and Google's cookies are kept; the
 * server's other cookie connectors (Spotify, Deezer) read theirs with [header].
 */
object YtmCookies {
    /** Domains whose cookies music.youtube.com receives or the session needs. */
    private val DOMAINS = listOf("youtube.com", "google.com")

    /** The `Cookie` header for music.youtube.com, or null when [text] holds no signed-in session (no SAPISID). */
    fun parse(text: String): String? = header(text, DOMAINS)?.takeIf { YtmAuth.sapisid(it) != null }

    /** The `Cookie` header made of [text]'s cookies for [domains] (and their subdomains); null when there are none. */
    fun header(text: String, domains: List<String>): String? {
        val t = text.trim().removePrefix("﻿")
        if (t.isEmpty()) return null
        val pairs = when {
            t.startsWith("[") -> fromJson(t, domains)
            looksNetscape(t) -> fromNetscape(t, domains)
            else -> fromHeader(t)
        }
        // A later copy of a name wins, as the most specific one a browser would list last.
        val map = LinkedHashMap<String, String>()
        for ((k, v) in pairs) if (k.isNotEmpty() && isToken(k)) map[k] = v
        if (map.isEmpty()) return null
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** Tab-separated lines with seven fields: domain, subdomains, path, secure, expiry, name, value. */
    private fun looksNetscape(t: String): Boolean =
        t.startsWith("# Netscape") || t.startsWith("# HTTP Cookie") ||
            t.lineSequence().any { line -> !line.startsWith("#") && line.split('\t').size >= 7 } ||
            t.lineSequence().any { it.startsWith("#HttpOnly_") }

    private fun fromNetscape(t: String, domains: List<String>): List<Pair<String, String>> = t.lineSequence().mapNotNull { raw ->
        // "#HttpOnly_" marks an HttpOnly cookie, not a comment: those are the ones that matter most.
        val line = raw.trimEnd('\r').removePrefix("#HttpOnly_")
        if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
        val f = line.split('\t')
        if (f.size < 7) return@mapNotNull null
        if (!ours(f[0], domains)) return@mapNotNull null
        f[5].trim() to f.subList(6, f.size).joinToString("\t").trim()
    }.toList()

    private fun fromHeader(t: String): List<Pair<String, String>> {
        // A whole header line ("cookie: ..." or "Cookie: ...") or only its value; one line or several.
        val value = t.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("; ") { line -> if (line.substringBefore(':').trim().equals("cookie", true)) line.substringAfter(':') else line }
        return value.split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }
    }

    private fun fromJson(t: String, domains: List<String>): List<Pair<String, String>> {
        val arr = runCatching { parseJson(t) }.getOrNull() as? JsonArray ?: return emptyList()
        return arr.mapNotNull { c ->
            val domain = c["domain"].str ?: c["host"].str ?: ""
            if (domain.isNotEmpty() && !ours(domain, domains)) return@mapNotNull null
            val name = c["name"].str ?: return@mapNotNull null
            name to (c["value"].str ?: return@mapNotNull null)
        }
    }

    private fun ours(domain: String, domains: List<String>): Boolean {
        val d = domain.trim().trimStart('.').lowercase()
        return domains.any { d == it || d.endsWith(".$it") }
    }

    /** A cookie name as RFC 6265 allows it: no separators, spaces or controls. */
    private fun isToken(name: String): Boolean = name.all { it.code in 0x21..0x7e && it !in "()<>@,;:\\\"/[]?={}" }
}
