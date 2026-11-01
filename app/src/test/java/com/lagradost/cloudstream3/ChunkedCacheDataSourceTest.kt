package com.lagradost.cloudstream3

import android.net.TestUri
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import com.lagradost.cloudstream3.ui.player.buffering.ChunkedCacheDataSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.NavigableSet
import java.util.TreeSet

@UnstableApi
class ChunkedCacheDataSourceTest {
    @Test
    fun readsContinuouslyAcrossBoundedChunks() {
        val bytes = ByteArray(10) { it.toByte() }
        val delegate = FakeRangeDataSource(bytes, rangeSupported = true)
        val dataSource = ChunkedCacheDataSource(delegate, chunkSize = 3)

        assertArrayEquals(bytes, readAll(dataSource, TestUri(), C.LENGTH_UNSET.toLong()))
        assertTrue("expected more than one chunk request", delegate.requestedLengths.size > 1)
        assertTrue(delegate.requestedLengths.all { it > 0L && it <= 3L })
    }

    @Test
    fun fallsBackToUnboundedWhenRangeResponseIsMissing() {
        val bytes = ByteArray(10) { (it + 1).toByte() }
        val delegate = FakeRangeDataSource(bytes, rangeSupported = false)
        val dataSource = ChunkedCacheDataSource(delegate, chunkSize = 3)

        assertArrayEquals(bytes, readAll(dataSource, TestUri(), C.LENGTH_UNSET.toLong()))
        assertTrue("fallback should reopen without a bounded range", delegate.sawUnboundedRequest)
        assertEquals(
            "unbounded requests: ${delegate.unboundedRequestCount}, lengths: ${delegate.requestedLengths}",
            1,
            delegate.unboundedRequestCount
        )
    }

    @Test
    fun honorsOriginalKnownLength() {
        val bytes = ByteArray(10) { (it * 2).toByte() }
        val delegate = FakeRangeDataSource(bytes, rangeSupported = true)
        val dataSource = ChunkedCacheDataSource(delegate, chunkSize = 3)

        val read = readAll(dataSource, TestUri(), length = 7)
        assertArrayEquals(bytes.copyOf(7), read)
        assertTrue(delegate.requestedLengths.all { it > 0L && it <= 3L })
    }

    @Test
    fun reportsKnownRemainingLengthToTheExtractor() {
        val bytes = ByteArray(10) { it.toByte() }
        val delegate = FakeRangeDataSource(bytes, rangeSupported = true)
        val dataSource = ChunkedCacheDataSource(delegate, chunkSize = 3)

        val fromStart = dataSource.open(spec(TestUri(), position = 0L, length = C.LENGTH_UNSET.toLong()))
        assertEquals(10L, fromStart)
        dataSource.close()

        val fromMiddle = dataSource.open(spec(TestUri(), position = 4L, length = C.LENGTH_UNSET.toLong()))
        assertEquals(6L, fromMiddle)
        dataSource.close()
    }

    @Test
    fun reportsUnsetLengthWhenTheServerOmitsTheTotal() {
        val bytes = ByteArray(10) { it.toByte() }
        val delegate = FakeRangeDataSource(bytes, rangeSupported = true, unknownTotal = true)
        val dataSource = ChunkedCacheDataSource(delegate, chunkSize = 3)

        val length = dataSource.open(spec(TestUri(), position = 0L, length = C.LENGTH_UNSET.toLong()))
        assertEquals(C.LENGTH_UNSET.toLong(), length)
        dataSource.close()
    }

    @Test
    fun keepsReadingWhenTheServerReturnsShortRanges() {
        val bytes = ByteArray(10) { (it + 3).toByte() }
        val delegate = FakeRangeDataSource(bytes, rangeSupported = true, maxRangeBytes = 2)
        val dataSource = ChunkedCacheDataSource(delegate, chunkSize = 3)

        assertArrayEquals(bytes, readAll(dataSource, TestUri(), C.LENGTH_UNSET.toLong()))
        assertTrue(delegate.requestedLengths.size > 1)
    }

    @Test
    fun prefersContentRangeTotalOverStaleCacheMetadata() {
        val bytes = ByteArray(10) { it.toByte() }
        val cache = FakeCache(initialContentLength = 6L)
        val delegate = FakeRangeDataSource(bytes, rangeSupported = true)
        val dataSource = ChunkedCacheDataSource(delegate, cache = cache, chunkSize = 3)

        assertArrayEquals(bytes, readAll(dataSource, TestUri(), C.LENGTH_UNSET.toLong()))
        assertEquals(10L, cache.contentLength)
    }

    private fun spec(uri: Uri, position: Long, length: Long): DataSpec {
        return DataSpec.Builder()
            .setUri(uri)
            .setPosition(position)
            .setLength(length)
            .build()
    }

    private fun readAll(dataSource: DataSource, uri: Uri, length: Long): ByteArray {
        val spec = spec(uri, position = 0L, length = length)
        dataSource.open(spec)
        val output = ArrayList<Byte>()
        val buffer = ByteArray(4)
        while (true) {
            val read = dataSource.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            if (read > 0) {
                for (i in 0 until read) output.add(buffer[i])
            }
        }
        dataSource.close()
        return output.toByteArray()
    }

    private class FakeRangeDataSource(
        private val bytes: ByteArray,
        private val rangeSupported: Boolean,
        private val unknownTotal: Boolean = false,
        private val maxRangeBytes: Int? = null,
    ) : DataSource {
        val requestedLengths = ArrayList<Long>()
        var sawUnboundedRequest = false
            private set
        var unboundedRequestCount = 0
            private set

        private var open = false
        private var position = 0
        private var end = 0
        private var responseHeaders: Map<String, List<String>> = emptyMap()

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            open = true
            requestedLengths.add(dataSpec.length)
            if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                sawUnboundedRequest = true
                unboundedRequestCount++
            }
            if (!rangeSupported && dataSpec.position > 0L) {
                // A server that ignores ranges always sends the full resource from zero.
                position = dataSpec.position.toInt()
                end = bytes.size
                responseHeaders = NON_RANGE_HEADERS
                return dataSpec.length
            }
            position = dataSpec.position.toInt().coerceAtMost(bytes.size)
            end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                bytes.size
            } else {
                (position + dataSpec.length).coerceAtMost(bytes.size.toLong()).toInt()
            }
            maxRangeBytes?.let { end = minOf(end, position + it) }
            val total = if (unknownTotal) "*" else bytes.size.toString()
            responseHeaders = if (rangeSupported) {
                mapOf(
                    "Content-Range" to listOf("bytes $position-${end - 1}/$total")
                )
            } else {
                NON_RANGE_HEADERS
            }
            return if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                (end - position).toLong()
            } else {
                dataSpec.length
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!open || position >= end) return C.RESULT_END_OF_INPUT
            val read = minOf(length, end - position)
            bytes.copyInto(buffer, offset, position, position + read)
            position += read
            return read
        }

        override fun getUri(): Uri? = null

        override fun getResponseHeaders(): Map<String, List<String>> = responseHeaders

        override fun close() {
            open = false
        }

        companion object {
            private val NON_RANGE_HEADERS = mapOf(
                "Content-Length" to listOf("10"),
                "Content-Type" to listOf("application/octet-stream"),
            )
        }
    }

    private class FakeCache(initialContentLength: Long) : Cache {
        var contentLength: Long = initialContentLength

        override fun getUid(): Long = 1L
        override fun release() = Unit
        override fun addListener(
            key: String,
            listener: Cache.Listener,
        ): NavigableSet<CacheSpan> = TreeSet()

        override fun removeListener(key: String, listener: Cache.Listener) = Unit
        override fun getCachedSpans(key: String): NavigableSet<CacheSpan> = TreeSet()
        override fun getKeys(): MutableSet<String> = mutableSetOf()
        override fun getCacheSpace(): Long = 0L

        override fun startReadWrite(key: String, position: Long, length: Long): CacheSpan {
            throw UnsupportedOperationException()
        }

        override fun startReadWriteNonBlocking(
            key: String,
            position: Long,
            length: Long,
        ): CacheSpan? = null

        override fun startFile(key: String, position: Long, length: Long): File {
            throw UnsupportedOperationException()
        }

        override fun commitFile(file: File, length: Long) = Unit
        override fun releaseHoleSpan(holeSpan: CacheSpan) = Unit
        override fun removeResource(key: String) = Unit
        override fun removeSpan(span: CacheSpan) = Unit
        override fun isCached(key: String, position: Long, length: Long): Boolean = false
        override fun getCachedLength(key: String, position: Long, length: Long): Long = -length
        override fun getCachedBytes(key: String, position: Long, length: Long): Long = 0L

        override fun applyContentMetadataMutations(
            key: String,
            mutations: ContentMetadataMutations,
        ) {
            val value = mutations.editedValues[ContentMetadata.KEY_CONTENT_LENGTH] as? Long ?: return
            contentLength = value
        }

        override fun getContentMetadata(key: String): ContentMetadata = object : ContentMetadata {
            override fun get(key: String, defaultValue: ByteArray?): ByteArray? = defaultValue
            override fun get(key: String, defaultValue: String?): String? = defaultValue
            override fun get(key: String, defaultValue: Long): Long {
                return if (key == ContentMetadata.KEY_CONTENT_LENGTH) contentLength else defaultValue
            }

            override fun contains(key: String): Boolean {
                return key == ContentMetadata.KEY_CONTENT_LENGTH &&
                        contentLength != C.LENGTH_UNSET.toLong()
            }
        }
    }
}
