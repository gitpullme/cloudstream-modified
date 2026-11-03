package com.lagradost.cloudstream3.actions

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.ResultViewModel2

sealed interface NextEpisodeResolution {
    data class Found(val nextEpisode: ResultEpisode) : NextEpisodeResolution
    data object LastEpisode : NextEpisodeResolution
    data object Unknown : NextEpisodeResolution

    val isComplete: Boolean
        get() = this !is Unknown

    val resolvedEpisode: ResultEpisode?
        get() = (this as? Found)?.nextEpisode
}

object NextEpisodeResolver {
    /**
     * Resolves the chronological next episode for [current] using the already-loaded [catalogue].
     *
     * Rules:
     * - Matches [current] by its stable episode ID (`id`).
     * - Keeps anime dub tracks separate: never selects an episode from another dub.
     * - Traverses seasons chronologically (e.g. season 1 final episode -> season 2 episode 1).
     * - Orders episodes chronologically by season and episode number, using `totalEpisodeIndex`
     *   when it provides a reliable, unique ordering.
     * - Ignores UI sorting preferences (descending, date, rating).
     * - Guarantees no duplicate selection and never returns the current episode.
     * - Returns [NextEpisodeResolution.LastEpisode] if [current] is confirmed to be the final episode.
     * - Returns [NextEpisodeResolution.Unknown] if [current] cannot be reliably located in [catalogue].
     */
    fun resolveNextEpisode(
        catalogue: Map<ResultViewModel2.EpisodeIndexer, List<ResultEpisode>>,
        current: ResultEpisode,
        preferredDubStatus: DubStatus? = null,
    ): NextEpisodeResolution {
        if (catalogue.isEmpty()) return NextEpisodeResolution.Unknown

        // Find which entries in the catalogue contain current episode by stable ID
        val matchingEntries = catalogue.entries.filter { entry ->
            entry.value.any { it.id == current.id }
        }
        if (matchingEntries.isEmpty()) {
            return NextEpisodeResolution.Unknown
        }

        // Determine target dub status: prefer matching entry that aligns with preferredDubStatus
        val targetEntry = if (preferredDubStatus != null) {
            matchingEntries.firstOrNull { it.key.dubStatus == preferredDubStatus }
                ?: matchingEntries.first()
        } else {
            matchingEntries.first()
        }
        val targetDub = targetEntry.key.dubStatus

        // Gather all episodes for this dub status across all seasons, removing duplicates by ID
        val dubEpisodes = catalogue
            .filter { it.key.dubStatus == targetDub }
            .values
            .flatten()
            .distinctBy { it.id }

        if (dubEpisodes.isEmpty()) return NextEpisodeResolution.Unknown

        // Check if totalEpisodeIndex provides a reliable distinct ordering
        val hasReliableTotalIndex = dubEpisodes.all { it.totalEpisodeIndex != null && it.totalEpisodeIndex > 0 } &&
                dubEpisodes.map { it.totalEpisodeIndex }.distinct().size == dubEpisodes.size

        val sortedEpisodes = if (hasReliableTotalIndex) {
            dubEpisodes.sortedBy { it.totalEpisodeIndex!! }
        } else {
            dubEpisodes.sortedWith(
                compareBy<ResultEpisode> { it.seasonIndex ?: it.season ?: 0 }
                    .thenBy { it.episode }
                    .thenBy { it.index }
            )
        }

        val currentIndex = sortedEpisodes.indexOfFirst { it.id == current.id }
        if (currentIndex < 0) return NextEpisodeResolution.Unknown

        if (currentIndex == sortedEpisodes.size - 1) {
            return NextEpisodeResolution.LastEpisode
        }

        val nextEp = sortedEpisodes[currentIndex + 1]
        // Guard against duplicate / same episode
        if (nextEp.id == current.id) {
            return NextEpisodeResolution.Unknown
        }

        return NextEpisodeResolution.Found(nextEp)
    }
}
