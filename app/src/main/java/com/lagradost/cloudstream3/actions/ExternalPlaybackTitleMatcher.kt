package com.lagradost.cloudstream3.actions

import kotlin.math.max

/**
 * Weak title matching for external players.
 *
 * Network Stream Player is free to publish a provider title, a formatted
 * episode name, a filename or a URL. Titles are therefore evidence, never
 * identity. The selected CloudStream episode id remains the tracking identity.
 */
object ExternalPlaybackTitleMatcher {
    private val SXXEXX = Regex("""(?i)\bs(\d{1,2})[\s._-]*e(\d{1,3})\b""")
    private val NXNN = Regex("""(?i)\b(\d{1,2})x(\d{1,3})\b""")
    private val EPISODE = Regex("""(?i)\b(?:episode|ep|e)[\s._-]*(\d{1,3})\b""")
    private val QUALITY_TAGS = Regex(
        """(?i)\b(?:2160p|1080p|720p|480p|360p|4k|uhd|web[- ]?dl|webrip|bluray|brrip|hdtv|x264|x265|h\.?264|h\.?265|hevc|av1|aac|dts|proper|repack)\b"""
    )

    /**
     * 3 = strong match, 2 = one title contains the other, 1 = partial token
     * overlap, 0 = no useful evidence (blank/URL), -1 = conflicting titles.
     */
    fun matchScore(expected: String?, actual: String?): Int {
        if (expected.isNullOrBlank() || actual.isNullOrBlank()) return 0
        if (looksLikeUrl(expected) || looksLikeUrl(actual)) return 0

        val left = normalize(expected)
        val right = normalize(actual)
        if (left.isEmpty() || right.isEmpty()) return 0
        if (left == right) return 3

        val leftEpisode = episodeNumber(left)
        val rightEpisode = episodeNumber(right)
        if (leftEpisode != null && rightEpisode != null) {
            if (leftEpisode.season != null && rightEpisode.season != null &&
                leftEpisode.season != rightEpisode.season
            ) {
                return -1
            }
            return if (leftEpisode.episode == rightEpisode.episode) 3 else -1
        }

        if (left.contains(right) || right.contains(left)) return 2

        val leftTokens = left.split(' ').filter { it.length > 2 }.toSet()
        val rightTokens = right.split(' ').filter { it.length > 2 }.toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return -1
        val overlap = leftTokens.intersect(rightTokens).size
        return if (overlap >= max(1, leftTokens.size / 2)) 1 else -1
    }

    /**
     * Blank or URL-derived titles are unknown, not mismatches. Only two
     * human-readable titles with conflicting evidence are treated as a weak
     * mismatch.
     */
    fun isLikelySame(expected: String?, actual: String?): Boolean {
        if (expected.isNullOrBlank() || actual.isNullOrBlank()) return true
        if (looksLikeUrl(expected) || looksLikeUrl(actual)) return true
        return matchScore(expected, actual) >= 1
    }

    fun looksLikeUrl(value: String?): Boolean {
        val lower = value?.trim()?.lowercase() ?: return false
        return lower.startsWith("http://") ||
                lower.startsWith("https://") ||
                lower.startsWith("rtmp") ||
                lower.startsWith("file://") ||
                lower.contains("/")
    }

    fun normalize(value: String): String {
        if (looksLikeUrl(value)) return ""
        var normalized = value.trim().lowercase()
        normalized = normalized.substringBeforeLast('.')
        normalized = normalized.replace(Regex("""\[[^\]]*]"""), " ")
        normalized = normalized.replace(Regex("""\([^)]*\)"""), " ")
        normalized = normalized.replace(QUALITY_TAGS, " ")
        return normalized.replace(Regex("""[^a-z0-9]+"""), " ").trim()
    }

    private data class EpisodeNumber(val season: Int?, val episode: Int)

    private fun episodeNumber(normalized: String): EpisodeNumber? {
        SXXEXX.find(normalized)?.let { match ->
            return EpisodeNumber(
                season = match.groupValues[1].toIntOrNull(),
                episode = match.groupValues[2].toIntOrNull() ?: return null,
            )
        }
        NXNN.find(normalized)?.let { match ->
            return EpisodeNumber(
                season = match.groupValues[1].toIntOrNull(),
                episode = match.groupValues[2].toIntOrNull() ?: return null,
            )
        }
        EPISODE.find(normalized)?.let { match ->
            return EpisodeNumber(
                season = null,
                episode = match.groupValues[1].toIntOrNull() ?: return null,
            )
        }
        return null
    }
}
