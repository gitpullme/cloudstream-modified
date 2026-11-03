package com.lagradost.cloudstream3.actions

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.net.URLDecoder
import java.util.Locale

enum class MediaMetadataSource(val label: String) {
    URL_PATH("URL path"),
    RESPONSE_CONTENT_TYPE("response-content-type"),
    CONTENT_DISPOSITION("Content-Disposition filename"),
    FALLBACK("fallback")
}

data class ResolvedMediaMetadata(
    val filename: String? = null,
    val extension: String? = null,
    val mimeType: String = "video/*",
    val source: MediaMetadataSource = MediaMetadataSource.FALLBACK
)

object ExternalMediaMimeResolver {

    val EXTENSION_TO_MIME: Map<String, String> = mapOf(
        ".mkv" to "video/x-matroska",
        ".mp4" to "video/mp4",
        ".m4v" to "video/mp4",
        ".webm" to "video/webm",
        ".ts" to "video/mp2t",
        ".m4s" to "video/mp4",
        ".mov" to "video/quicktime",
        ".avi" to "video/x-msvideo",
        ".m3u8" to "application/vnd.apple.mpegurl",
        ".mpd" to "application/dash+xml",
        ".flv" to "video/x-flv",
        ".3gp" to "video/3gpp"
    )

    private val FILENAME_STAR_REGEX = Regex("""filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
    private val QUOTED_FILENAME_REGEX = Regex("""filename\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
    private val UNQUOTED_FILENAME_REGEX = Regex("""filename\s*=\s*([^;,\s]+)""", RegexOption.IGNORE_CASE)

    /**
     * Extracts a filename from a Content-Disposition header or query value.
     * Supports filename*, filename="...", filename=..., and escaped/encoded variants.
     */
    fun parseContentDispositionFilename(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null

        // 1. Check filename*= (RFC 6266 / RFC 5987 takes precedence)
        FILENAME_STAR_REGEX.find(disposition)?.let { match ->
            val raw = match.groupValues[1].trim().removeSurrounding("\"")
            val decoded = if (raw.contains("'")) {
                val parts = raw.split("'", limit = 3)
                if (parts.size == 3) {
                    val charset = parts[0].ifBlank { "UTF-8" }
                    val encoded = parts[2]
                    try {
                        URLDecoder.decode(encoded, charset)
                    } catch (_: Throwable) {
                        encoded
                    }
                } else {
                    decodePercent(raw)
                }
            } else {
                decodePercent(raw)
            }
            val cleaned = cleanFilename(decoded)
            if (!cleaned.isNullOrBlank()) return cleaned
        }

        // 2. Check quoted filename="..."
        QUOTED_FILENAME_REGEX.find(disposition)?.let { match ->
            val raw = match.groupValues[1]
            val cleaned = cleanFilename(raw)
            if (!cleaned.isNullOrBlank()) return cleaned
        }

        // 3. Check unquoted filename=...
        UNQUOTED_FILENAME_REGEX.find(disposition)?.let { match ->
            val raw = match.groupValues[1]
            val cleaned = cleanFilename(raw)
            if (!cleaned.isNullOrBlank()) return cleaned
        }

        return null
    }

    private fun cleanFilename(raw: String): String? {
        var str = raw.trim()
        str = str.replace("\\\"", "\"")
        str = str.removeSurrounding("\"")
        str = str.removeSurrounding("'")
        str = str.removeSurrounding("\"")
        str = str.trim()
        return str.ifBlank { null }
    }

    private fun decodePercent(value: String): String {
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Throwable) {
            value
        }
    }

    /**
     * Parses query parameters from a URL without decoding already decoded values twice.
     */
    fun parseQueryParams(url: String): Map<String, String> {
        val queryIndex = url.indexOf('?')
        if (queryIndex < 0 || queryIndex >= url.length - 1) return emptyMap()
        val fragmentIndex = url.indexOf('#', startIndex = queryIndex)
        val query = if (fragmentIndex > queryIndex) {
            url.substring(queryIndex + 1, fragmentIndex)
        } else {
            url.substring(queryIndex + 1)
        }
        if (query.isBlank()) return emptyMap()

        val params = mutableMapOf<String, String>()
        for (param in query.split('&')) {
            if (param.isBlank()) continue
            val eqIdx = param.indexOf('=')
            if (eqIdx >= 0) {
                val key = decodePercent(param.substring(0, eqIdx)).lowercase(Locale.ROOT).trim()
                val value = decodePercent(param.substring(eqIdx + 1)).trim()
                if (key.isNotBlank()) {
                    params[key] = value
                }
            } else {
                val key = decodePercent(param).lowercase(Locale.ROOT).trim()
                if (key.isNotBlank()) {
                    params[key] = ""
                }
            }
        }
        return params
    }

    /**
     * Extracts filename from response-content-disposition query parameter in a URL.
     */
    fun parseContentDispositionFilenameFromUrl(url: String): String? {
        val params = parseQueryParams(url)
        val disposition = params["response-content-disposition"] ?: params["content-disposition"]
        return parseContentDispositionFilename(disposition)
    }

    /**
     * Extracts extension (including the dot, e.g. ".mkv") from a filename or path.
     */
    fun extractExtension(pathOrFilename: String?): String? {
        if (pathOrFilename.isNullOrBlank()) return null
        val clean = pathOrFilename.substringBefore("?").substringBefore("#")
        val lastSlash = clean.lastIndexOf('/')
        val filename = if (lastSlash >= 0) clean.substring(lastSlash + 1) else clean
        val lastDot = filename.lastIndexOf('.')
        if (lastDot < 0 || lastDot >= filename.length - 1) return null
        return "." + filename.substring(lastDot + 1).lowercase(Locale.ROOT)
    }

    /**
     * Derives media metadata and MIME type from URL and link type according to the 4-tier priority.
     */
    fun resolve(url: String, linkType: ExtractorLinkType = ExtractorLinkType.VIDEO): ResolvedMediaMetadata {
        val cleanUrl = url.substringBefore("#").substringBefore("?")
        val pathExtension = extractExtension(cleanUrl)

        // Priority 1: A recognised video extension in the URL path.
        if (pathExtension != null && EXTENSION_TO_MIME.containsKey(pathExtension)) {
            val filename = cleanUrl.substringAfterLast('/')
            return ResolvedMediaMetadata(
                filename = filename.ifBlank { null },
                extension = pathExtension,
                mimeType = EXTENSION_TO_MIME[pathExtension]!!,
                source = MediaMetadataSource.URL_PATH
            )
        }

        val queryParams = parseQueryParams(url)

        // Priority 2: An explicit response-content-type query parameter when present.
        val responseContentType = queryParams["response-content-type"] ?: queryParams["content-type"]
        if (!responseContentType.isNullOrBlank()) {
            val cleanType = responseContentType.substringBefore(";").trim().lowercase(Locale.ROOT)
            if (isValidMediaMimeType(cleanType)) {
                val cdFilename = parseContentDispositionFilename(
                    queryParams["response-content-disposition"] ?: queryParams["content-disposition"]
                )
                val ext = extractExtension(cdFilename) ?: pathExtension
                return ResolvedMediaMetadata(
                    filename = cdFilename,
                    extension = ext,
                    mimeType = cleanType,
                    source = MediaMetadataSource.RESPONSE_CONTENT_TYPE
                )
            }
        }

        // Priority 3: The filename in response-content-disposition.
        val disposition = queryParams["response-content-disposition"] ?: queryParams["content-disposition"]
        val cdFilename = parseContentDispositionFilename(disposition)
        if (cdFilename != null) {
            val cdExtension = extractExtension(cdFilename)
            if (cdExtension != null && EXTENSION_TO_MIME.containsKey(cdExtension)) {
                return ResolvedMediaMetadata(
                    filename = cdFilename,
                    extension = cdExtension,
                    mimeType = EXTENSION_TO_MIME[cdExtension]!!,
                    source = MediaMetadataSource.CONTENT_DISPOSITION
                )
            }
        }

        // Priority 4: Existing generic fallback when no reliable metadata exists.
        val fallbackMime = when (linkType) {
            ExtractorLinkType.M3U8 -> "application/vnd.apple.mpegurl"
            ExtractorLinkType.DASH -> "application/dash+xml"
            else -> {
                when {
                    cleanUrl.endsWith(".m3u8", ignoreCase = true) -> "application/vnd.apple.mpegurl"
                    cleanUrl.endsWith(".mpd", ignoreCase = true) -> "application/dash+xml"
                    else -> "video/*"
                }
            }
        }

        return ResolvedMediaMetadata(
            filename = cdFilename,
            extension = pathExtension ?: extractExtension(cdFilename),
            mimeType = fallbackMime,
            source = MediaMetadataSource.FALLBACK
        )
    }

    private fun isValidMediaMimeType(mime: String): Boolean {
        if (mime == "application/octet-stream" || mime == "binary/octet-stream") return false
        return mime.startsWith("video/") ||
                mime.startsWith("audio/") ||
                mime == "application/vnd.apple.mpegurl" ||
                mime == "application/x-mpegurl" ||
                mime == "application/dash+xml" ||
                mime == "application/ogg"
    }
}
