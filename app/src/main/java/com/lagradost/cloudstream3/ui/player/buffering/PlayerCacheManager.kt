package com.lagradost.cloudstream3.ui.player.buffering

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import com.lagradost.cloudstream3.MainActivity.Companion.deleteFileOnExit
import com.lagradost.cloudstream3.mvvm.logError
import java.io.File
import java.util.TreeSet

data class PlayerCacheConfig(
    val directoryName: String,
    val quotaBytes: Long,
    val databaseName: String,
    val createWhenQuotaZero: Boolean,
    val deleteOnExit: Boolean,
)

object PlayerCacheSelection {
    fun forPolicy(policy: PlayerBufferingPolicy, online: Boolean): PlayerCacheConfig? {
        if (!online) return null
        return when (policy) {
            is PlayerBufferingPolicy.Legacy -> PlayerCacheConfig(
                directoryName = PlayerCacheManager.NORMAL_DIRECTORY,
                quotaBytes = policy.diskCacheBytes,
                databaseName = PlayerCacheManager.NORMAL_DATABASE,
                createWhenQuotaZero = true,
                deleteOnExit = true,
            )

            is PlayerBufferingPolicy.Aggressive -> {
                if (policy.cacheQuotaBytes <= 0L) null else PlayerCacheConfig(
                    directoryName = PlayerCacheManager.AGGRESSIVE_DIRECTORY,
                    quotaBytes = policy.cacheQuotaBytes,
                    databaseName = PlayerCacheManager.AGGRESSIVE_DATABASE,
                    createWhenQuotaZero = false,
                    deleteOnExit = true,
                )
            }
        }
    }
}

/**
 * Owns at most one SimpleCache per app-managed directory. Normal and aggressive playback use
 * separate directories and separate metadata databases so neither policy can mutate the other's
 * cache or inherit its capacity.
 */
@OptIn(UnstableApi::class)
object PlayerCacheManager {
    const val NORMAL_DIRECTORY = "exoplayer"
    const val NORMAL_DATABASE = "exoplayer_internal.db"
    const val AGGRESSIVE_DIRECTORY = "exoplayer_aggressive"
    const val AGGRESSIVE_DATABASE = "exoplayer_aggressive_internal.db"

    private data class Entry(
        val cache: SimpleCache,
        val evictor: AdjustableLeastRecentlyUsedCacheEvictor,
    )

    private val entries = HashMap<String, Entry>()
    private val directoriesByCache = HashMap<Cache, String>()
    private val failedDirectories = HashSet<String>()

    @Synchronized
    fun getCache(context: Context, config: PlayerCacheConfig): SimpleCache? {
        if (config.quotaBytes <= 0L && !config.createWhenQuotaZero) return null
        if (config.directoryName in failedDirectories) return null

        entries[config.directoryName]?.let { entry ->
            entry.evictor.updateMaxBytes(config.quotaBytes)
            return entry.cache
        }

        return try {
            val directory = File(context.cacheDir, config.directoryName)
            if (config.deleteOnExit) {
                deleteFileOnExit(directory)
            }
            val evictor = AdjustableLeastRecentlyUsedCacheEvictor(config.quotaBytes)
            val databaseProvider: DatabaseProvider = if (config.databaseName ==
                StandaloneDatabaseProvider.DATABASE_NAME
            ) {
                StandaloneDatabaseProvider(context)
            } else {
                NamedDatabaseProvider(context, config.databaseName)
            }
            val cache = SimpleCache(directory, evictor, databaseProvider)
            entries[config.directoryName] = Entry(cache, evictor)
            directoriesByCache[cache] = config.directoryName
            cache
        } catch (e: Exception) {
            logError(e)
            failedDirectories += config.directoryName
            null
        }
    }

    @Synchronized
    fun updateQuota(directoryName: String, quotaBytes: Long) {
        if (quotaBytes <= 0L) return
        entries[directoryName]?.evictor?.updateMaxBytes(quotaBytes)
    }

    @Synchronized
    fun cacheSpace(directoryName: String): Long? {
        return try {
            entries[directoryName]?.cache?.cacheSpace
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    @Synchronized
    fun currentQuota(directoryName: String): Long? {
        return entries[directoryName]?.evictor?.currentMaxBytes
    }

    @Synchronized
    fun directoryForCache(cache: Cache): String? = directoriesByCache[cache]
}

@OptIn(UnstableApi::class)
private class NamedDatabaseProvider(
    context: Context,
    databaseName: String,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, 1), DatabaseProvider {
    override fun onCreate(db: SQLiteDatabase) = Unit
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

/**
 * LRU eviction with an adjustable hard cap. SimpleCache has no API to change an evictor's limit,
 * and recreating a live cache is unsafe, so the quota is updated on the existing evictor.
 */
@OptIn(UnstableApi::class)
internal class AdjustableLeastRecentlyUsedCacheEvictor(
    initialMaxBytes: Long,
) : CacheEvictor {
    private val leastRecentlyUsed = TreeSet<CacheSpan>(::compare)
    private var currentSize = 0L

    @Volatile
    private var maxBytes = initialMaxBytes.coerceAtLeast(0L)

    val currentMaxBytes: Long
        get() = maxBytes

    fun updateMaxBytes(value: Long) {
        maxBytes = value.coerceAtLeast(0L)
    }

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) {
            evictCache(cache, length)
        }
    }

    @Synchronized
    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.add(span)
        currentSize += span.length
        evictCache(cache, 0L)
    }

    @Synchronized
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.remove(span)
        currentSize -= span.length
    }

    @Synchronized
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        leastRecentlyUsed.remove(oldSpan)
        currentSize -= oldSpan.length
        leastRecentlyUsed.add(newSpan)
        currentSize += newSpan.length
        evictCache(cache, 0L)
    }

    private fun evictCache(cache: Cache, requiredSpace: Long) {
        while (currentSize + requiredSpace > maxBytes && leastRecentlyUsed.isNotEmpty()) {
            val span = leastRecentlyUsed.first()
            // Keep recently touched spans: they are either being read now or are the
            // playback buffer immediately ahead of the playhead. Prefetch must never
            // evict that buffer just to make room for a farther-away segment.
            val ageMs = System.currentTimeMillis() - span.lastTouchTimestamp
            if (ageMs in 0..30_000L) break
            try {
                cache.removeSpan(span)
            } catch (_: Exception) {
                // A span locking a reader is not safe to remove. Keep it in the LRU
                // set so it can be retried after the reader releases it.
                break
            }
        }
    }

    private fun compare(lhs: CacheSpan, rhs: CacheSpan): Int {
        val lastTouchTimestampDelta = lhs.lastTouchTimestamp - rhs.lastTouchTimestamp
        if (lastTouchTimestampDelta == 0L) {
            return lhs.compareTo(rhs)
        }
        return if (lastTouchTimestampDelta < 0L) -1 else 1
    }
}

