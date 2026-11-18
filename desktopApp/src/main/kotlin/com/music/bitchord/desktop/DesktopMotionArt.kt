package com.music.bitchord.desktop

import com.music.bitchord.data.AppleCatalogue
import com.music.bitchord.data.Http
import com.music.bitchord.data.canvas.CanvasArtwork
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * Apple Music's motion artwork, for Home's Top Picks.
 *
 * The phone's `CanvasRepository` and `AppleArtistArtRepository` live in `:app`
 * and answer through Android's settings object; this is the same two lookups
 * without that, for the desktop.
 *
 * Both are public, unauthenticated reads of somebody else's service, and both
 * will happily answer a search with the wrong record, so every answer is
 * re-checked against what was asked for ([CanvasArtwork.matches] for a
 * release, a shared catalogue title for an artist) and any failure — network,
 * parse, mismatch — is just no clip. The still artwork is always underneath.
 */
internal object DesktopMotionArt {

    private const val TAG = "DesktopMotionArt"
    private const val AMP = "https://amp-api.music.apple.com/v1/catalog"
    private const val WEB_PLAYER = "https://music.apple.com/us/browse"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Motion artwork is per storefront; the machine's own, when it looks sane. */
    private val storefront: String by lazy {
        Locale.getDefault().country.takeIf { it.length == 2 }?.lowercase(Locale.ROOT) ?: "us"
    }

    /** True once a scrape has failed, so every card does not pay for it again. */
    private var tokenRetryAfterMs = 0L

    // ---- Releases ------------------------------------------------------

    /**
     * The motion artwork behind a release, for a Top Picks card. Only Apple is
     * asked: it is the first source the phone's repository tries, and the ones
     * behind it are for back catalogue the shelf does not reach. Null is the
     * normal answer.
     */
    suspend fun releaseMotion(album: String, artist: String): CanvasArtwork? {
        val name = album.trim()
        val credit = artist.trim()
        if (name.isEmpty() || credit.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            runCatching { searchAlbum(name, credit) }
                .onFailure { DesktopTrackLog.log("$TAG: release lookup failed: ${it.message}") }
                .getOrNull()
                ?.takeIf { it.matches(name, credit, name) }
        }
    }

    /** Albums carry `editorialVideo` inline on the search result — no second lookup. */
    private fun searchAlbum(album: String, artist: String): CanvasArtwork? {
        val bearer = token() ?: return null
        val term = if (album.contains(artist, ignoreCase = true)) album else "$artist $album"
        val url = "$AMP/$storefront/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("types", "albums")
            .addQueryParameter("limit", "10")
            .addQueryParameter("extend", "editorialVideo")
            .build()
            .toString()

        val body = get(url, bearer) ?: return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val hits = root["results"]?.jsonObject
            ?.get("albums")?.jsonObject
            ?.get("data")?.jsonArray
            ?: return null

        val ranked = hits.mapNotNull { hit ->
            val record = hit as? JsonObject ?: return@mapNotNull null
            // An album is its own "album" as far as the scoring goes, which is
            // what keeps a deluxe edition from outranking the plain one.
            val score = score(record, album, artist, album, albumIsSelf = true)
                ?: return@mapNotNull null
            score to record
        }.sortedByDescending { it.first }

        for ((score, record) in ranked) {
            if (score < MIN_SCORE) break
            val attributes = record["attributes"]?.jsonObject ?: continue
            val name = attributes.str("name") ?: continue
            if (isCompilation(name)) continue
            val video = attributes["editorialVideo"]?.jsonObject ?: continue
            val (primary, alternate) = motionUrls(video) ?: continue
            DesktopTrackLog.log("$TAG: motion artwork for album '$name'")
            return CanvasArtwork(
                url = primary,
                fallbackUrl = alternate,
                title = name,
                artist = attributes.str("artistName"),
                album = name,
            )
        }
        return null
    }

    // ---- Artists -------------------------------------------------------

    /**
     * Apple's looping artist clip, for a Top Picks card, off the same lookup
     * the phone's artist page makes. [catalogue] is the page's own song and
     * release titles — a name is not an identity, and a same-named Apple
     * artist is only taken when their catalogue shares something with it.
     */
    suspend fun artistMotion(
        artistName: String,
        catalogue: suspend () -> Collection<String>,
    ): CanvasArtwork? {
        val key = AppleCatalogue.matchKey(artistName)
        if (key.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val titles = AppleCatalogue.keys(runCatching { catalogue() }.getOrDefault(emptyList()))
                if (titles.isEmpty()) return@runCatching null
                clipFor(artistName, key, titles)
            }.onFailure { DesktopTrackLog.log("$TAG: artist lookup failed: ${it.message}") }
                .getOrNull()
        }
    }

    private fun clipFor(artistName: String, key: String, titles: Set<String>): CanvasArtwork? {
        val search = get(
            "https://itunes.apple.com/search?term=${URLEncoder.encode(artistName, "UTF-8")}" +
                "&entity=musicArtist&limit=5",
            bearer = null,
        ) ?: return null
        val results = runCatching { json.parseToJsonElement(search).jsonObject["results"]?.jsonArray }
            .getOrNull() ?: return null

        var page: String? = null
        for (element in results) {
            val result = element as? JsonObject ?: continue
            val name = result.str("artistName") ?: continue
            if (AppleCatalogue.matchKey(name) != key) continue
            val id = result.str("artistId")?.toLongOrNull()?.takeIf { it > 0 } ?: continue
            // Every same-named candidate is checked, not just the first: the
            // one Apple ranks highest is often the wrong one of two.
            if (!AppleCatalogue.sharesTitles(id, titles)) continue
            page = result.str("artistLinkUrl")?.substringBefore('?')?.takeIf { it.isNotBlank() }
                ?: "https://music.apple.com/us/artist/$id"
            break
        }
        if (page == null) return null

        val html = get(page, bearer = null) ?: return null
        return parseArtistPage(html)
    }

    /** The header art out of the page's hydration JSON, which is not an API. */
    private fun parseArtistPage(html: String): CanvasArtwork? {
        val marker = html.indexOf("id=\"serialized-server-data\"")
        if (marker < 0) return null
        val start = html.indexOf('>', marker) + 1
        val end = html.indexOf("</script>", start)
        if (start <= 0 || end < 0) return null
        val header = findHeader(
            runCatching { json.parseToJsonElement("{\"d\":${html.substring(start, end)}}").jsonObject["d"] }
                .getOrNull(),
        ) ?: return null

        val clip = header["videoArtwork"]?.jsonObject
            ?.get("dictionary")?.jsonObject
            ?.get("motionArtistSquare1x1")?.jsonObject
        val video = link(clip, "video")?.takeIf { it.startsWith("http") } ?: return null
        return CanvasArtwork(url = video)
    }

    /** The first object anywhere in [node] that has an `artistLogo` key. */
    private fun findHeader(node: JsonElement?): JsonObject? {
        when (node) {
            is JsonObject -> {
                if (node.containsKey("artistLogo")) return node
                for (value in node.values) findHeader(value)?.let { return it }
            }
            is JsonArray -> for (element in node) findHeader(element)?.let { return it }
            else -> Unit
        }
        return null
    }

    // ---- Search scoring ------------------------------------------------

    /**
     * The floor a hit has to clear. An exact artist and an exact title alone
     * reach 25, so this only ever admits a result that matched on both, or one
     * that matched the artist plus a fuzzy title and the right album.
     */
    private const val MIN_SCORE = 12

    private fun score(
        record: JsonObject,
        title: String,
        artist: String,
        album: String?,
        albumIsSelf: Boolean = false,
    ): Int? {
        val attributes = record["attributes"]?.jsonObject ?: return null
        val hitName = attributes.str("name").orEmpty()
        val hitArtist = attributes.str("artistName").orEmpty()
        val hitAlbum = if (albumIsSelf) hitName else attributes.str("albumName").orEmpty()

        if (isCompilation(hitName) || isCompilation(hitAlbum)) return null

        val wanted = splitArtists(artist)
        val credited = splitArtists(hitArtist)
        if (wanted.isEmpty() || credited.isEmpty()) return null
        if (!wanted.all { want -> credited.any { it == want } }) return null

        var score = 10
        val wantTitle = title.normalizeForMatch()
        val hitTitle = hitName.normalizeForMatch()
        score += when {
            hitTitle == wantTitle -> 15
            hitTitle.contains(wantTitle) || wantTitle.contains(hitTitle) -> 7
            else -> -10
        }

        if (!album.isNullOrBlank() && hitAlbum.isNotBlank()) {
            val wantAlbum = album.normalizeForMatch()
            val gotAlbum = hitAlbum.normalizeForMatch()
            score += when {
                gotAlbum == wantAlbum -> 20
                gotAlbum.contains(wantAlbum) || wantAlbum.contains(gotAlbum) -> 10
                else -> 0
            }
        }

        for (word in EDITION_WORDS) {
            val inWanted = title.contains(word, ignoreCase = true)
            val inHit = hitName.contains(word, ignoreCase = true)
            if (inWanted && inHit) score += 5 else if (inHit) score -= 3
        }
        return score
    }

    private val EDITION_WORDS =
        listOf("deluxe", "expanded", "remastered", "remix", "version", "edit", "mix", "bonus")

    private fun isCompilation(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return COMPILATION_MARKERS.any { lower.contains(it) }
    }

    private val COMPILATION_MARKERS = listOf(
        "playlist", "set list", "essentials", "dj mix", "mixed",
        "apple music", "today's hits", "session",
    )

    /** An artist credit is a list: "A & B", "A feat. B" and "A, B" all name two. */
    private fun splitArtists(credit: String): List<String> =
        credit.split(ARTIST_SPLIT)
            .map { it.normalizeForMatch() }
            .filter { it.isNotEmpty() }
            .distinct()

    private val ARTIST_SPLIT = Regex(
        """\s*(?:,|&|/|feat\.?|ft\.?|featuring|with|x|×|\band\b)\s*""",
        RegexOption.IGNORE_CASE,
    )

    private fun String.normalizeForMatch(): String =
        lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ---- Clip renditions -----------------------------------------------

    private fun link(node: JsonObject?, key: String): String? =
        node?.get(key)?.let { it as? JsonObject }?.let { asset ->
            asset.str("video") ?: asset.str("videoUrl") ?: asset.str("hlsUrl") ?: asset.str("url")
        }?.takeIf { it.isNotBlank() }

    /** The 3:4 rendition Apple's own player runs under its phone screen. */
    private fun tallUrl(video: JsonObject): String? =
        link(video, "motionDetailTall") ?: link(video, "motionTallVideo3x4")

    /** The square rendition first — it fills a square card without cropping. */
    private fun motionUrls(video: JsonObject): Pair<String, String?>? {
        val square = link(video, "motionDetailSquare") ?: link(video, "motionSquareVideo1x1")
        val raw = link(video, "motionDetailRaw")
        val tall = tallUrl(video)
        val primary = square ?: raw ?: tall ?: return null
        val alternate = listOfNotNull(square, raw, tall).firstOrNull { it != primary }
        return primary to alternate
    }

    // ---- Token ---------------------------------------------------------

    private var cachedToken: String? = null
    private var tokenExpiresAtMs = 0L

    /** Tokens the catalog API has already turned down. See [get]. */
    private val rejected = mutableSetOf<String>()

    /**
     * The anonymous bearer token the Apple Music web player uses for catalog
     * reads: read the way the browser gets it, by loading the web player and
     * picking it out of the bundle it pulls in.
     */
    @Synchronized
    private fun token(): String? {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < tokenExpiresAtMs - 60_000) return it }
        if (now < tokenRetryAfterMs) return null

        val html = canvasGet(WEB_PLAYER, mapOf("User-Agent" to USER_AGENT))
        val scripts = html?.let {
            Regex("""/assets/index(?:-legacy)?[~-][A-Za-z0-9_-]+\.js""")
                .findAll(it).map(MatchResult::value).distinct().toList()
        }.orEmpty()

        for (path in scripts) {
            val script = canvasGet("https://music.apple.com$path", mapOf("User-Agent" to USER_AGENT))
                ?: continue
            val candidates = Regex("""ey[A-Za-z0-9_-]+\.ey[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
                .findAll(script)
                .map(MatchResult::value)
                .distinct()
                .filter { it !in rejected }
                .mapNotNull { jwt -> expiry(jwt)?.let { jwt to it } }
                .filter { it.second > now }
                .toList()
            if (candidates.isEmpty()) continue

            val (jwt, expiresAt) = candidates.firstOrNull { isWebPlayerToken(it.first) }
                ?: candidates.first()
            cachedToken = jwt
            tokenExpiresAtMs = expiresAt
            return jwt
        }

        DesktopTrackLog.log("$TAG: no usable web player token; backing off for 30 min")
        tokenRetryAfterMs = now + TOKEN_RETRY_MS
        return null
    }

    private const val TOKEN_RETRY_MS = 30L * 60 * 1000

    /**
     * An authenticated catalog read. A 401 means the token picked out of the
     * bundle is not the one this endpoint honours, so it is struck off and the
     * next lookup re-scrapes.
     */
    private fun get(url: String, bearer: String?): String? {
        val request = Request.Builder().url(url).apply {
            header("User-Agent", USER_AGENT)
            if (bearer != null) {
                header("Authorization", "Bearer $bearer")
                header("Origin", "https://music.apple.com")
                header("Referer", "https://music.apple.com/")
            }
        }.build()
        return runCatching {
            Http.client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> response.body?.string()
                    response.code == 401 && bearer != null -> {
                        synchronized(this) {
                            rejected += bearer
                            if (cachedToken == bearer) {
                                cachedToken = null
                                tokenExpiresAtMs = 0L
                            }
                        }
                        null
                    }
                    else -> null
                }
            }
        }.getOrNull()
    }

    private fun canvasGet(url: String, headers: Map<String, String> = emptyMap()): String? {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        return runCatching {
            Http.client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }.getOrNull()
    }

    /** The web player's token names itself in the header `kid` and payload `iss`. */
    private fun isWebPlayerToken(jwt: String): Boolean = runCatching {
        val parts = jwt.split(".")
        val header = String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8)
        val payload = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
        header.contains("WebPlayKid") || payload.contains("AMPWebPlay")
    }.getOrDefault(false)

    /** A JWT's `exp` in millis, or null if this is not one we can read. */
    private fun expiry(jwt: String): Long? = runCatching {
        val payload = String(Base64.getUrlDecoder().decode(jwt.split(".")[1]), Charsets.UTF_8)
        Regex("\"exp\"\\s*:\\s*(\\d+)").find(payload)?.groupValues?.get(1)?.toLong()?.times(1000)
    }.getOrDefault(null)

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
