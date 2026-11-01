package com.lagradost.cloudstream3.ui.player.buffering

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import java.io.IOException

/**
 * Media3's progressive media source opens one cache request for the whole remaining file. That
 * open-ended hole span locks the cache key up to EOF, so a speculative range reader can never
 * write ahead of playback. This wrapper splits the player's reads into bounded cache requests.
 * The extractor still sees one continuous stream because the wrapper transparently opens the next
 * chunk whenever the current one ends.
 *
 * Only use this for progressive HTTP media with the same cache/data-source stack. HLS/DASH already
 * address individual segments and must keep their native data source.
 */
@UnstableApi
class ChunkedCacheDataSourceFactory(
    private val delegateFactory: DataSource.Factory,
    private val cache: Cache? = null,
    private val chunkSize: Long = DEFAULT_CHUNK_SIZE,
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return ChunkedCacheDataSource(delegateFactory.createDataSource(), cache, chunkSize)
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE = 8L * 1024L * 1024L
    }
}

@UnstableApi
internal class ChunkedCacheDataSource(
    private val delegate: DataSource,
    private val cache: Cache? = null,
    private val chunkSize: Long,
) : DataSource {
    private var requestSpec: DataSpec? = null
    private var position = 0L
    private var endPosition = C.LENGTH_UNSET.toLong()
    private var totalLength = C.LENGTH_UNSET.toLong()
    private var chunking: Boolean? = null
    private var currentChunkLength = C.LENGTH_UNSET.toLong()
    private var currentChunkRead = 0L
    private var currentChunkOpen = false
    private var endOfInput = false
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) {
        delegate.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        close()
        requestSpec = dataSpec
        position = dataSpec.position
        endPosition = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            C.LENGTH_UNSET.toLong()
        } else {
            dataSpec.position + dataSpec.length
        }
        opened = true
        endOfInput = false
        chunking = null
        totalLength = knownTotalLength(dataSpec)
        // Cached spans beyond the recorded file length prove the metadata is stale
        // (for example, left behind by a short range response). Drop it before the
        // delegate opens so CacheDataSource cannot truncate reads at the wrong EOF.
        if (isTotalLengthKnown() && cachedDataExceeds(dataSpec, totalLength)) {
            clearContentLength(dataSpec)
            totalLength = C.LENGTH_UNSET.toLong()
        }
        openNextChunk()
        // Report the remaining length when it is known. Returning the chunk length would
        // make the extractor believe the file ends at the chunk boundary; returning
        // LENGTH_UNSET would hide a known file size that the previous open-ended cache
        // request used to expose. The wrapper can supply the whole remainder.
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else if (totalLength > 0L && totalLength != C.LENGTH_UNSET.toLong()) {
            (totalLength - dataSpec.position).coerceAtLeast(0L)
        } else {
            C.LENGTH_UNSET.toLong()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!opened) return C.RESULT_END_OF_INPUT
        while (true) {
            if (!currentChunkOpen) {
                if (isAtEnd() || endOfInput || chunking == false) {
                    return C.RESULT_END_OF_INPUT
                }
                if (!openNextChunk()) return C.RESULT_END_OF_INPUT
            }
            // The first part of a chunk may be a cache hit, so range support is only
            // discovered after CacheDataSource switches to upstream mid-chunk. Switch
            // to a single unbounded request if that upstream ignores range requests;
            // otherwise every later chunk would re-download a growing prefix.
            if (chunking == null) {
                val headers = getResponseHeaders()
                if (headers.isNotEmpty()) {
                    val spec = requestSpec
                    if (spec != null && contentRangeStart(headers) == null) {
                        beginPassThrough(spec)
                        continue
                    }
                    if (contentRangeStart(headers) != null) {
                        chunking = true
                    }
                }
            }
            val bytesRead = delegate.read(buffer, offset, length)
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                val chunkWasShort = currentChunkLength != C.LENGTH_UNSET.toLong() &&
                        currentChunkRead < currentChunkLength
                val madeProgress = currentChunkRead > 0L
                closeCurrentChunk()
                // CacheDataSource treats an upstream EOI before the requested range
                // length as the end of the resource and stores that position as the
                // content length. CDNs that cap range responses do this routinely.
                // Restore the total learned from Content-Range before reopening so a
                // short range cannot truncate the rest of playback.
                if (chunkWasShort && madeProgress && isTotalLengthKnown()) {
                    requestSpec?.let { persistContentLength(it, totalLength) }
                }
                if (chunking == false) return C.RESULT_END_OF_INPUT
                // CacheDataSource truncates a read to the cached span or hole it
                // found, and a range server may also return less than requested.
                // Reopen at the current position so the extractor still sees one
                // continuous stream. Stop if reopening made no progress, which
                // means the upstream really is at EOF.
                if (chunkWasShort && !madeProgress) {
                    endOfInput = true
                    return C.RESULT_END_OF_INPUT
                }
                continue
            }
            if (bytesRead > 0) {
                position += bytesRead
                currentChunkRead += bytesRead
                return bytesRead
            }
            return 0
        }
    }

    override fun getUri(): Uri? = delegate.uri

    override fun getResponseHeaders(): Map<String, List<String>> {
        return runCatching { delegate.responseHeaders }.getOrDefault(emptyMap())
    }

    override fun close() {
        opened = false
        requestSpec = null
        chunking = null
        endOfInput = false
        closeCurrentChunk()
    }

    private fun isAtEnd(): Boolean {
        return endPosition != C.LENGTH_UNSET.toLong() && position >= endPosition
    }

    private fun isTotalLengthKnown(): Boolean {
        return totalLength > 0L && totalLength != C.LENGTH_UNSET.toLong()
    }

    private fun openNextChunk(): Boolean {
        val spec = requestSpec ?: return false
        if (chunking == false) {
            openPassThrough(spec)
            return currentChunkOpen
        }
        val remaining = if (endPosition == C.LENGTH_UNSET.toLong()) {
            chunkSize
        } else {
            (endPosition - position).coerceAtLeast(0L)
        }
        if (remaining <= 0L) return false
        val length = minOf(chunkSize, remaining)
        val chunkSpec = spec.buildUpon()
            .setPosition(position)
            .setLength(length)
            .build()
        try {
            delegate.open(chunkSpec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            // A request starting at EOF may be rejected with 416. That is a normal end.
            if (e.responseCode == 416) {
                endOfInput = true
                closeCurrentChunk()
                return false
            }
            closeCurrentChunk()
            throw e
        } catch (e: IOException) {
            closeCurrentChunk()
            throw e
        }
        currentChunkOpen = true
        currentChunkLength = length
        currentChunkRead = 0L

        val headers = getResponseHeaders()
        val responseTotal = contentRangeTotal(headers)
        if (responseTotal != null) {
            // Content-Range is authoritative even when cache metadata already claims
            // a length. A short-range response may have stored an incorrect length
            // previously; trust the server's total and repair the cache metadata.
            if (responseTotal != totalLength) {
                totalLength = responseTotal
                persistContentLength(spec, responseTotal)
            }
        } else if (totalLength <= 0L || totalLength == C.LENGTH_UNSET.toLong()) {
            val hasContentRange = headers.keys.any { it.equals("Content-Range", ignoreCase = true) }
            val parsedTotal = if (!hasContentRange && position == 0L) {
                contentLengthHeader(headers)
            } else {
                null
            }
            if (parsedTotal != null) {
                totalLength = parsedTotal
                persistContentLength(spec, parsedTotal)
            }
        }
        if (chunking == null) {
            val responseStart = contentRangeStart(headers)
            when {
                responseStart == position -> chunking = true
                responseStart != null -> beginPassThrough(spec)
                headers.isEmpty() -> Unit // Cached span; decide when upstream is reached.
                else -> beginPassThrough(spec)
            }
        }
        return currentChunkOpen
    }

    private fun beginPassThrough(spec: DataSpec) {
        closeCurrentChunk()
        chunking = false
        openPassThrough(spec)
    }

    private fun openPassThrough(spec: DataSpec) {
        val remainingSpec = spec.buildUpon()
            .setPosition(position)
            .setLength(
                if (endPosition == C.LENGTH_UNSET.toLong()) {
                    C.LENGTH_UNSET.toLong()
                } else {
                    (endPosition - position).coerceAtLeast(0L)
                }
            )
            .build()
        delegate.open(remainingSpec)
        currentChunkOpen = true
        currentChunkLength = if (endPosition == C.LENGTH_UNSET.toLong()) {
            C.LENGTH_UNSET.toLong()
        } else {
            (endPosition - position).coerceAtLeast(0L)
        }
        currentChunkRead = 0L
    }

    private fun closeCurrentChunk() {
        if (!currentChunkOpen) return
        currentChunkOpen = false
        runCatching { delegate.close() }
    }

    private fun contentRangeStart(headers: Map<String, List<String>>): Long? {
        val value = headers.entries
            .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?: return null
        val match = CONTENT_RANGE.find(value) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private fun contentRangeTotal(headers: Map<String, List<String>>): Long? {
        val value = headers.entries
            .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?: return null
        val match = CONTENT_RANGE.find(value) ?: return null
        return match.groupValues[3].toLongOrNull()?.takeIf { it > 0L }
    }

    private fun contentLengthHeader(headers: Map<String, List<String>>): Long? {
        return headers.entries
            .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    }

    private fun knownTotalLength(dataSpec: DataSpec): Long {
        val cache = cache ?: return C.LENGTH_UNSET.toLong()
        return runCatching {
            val key = dataSpec.key ?: CacheKeyFactory.DEFAULT.buildCacheKey(dataSpec)
            ContentMetadata.getContentLength(cache.getContentMetadata(key))
        }.getOrDefault(C.LENGTH_UNSET.toLong())
            .takeIf { it > 0L && it != C.LENGTH_UNSET.toLong() }
            ?: C.LENGTH_UNSET.toLong()
    }

    private fun cachedDataExceeds(dataSpec: DataSpec, total: Long): Boolean {
        val cache = cache ?: return false
        return runCatching {
            val key = dataSpec.key ?: CacheKeyFactory.DEFAULT.buildCacheKey(dataSpec)
            cache.getCachedSpans(key).any { span ->
                span.isCached &&
                        !span.isOpenEnded &&
                        span.length > 0L &&
                        span.position + span.length > total
            }
        }.getOrDefault(false)
    }

    private fun clearContentLength(dataSpec: DataSpec) {
        val cache = cache ?: return
        runCatching {
            val key = dataSpec.key ?: CacheKeyFactory.DEFAULT.buildCacheKey(dataSpec)
            val mutations = ContentMetadataMutations()
            ContentMetadataMutations.setContentLength(mutations, C.LENGTH_UNSET.toLong())
            cache.applyContentMetadataMutations(key, mutations)
        }
    }

    /**
     * Makes the validated Content-Range total visible to CacheDataSource and to future
     * opens. Media3 normally stores this itself, but only for open-ended requests; our
     * cache reads are bounded to a chunk, so the player would otherwise forget the
     * file size whenever the first chunk after a seek is already cached.
     */
    private fun persistContentLength(spec: DataSpec, total: Long) {
        val cache = cache ?: return
        runCatching {
            val key = spec.key ?: CacheKeyFactory.DEFAULT.buildCacheKey(spec)
            val current = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            if (current == total) return
            val mutations = ContentMetadataMutations()
            ContentMetadataMutations.setContentLength(mutations, total)
            cache.applyContentMetadataMutations(key, mutations)
        }
    }

    companion object {
        private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")
    }
}
