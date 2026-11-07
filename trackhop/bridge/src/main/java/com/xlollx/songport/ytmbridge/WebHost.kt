package com.xlollx.songport.ytmbridge

/**
 * What the Spotify and Deezer web clients ([SpotifyWebClient], [DeezerWebClient]) need from where
 * they run. In the app it is the WebView sign-in, kept with the Keystore ([WebSession]); on the
 * server (server/build.gradle compiles this file and those clients from here) it is the cookies the
 * user pasted and a file in its data folder. No Android in this file: the server has none.
 */
interface WebHost {
    /** The session's cookies, as a `Cookie` header; null when not connected. */
    fun cookies(): String?
    /** The account name saved at sign-in. */
    fun account(): String?
    /** A small value kept with the session (cached tokens, query hashes, the account id). */
    fun get(key: String): String?
    /** Keeps [value] under [key]; null forgets it. */
    fun put(key: String, value: String?)
    /** The desktop Chrome user agent the requests carry. */
    val userAgent: String
    /** Which account of the service this is: "" for the primary (and on the server), "2", "3"… for an extra one. */
    val slot: String get() = ""
    /**
     * What the clients' process-wide caches (the player page, the signed-in user) are kept under:
     * the account's slot, or on a server with several people each person's data folder and account,
     * so no session ever reads another's.
     */
    val cacheScope: String get() = slot

    companion object {
        /** The value of cookie [name] in a `Cookie` header; null when absent or empty. */
        fun cookieValue(cookies: String?, name: String): String? = cookies?.split(';')
            ?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }
    }
}
