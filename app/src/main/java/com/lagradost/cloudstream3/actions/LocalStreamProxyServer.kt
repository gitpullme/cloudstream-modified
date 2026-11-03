package com.lagradost.cloudstream3.actions

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import okhttp3.CacheControl
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Local HTTP loopback proxy for streaming media to external players when
 * direct intent header handoff is insufficient (e.g. HLS playlists with cookies,
 * referers, or custom headers required on child chunks and keys).
 *
 * The proxy deliberately owns its own daemon threads and has no Activity or
 * coroutine-scope dependency. A foreground service keeps the process alive while
 * an external player is playing; see [com.lagradost.cloudstream3.services.LocalStreamProxyService].
 *
 * All upstream responses are streamed incrementally. The object never buffers a
 * complete media segment in memory and never holds a session-wide lock while
 * waiting for upstream I/O.
 */
object LocalStreamProxyServer : Closeable {
    private const val TAG = "LocalStreamProxy"
    const val BUFFER_SIZE = 64 * 1024
    const val DEFAULT_CLIENT_BUFFER_SIZE = 64 * 1024 // 64 KB per-client buffered socket output stream
    const val BATCH_FLUSH_THRESHOLD = 32 * 1024 // 32 KB write coalescing threshold
    const val TRANSFER_BUFFER_SIZE = 64 * 1024 // 64 KB reusable media transfer buffer
    const val TELEMETRY_LOG_INTERVAL = 50L // Emit aggregated stats every 50 completed requests
    private const val MAX_CONNECTIONS = 64
    private const val MAX_PENDING_CONNECTIONS = 64
    private const val MAX_REDIRECTS = 8
    private const val MAX_PLAYLIST_BYTES = 16L * 1024L * 1024L
    private const val MAX_LICENSE_BYTES = 4L * 1024L * 1024L
    private const val CLEANUP_INTERVAL_SECONDS = 30L

    const val DEFAULT_IDLE_TIMEOUT_MS = 15 * 60 * 1000L // 15 minutes of inactivity
    const val DEFAULT_TTL_MS = 4 * 60 * 60 * 1000L // 4 hours maximum session validity

    @Volatile var verboseDiagnostics: Boolean = false
    val totalCompletedRequests = AtomicLong(0)
    val totalBytesTransferred = AtomicLong(0)
    val activeTransfers = AtomicInteger(0)
    val totalTransferFailures = AtomicLong(0)
    val hlsInspectionCount = AtomicLong(0)

    private val threadLocalTransferBuffer = ThreadLocal.withInitial {
        ByteArray(TRANSFER_BUFFER_SIZE)
    }

    private fun getTransferBuffer(): ByteArray {
        return threadLocalTransferBuffer.get() ?: ByteArray(TRANSFER_BUFFER_SIZE).also {
            threadLocalTransferBuffer.set(it)
        }
    }

    private val CRLF = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte())
    private val ZERO_CHUNK = "0\r\n\r\n".toByteArray(Charsets.US_ASCII)

    private val isRunning = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var cleanupExecutor: ScheduledExecutorService? = null

    private val workerPool = ThreadPoolExecutor(
        4,
        MAX_CONNECTIONS,
        60L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(MAX_PENDING_CONNECTIONS),
        { runnable -> Thread(runnable, "LocalStreamProxyWorker").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    ).apply {
        allowCoreThreadTimeOut(true)
    }

    private val sessions = ConcurrentHashMap<String, ProxySession>()
    private val requestSeq = AtomicLong(0)

    /**
     * Bounded, per-client buffered socket output stream.
     * Coalesces small socket writes into batches up to 64 KB while providing direct
     * write-through for large buffers and manual/adaptive flush control.
     */
    class BufferedClientOutputStream(
        val target: OutputStream,
        bufferSize: Int = DEFAULT_CLIENT_BUFFER_SIZE
    ) : OutputStream() {
        private val buf = ByteArray(bufferSize)
        private var count = 0

        val bufferedBytes: Int
            get() = count

        val capacity: Int
            get() = buf.size

        override fun write(b: Int) {
            if (count >= buf.size) {
                flushBuffer()
            }
            buf[count++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            if (len >= buf.size) {
                flushBuffer()
                target.write(b, off, len)
                return
            }
            if (count + len > buf.size) {
                flushBuffer()
            }
            System.arraycopy(b, off, buf, count, len)
            count += len
        }

        fun flushBuffer() {
            if (count > 0) {
                target.write(buf, 0, count)
                count = 0
            }
        }

        override fun flush() {
            flushBuffer()
            target.flush()
        }

        override fun close() {
            try {
                flush()
            } finally {
                target.close()
            }
        }
    }

    enum class ResourceType(val label: String) {
        MASTER_PLAYLIST("master playlist"),
        MEDIA_PLAYLIST("media playlist"),
        SEGMENT("media segment"),
        KEY("encryption key"),
        INIT_MAP("initialization map"),
        SUBTITLE("subtitle"),
        OTHER("resource")
    }

    class ResourceRecord(
        val id: String,
        val url: String,
        type: ResourceType,
        headers: Map<String, String>,
        originPlaylistUrl: String?,
        byteRange: String?
    ) {
        @Volatile var type: ResourceType = type
        @Volatile var headers: Map<String, String> = headers
        @Volatile var originPlaylistUrl: String? = originPlaylistUrl
        @Volatile var byteRange: String? = byteRange

        fun mergeHeaders(additional: Map<String, String>) {
            if (additional.isNotEmpty()) {
                headers = headers + additional
            }
        }
    }

    /**
     * Non-sensitive structured diagnostic tracker for incoming player requests.
     */
    class RequestDiagnostic(
        val requestId: Long,
        val method: String,
        val routeType: String,
        val resourceType: String,
        val targetUrl: String,
        val rangeReceived: String?,
        val rangeForwarded: Boolean,
        val sessionId: String? = null,
        val resourceId: String? = null
    ) {
        val startTime: Long = System.currentTimeMillis()
        var timeToHeaders: Long = -1L
        var timeToFirstByte: Long = -1L
        var upstreamStatus: Int = -1
        var contentType: String = ""
        var contentLength: Long = -1L
        var bytesTransferred: Long = 0L
        var failureStage: String? = null
        var failureReason: String? = null

        private val sessionTag: String
            get() = sessionId?.let { " session=${it.take(8)}" } ?: ""

        private val resourceTag: String
            get() = resourceId?.let { " res=$it" } ?: ""

        fun logStart() {
            if (verboseDiagnostics) {
                Log.d(
                    TAG,
                    "[#$requestId]$sessionTag$resourceTag START $method $routeType ($resourceType) " +
                            "upstream=${sanitizeUrl(targetUrl)} range=${rangeReceived ?: "none"} (fwd=$rangeForwarded)"
                )
            }
        }

        fun onHeadersReceived(status: Int, type: String, length: Long) {
            timeToHeaders = System.currentTimeMillis() - startTime
            upstreamStatus = status
            contentType = type
            contentLength = length
            if (verboseDiagnostics) {
                Log.d(
                    TAG,
                    "[#$requestId]$sessionTag HEADERS in ${timeToHeaders}ms: status=$status " +
                            "type='$type' len=$length"
                )
            }
        }

        fun onFirstByte() {
            if (timeToFirstByte < 0) {
                timeToFirstByte = System.currentTimeMillis() - startTime
            }
        }

        fun onComplete() {
            val totalDuration = System.currentTimeMillis() - startTime
            totalBytesTransferred.addAndGet(bytesTransferred)
            val completed = totalCompletedRequests.incrementAndGet()
            if (verboseDiagnostics) {
                Log.d(
                    TAG,
                    "[#$requestId]$sessionTag DONE in ${totalDuration}ms: bytes=$bytesTransferred " +
                            "(headers=${timeToHeaders}ms, ttfb=${timeToFirstByte}ms)"
                )
            } else if (completed % TELEMETRY_LOG_INTERVAL == 0L) {
                Log.i(
                    TAG,
                    "[Telemetry] requests=$completed active=${activeTransfers.get()} " +
                            "bytes=${totalBytesTransferred.get()} failures=${totalTransferFailures.get()}"
                )
            }
        }

        fun onError(stage: String, error: Throwable?) {
            totalTransferFailures.incrementAndGet()
            val totalDuration = System.currentTimeMillis() - startTime
            failureStage = stage
            failureReason = error?.message ?: "unknown"
            Log.w(
                TAG,
                "[#$requestId]$sessionTag FAILED at '$stage' in ${totalDuration}ms: " +
                        "${error?.javaClass?.simpleName}: ${error?.message}"
            )
        }
    }

    /**
     * Dedicated OkHttpClient optimized for media and HLS proxying.
     * Redirects are followed manually so credentials can be scoped by host.
     */
    internal val proxyHttpClient: OkHttpClient by lazy {
        val builder = try {
            app.baseClient.newBuilder()
        } catch (_: Throwable) {
            OkHttpClient.Builder()
        }
        builder
            .cache(null)
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 128
                maxRequestsPerHost = 32
            })
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request()
                val acceptEncoding = request.header("Accept-Encoding")
                if (acceptEncoding != null) {
                    // For range requests or explicit identity encoding, preserve it so
                    // compressed transfer encoding does not make byte offsets inconsistent.
                    if (acceptEncoding.equals("identity", ignoreCase = true) || request.header("Range") != null) {
                        chain.proceed(request)
                    } else {
                        chain.proceed(request.newBuilder().removeHeader("Accept-Encoding").build())
                    }
                } else {
                    chain.proceed(request)
                }
            }
            .build()
    }

    val port: Int
        get() = serverSocket?.localPort ?: -1

    class ProxySession(
        val sessionId: String,
        val request: ExternalPlaybackRequest,
        val upstreamUrl: String,
        val effectiveHeaders: Map<String, String>,
        val createdAt: Long = System.currentTimeMillis(),
        val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
        val ttlMs: Long = DEFAULT_TTL_MS
    ) {
        private val activeRequests = AtomicInteger(0)
        private val lastAccess = AtomicLong(System.currentTimeMillis())
        @Volatile private var retired = false

        // Deterministic bidirectional resource mappings:
        // urlToId: URL -> resourceId
        // idToUrl: resourceId -> URL
        private val idToUrl = ConcurrentHashMap<String, String>()
        private val urlToId = ConcurrentHashMap<String, String>()
        private val resourcesById = ConcurrentHashMap<String, ResourceRecord>()

        // Known safe hosts in this playback session (e.g. redirect targets, variant CDNs)
        private val knownHosts = ConcurrentHashMap.newKeySet<String>()

        init {
            extractHost(upstreamUrl)?.let { knownHosts.add(it) }
            extractHost(request.masterUrl)?.let { knownHosts.add(it) }
            request.allVariants.forEach { v -> extractHost(v.url)?.let { knownHosts.add(it) } }
        }

        fun addKnownHost(url: String) {
            extractHost(url)?.let { knownHosts.add(it) }
        }

        fun isKnownSafeHost(url: String): Boolean {
            val host = extractHost(url) ?: return false
            return knownHosts.any { isSameHostOrSubdomain("https://$it", "https://$host") }
        }

        private fun extractHost(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return try {
                URI.create(url).host?.lowercase()
            } catch (_: Throwable) {
                null
            }
        }

        /** Exposed map for backward compatibility and test assertions. */
        val resourceMap: ConcurrentHashMap<String, String>
            get() = idToUrl

        val subtitleMap = ConcurrentHashMap<String, ExternalSubtitle>()

        fun isExpired(): Boolean {
            if (retired) return activeRequests.get() == 0
            if (activeRequests.get() > 0) return false

            val now = System.currentTimeMillis()
            val isIdle = (now - lastAccess.get()) > idleTimeoutMs
            val isOverTtl = (now - createdAt) > ttlMs
            return isIdle || isOverTtl
        }

        fun isRetired(): Boolean = retired

        fun retire() {
            retired = true
        }

        fun touch() {
            lastAccess.set(System.currentTimeMillis())
        }

        fun beginRequest(): Int {
            touch()
            return activeRequests.incrementAndGet()
        }

        fun endRequest(): Int {
            touch()
            return activeRequests.decrementAndGet().coerceAtLeast(0)
        }

        fun getActiveRequests(): Int = activeRequests.get()
        fun getIdleAgeMs(): Long = System.currentTimeMillis() - lastAccess.get()
        fun getTotalAgeMs(): Long = System.currentTimeMillis() - createdAt

        /**
         * Deterministically registers a resource URL with optional custom headers.
         * The same URL always produces the identical resourceId across playlist refreshes,
         * backward seeks, and concurrent requests.
         */
        fun registerResource(
            url: String,
            headers: Map<String, String>? = null,
            type: ResourceType = inferResourceType(url),
            originPlaylistUrl: String? = null,
            byteRange: String? = null
        ): String {
            val existingId = urlToId[url]
            return registerResourceWithId(
                id = existingId ?: generateDeterministicId(url),
                url = url,
                headers = headers,
                type = type,
                originPlaylistUrl = originPlaylistUrl,
                byteRange = byteRange
            )
        }

        /**
         * Registers a resource under an explicitly supplied collision-resistant ID.
         * Used for subtitles, where the same upstream URL can appear multiple times
         * with distinct names.
         */
        fun registerResourceWithId(
            id: String,
            url: String,
            headers: Map<String, String>? = null,
            type: ResourceType = inferResourceType(url),
            originPlaylistUrl: String? = null,
            byteRange: String? = null
        ): String {
            if (originPlaylistUrl != null && isKnownSafeHost(originPlaylistUrl)) {
                addKnownHost(url)
            }
            idToUrl.putIfAbsent(id, url)
            urlToId.putIfAbsent(url, id)

            val existingRecord = resourcesById[id]
            if (existingRecord == null) {
                resourcesById.putIfAbsent(
                    id,
                    ResourceRecord(
                        id = id,
                        url = url,
                        type = type,
                        headers = headers ?: emptyMap(),
                        originPlaylistUrl = originPlaylistUrl,
                        byteRange = byteRange
                    )
                )
            } else {
                headers?.let { existingRecord.mergeHeaders(it) }
                if (existingRecord.originPlaylistUrl == null) {
                    existingRecord.originPlaylistUrl = originPlaylistUrl
                }
                if (existingRecord.byteRange == null) {
                    existingRecord.byteRange = byteRange
                }
            }
            return id
        }

        fun deterministicId(value: String): String = generateDeterministicId(value)

        fun getResourceRecord(resIdOrUrl: String): ResourceRecord? {
            resourcesById[resIdOrUrl]?.let { return it }
            val id = urlToId[resIdOrUrl]
                ?: idToUrl[resIdOrUrl]?.let { urlToId[it] }
                ?: resourcesById.keys.firstOrNull { it == resIdOrUrl.substringBeforeLast(".") }
            if (id != null) {
                resourcesById[id]?.let { return it }
            }
            val withoutExt = resIdOrUrl.substringBeforeLast(".")
            return resourcesById[withoutExt]
        }

        fun getResourceHeaders(resIdOrUrl: String): Map<String, String>? {
            getResourceRecord(resIdOrUrl)?.let { return it.headers }
            return null
        }

        /**
         * Resolves a resource ID to its upstream URL.
         * Handles both exact IDs and IDs containing advertised extensions (e.g. .ts, .m3u8).
         */
        fun resolveResourceId(resId: String): String? {
            idToUrl[resId]?.let { return it }

            val withoutExt = resId.substringBeforeLast(".")
            idToUrl[withoutExt]?.let { return it }

            // Fallback: Base64Url-encoded URL token
            if (resId.startsWith("u_")) {
                try {
                    val token = withoutExt.removePrefix("u_")
                    val decoded = String(
                        java.util.Base64.getUrlDecoder().decode(token),
                        Charsets.UTF_8
                    )
                    if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                        idToUrl[resId] = decoded
                        idToUrl[withoutExt] = decoded
                        return decoded
                    }
                } catch (_: Throwable) {
                }
            }

            return null
        }

        private fun generateDeterministicId(url: String): String {
            return try {
                val md = MessageDigest.getInstance("SHA-256")
                val bytes = md.digest(url.toByteArray(Charsets.UTF_8))
                // 128 bits of SHA-256; collision-free in practice for a playback session.
                bytes.take(16).joinToString("") { "%02x".format(it) }
            } catch (_: Throwable) {
                Integer.toHexString(url.hashCode())
            }
        }
    }

    fun inferResourceType(url: String): ResourceType {
        val cleanUrl = url.substringBefore("?").substringBefore("#")
        return when {
            cleanUrl.endsWith(".m3u8", ignoreCase = true) -> ResourceType.MEDIA_PLAYLIST
            cleanUrl.endsWith(".key", ignoreCase = true) -> ResourceType.KEY
            cleanUrl.endsWith(".vtt", ignoreCase = true) ||
                    cleanUrl.endsWith(".webvtt", ignoreCase = true) ||
                    cleanUrl.endsWith(".srt", ignoreCase = true) -> ResourceType.SUBTITLE
            cleanUrl.endsWith(".mp4", ignoreCase = true) && cleanUrl.contains("init", ignoreCase = true) ->
                ResourceType.INIT_MAP
            cleanUrl.endsWith(".mp4", ignoreCase = true) ||
                    cleanUrl.endsWith(".m4s", ignoreCase = true) ||
                    cleanUrl.endsWith(".ts", ignoreCase = true) ||
                    cleanUrl.endsWith(".aac", ignoreCase = true) ||
                    cleanUrl.endsWith(".m4a", ignoreCase = true) -> ResourceType.SEGMENT
            else -> ResourceType.OTHER
        }
    }

    fun isKnownBinaryResource(type: ResourceType): Boolean {
        return type == ResourceType.SEGMENT ||
                type == ResourceType.INIT_MAP ||
                type == ResourceType.KEY ||
                type == ResourceType.SUBTITLE
    }

    fun isExplicitHlsMimeType(contentType: String): Boolean {
        return contentType.contains("application/vnd.apple.mpegurl", ignoreCase = true) ||
                contentType.contains("application/x-mpegurl", ignoreCase = true) ||
                contentType.contains("audio/mpegurl", ignoreCase = true)
    }

    @Synchronized
    fun ensureStarted(): Int {
        if (isRunning.get() && serverSocket != null && !serverSocket!!.isClosed) {
            return port
        }

        try {
            // Bind strictly to loopback address 127.0.0.1 on an available port
            val socket = ServerSocket(0, 128, InetAddress.getByName("127.0.0.1"))
            socket.reuseAddress = true
            serverSocket = socket
            isRunning.set(true)

            workerPool.execute {
                acceptLoop(socket)
            }
            startCleanupScheduler()

            Log.d(TAG, "Server started on 127.0.0.1:${socket.localPort}")
            return socket.localPort
        } catch (t: Throwable) {
            isRunning.set(false)
            Log.e(TAG, "Failed to start local stream proxy server: ${t.message}")
            throw IllegalStateException("Failed to bind local loopback proxy", t)
        }
    }

    private fun startCleanupScheduler() {
        if (cleanupExecutor?.isShutdown == false) return
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "LocalStreamProxyCleanup").apply { isDaemon = true }
        }
        cleanupExecutor = executor
        executor.scheduleWithFixedDelay(
            {
                try {
                    cleanupExpiredSessions()
                } catch (t: Throwable) {
                    Log.w(TAG, "Session cleanup failed: ${t.message}")
                }
            },
            CLEANUP_INTERVAL_SECONDS,
            CLEANUP_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (isRunning.get() && !socket.isClosed) {
            try {
                val client = socket.accept()
                // Strict loopback verification
                if (!client.inetAddress.isLoopbackAddress) {
                    try {
                        client.close()
                    } catch (_: Throwable) {
                    }
                    continue
                }
                client.soTimeout = 60_000
                try {
                    workerPool.execute {
                        handleClient(client)
                    }
                } catch (_: RejectedExecutionException) {
                    Log.w(TAG, "Rejected client connection: proxy worker limit reached")
                    try {
                        client.close()
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: SocketException) {
                // Server socket closed
                break
            } catch (t: Throwable) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error accepting client connection: ${t.message}")
                }
            }
        }
    }

    fun startSession(
        request: ExternalPlaybackRequest,
        idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
        ttlMs: Long = DEFAULT_TTL_MS
    ): ProxySession {
        ensureStarted()
        cleanupExpiredSessions()

        val token = UUID.randomUUID().toString().replace("-", "")
        val session = ProxySession(
            sessionId = token,
            request = request,
            upstreamUrl = request.mediaUrl,
            effectiveHeaders = request.headers,
            idleTimeoutMs = idleTimeoutMs,
            ttlMs = ttlMs
        )

        // Pre-register any known variants with their specific headers
        request.allVariants.forEach { variant ->
            session.registerResource(
                variant.url,
                variant.headers,
                ResourceType.MEDIA_PLAYLIST,
                request.masterUrl ?: request.mediaUrl
            )
        }

        // Register subtitles
        request.subtitles.forEachIndexed { index, sub ->
            session.registerSubtitle(index, sub)
        }

        sessions[token] = session
        Log.d(
            TAG,
            "Session created: id=${token.take(8)} upstream=${sanitizeUrl(request.mediaUrl)} " +
                    "variants=${request.allVariants.size} subtitles=${request.subtitles.size}"
        )
        return session
    }

    fun getSession(sessionId: String): ProxySession? {
        val session = sessions[sessionId] ?: return null
        if (session.isExpired()) {
            sessions.remove(sessionId)
            Log.d(
                TAG,
                "Session expired on lookup: id=${sessionId.take(8)} " +
                        "idleAge=${session.getIdleAgeMs()}ms"
            )
            return null
        }
        if (session.isRetired()) return null
        session.touch()
        return session
    }

    fun hasSessions(): Boolean = sessions.isNotEmpty()

    fun sessionCount(): Int = sessions.size

    fun createStreamUrl(session: ProxySession): String {
        val isM3u8 = session.request.linkType == ExtractorLinkType.M3U8 ||
                session.upstreamUrl.contains(".m3u8", ignoreCase = true) ||
                !session.request.masterUrl.isNullOrBlank() ||
                session.request.allVariants.isNotEmpty()
        val endpoint = if (isM3u8) "master.m3u8" else "media"
        return "http://127.0.0.1:$port/session/${session.sessionId}/$endpoint"
    }

    fun createSubtitleUrl(session: ProxySession, index: Int, subtitle: ExternalSubtitle): String {
        val subId = session.registerSubtitle(index, subtitle)
        return "http://127.0.0.1:$port/session/${session.sessionId}/sub/$subId.vtt"
    }

    fun createLicenseUrl(session: ProxySession): String {
        return "http://127.0.0.1:$port/session/${session.sessionId}/license"
    }

    fun stopSession(sessionId: String) {
        sessions.remove(sessionId)?.let {
            it.retire()
            Log.d(
                TAG,
                "Session stopped explicitly: id=${sessionId.take(8)} " +
                        "activeRequests=${it.getActiveRequests()}"
            )
        }
    }

    /**
     * Removes only sessions that are truly expired and have no active transfer.
     * Active streams keep their resource mappings alive.
     */
    fun cleanupExpiredSessions() {
        sessions.entries.removeIf { entry ->
            val session = entry.value
            val expired = session.isExpired()
            if (expired) {
                session.retire()
                Log.d(
                    TAG,
                    "Cleaning expired session: id=${entry.key.take(8)} " +
                            "idleAge=${session.getIdleAgeMs()}ms active=${session.getActiveRequests()}"
                )
            }
            expired
        }
    }

    /**
     * Stops the listening socket when no playback session remains. Safe to call
     * from a foreground service; active sessions are never interrupted.
     */
    @Synchronized
    fun shutdownIfIdle(): Boolean {
        if (sessions.isNotEmpty()) return false
        stopListening()
        return true
    }

    @Synchronized
    private fun stopListening() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        cleanupExecutor?.shutdownNow()
        cleanupExecutor = null
        Log.d(TAG, "Server stopped")
    }

    private fun ProxySession.registerSubtitle(index: Int, subtitle: ExternalSubtitle): String {
        val subId = deterministicId("${subtitle.url}#sub#$index")
        registerResourceWithId(
            id = subId,
            url = subtitle.url,
            headers = subtitle.headers,
            type = ResourceType.SUBTITLE,
            originPlaylistUrl = request.mediaUrl
        )
        subtitleMap[subId] = subtitle
        return subId
    }

    private data class ParsedHttpRequest(
        val method: String,
        val target: String,
        val headers: Map<String, String>
    )

    private fun handleClient(client: Socket) {
        var clientIn: InputStream? = null
        var clientOut: OutputStream? = null
        try {
            clientIn = client.getInputStream()
            val rawOut = client.getOutputStream()
            clientOut = BufferedClientOutputStream(rawOut, DEFAULT_CLIENT_BUFFER_SIZE)

            val parsed = readHttpRequest(clientIn) ?: return
            val method = parsed.method.uppercase()
            val fullPath = parsed.target
            val clientHeaders = parsed.headers

            // CORS Pre-flight
            if (method == "OPTIONS") {
                writeCorsOk(clientOut)
                return
            }

            // Route parsing: /session/{sessionId}/{action}/{optionalSubPath}
            val pathSegments = fullPath.substringBefore("?").split("/").filter { it.isNotEmpty() }
            if (pathSegments.size < 3 || pathSegments[0] != "session") {
                writeNotFound(clientOut, "Invalid route")
                return
            }

            val sessionId = pathSegments[1]
            val session = getSession(sessionId)
            if (session == null) {
                writeError(clientOut, 410, "Playback session expired or invalid")
                return
            }

            val action = pathSegments[2]
            session.beginRequest()
            try {
                when {
                    action == "master.m3u8" || action == "media" ||
                            action == "playlist.m3u8" || action == "index.m3u8" -> {
                        handleMasterOrMediaRequest(
                            method,
                            session,
                            clientHeaders,
                            clientOut,
                            expectsPlaylist = action != "media"
                        )
                    }

                    action == "res" && pathSegments.size >= 4 -> {
                        val rawResId = pathSegments[3].substringBefore("?")
                        val record = session.getResourceRecord(rawResId)
                        val targetUrl = record?.url ?: session.resolveResourceId(rawResId)
                        if (targetUrl == null) {
                            Log.w(TAG, "Resource not found in session ${session.sessionId.take(8)}")
                            writeNotFound(clientOut, "Resource not found")
                            return
                        }
                        handleMediaRequest(
                            method = method,
                            targetUrl = targetUrl,
                            session = session,
                            clientHeaders = clientHeaders,
                            clientOut = clientOut,
                            resource = record,
                            expectedPlaylist = record?.type == ResourceType.MASTER_PLAYLIST ||
                                    record?.type == ResourceType.MEDIA_PLAYLIST
                        )
                    }

                    action == "sub" && pathSegments.size >= 4 -> {
                        val rawSubId = pathSegments[3].substringBefore("?").removeSuffix(".vtt")
                        val subtitle = session.subtitleMap[rawSubId]
                        if (subtitle == null) {
                            writeNotFound(clientOut, "Subtitle not found")
                            return
                        }
                        handleSubtitleRequest(method, subtitle, session, clientHeaders, clientOut)
                    }

                    action == "license" -> {
                        handleDrmLicenseRequest(method, clientIn, clientHeaders, session, clientOut)
                    }

                    else -> {
                        writeNotFound(clientOut, "Unknown action: $action")
                    }
                }
            } finally {
                session.endRequest()
            }
        } catch (_: SocketException) {
            Log.d(TAG, "Client disconnected during transfer")
        } catch (t: Throwable) {
            Log.e(TAG, "Error handling client request: ${t.message}")
        } finally {
            try {
                clientOut?.flush()
            } catch (_: Throwable) {
            }
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun handleMasterOrMediaRequest(
        method: String,
        session: ProxySession,
        clientHeaders: Map<String, String>,
        clientOut: OutputStream,
        expectsPlaylist: Boolean
    ) {
        val masterUrl = session.request.masterUrl?.takeIf { it.isNotBlank() }
        val targetUrl = masterUrl ?: session.upstreamUrl
        val headers = if (masterUrl != null && session.request.masterHeaders.isNotEmpty()) {
            session.request.masterHeaders
        } else {
            session.effectiveHeaders
        }

        val success = handleMediaRequest(
            method = method,
            targetUrl = targetUrl,
            session = session,
            clientHeaders = clientHeaders,
            clientOut = clientOut,
            specificHeaders = headers,
            isMasterRoute = true,
            fallbackOnFailure = targetUrl != session.upstreamUrl,
            expectedPlaylist = expectsPlaylist
        )

        if (!success && targetUrl != session.upstreamUrl) {
            Log.w(
                TAG,
                "Master playlist fetch failed for ${sanitizeUrl(targetUrl)}, " +
                        "falling back to selected media URL ${sanitizeUrl(session.upstreamUrl)}"
            )
            handleMediaRequest(
                method = method,
                targetUrl = session.upstreamUrl,
                session = session,
                clientHeaders = clientHeaders,
                clientOut = clientOut,
                isMasterRoute = true,
                fallbackOnFailure = false,
                expectedPlaylist = null
            )
        }
    }

    fun buildSyntheticMasterPlaylist(
        variants: List<HlsVariant>,
        session: ProxySession,
        proxyBaseUrl: String
    ): String {
        val validVariants = variants.filter { it.url.isNotBlank() }
        if (validVariants.size <= 1) return ""

        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:3\n")
        val sorted = validVariants.sortedByDescending { it.quality }
        for (v in sorted) {
            val resId = session.registerResource(
                v.url,
                v.headers,
                ResourceType.MEDIA_PLAYLIST,
                session.request.masterUrl ?: session.upstreamUrl
            )
            val proxyUri = "$proxyBaseUrl/session/${session.sessionId}/res/$resId.m3u8"
            val bw = v.bandwidth ?: ExternalPlaybackRequestBuilder.deriveBandwidth(v.quality)
            val res = v.resolution ?: ExternalPlaybackRequestBuilder.deriveResolution(v.quality)
            val resAttr = if (res.isNotBlank()) ",RESOLUTION=$res" else ""
            val codeAttr = if (!v.codecs.isNullOrBlank()) ",CODECS=\"${v.codecs}\"" else ""
            val nameAttr = if (!v.name.isNullOrBlank()) ",NAME=\"${v.name}\"" else ""
            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=$bw$resAttr$codeAttr$nameAttr\n")
            sb.append(proxyUri).append("\n")
        }
        return sb.toString()
    }

    fun checkIsHls(
        source: BufferedSource,
        contentType: String,
        targetUrl: String
    ): Boolean {
        hlsInspectionCount.incrementAndGet()
        // 1. Explicit HLS MIME Type
        if (isExplicitHlsMimeType(contentType)) {
            return true
        }

        // 2. Peek into stream to detect #EXTM3U signature (supports UTF-8 BOM and leading whitespace)
        try {
            val peek = source.peek()
            peek.request(512)
            val buffer = peek.buffer
            if (buffer.size >= 7) {
                val sampleBytes = buffer.snapshot().toByteArray()
                var offset = 0
                // Skip UTF-8 BOM if present (EF BB BF)
                if (sampleBytes.size >= 3 &&
                    sampleBytes[0] == 0xEF.toByte() &&
                    sampleBytes[1] == 0xBB.toByte() &&
                    sampleBytes[2] == 0xBF.toByte()
                ) {
                    offset = 3
                }
                // Skip leading whitespace / newlines
                while (offset < sampleBytes.size &&
                    (sampleBytes[offset] == ' '.code.toByte() ||
                            sampleBytes[offset] == '\r'.code.toByte() ||
                            sampleBytes[offset] == '\n'.code.toByte() ||
                            sampleBytes[offset] == '\t'.code.toByte())
                ) {
                    offset++
                }
                if (sampleBytes.size - offset >= 7) {
                    val sig = String(sampleBytes, offset, 7, Charsets.US_ASCII)
                    if (sig.equals("#EXTM3U", ignoreCase = true)) {
                        return true
                    }
                }
            }
        } catch (_: Throwable) {
        }

        // 3. Fallback: if URL ends with .m3u8, but NOT HTML or definite binary container
        val cleanUrl = targetUrl.substringBefore("?").substringBefore("#")
        val hasM3u8Ext = cleanUrl.endsWith(".m3u8", ignoreCase = true)
        val isHtml = contentType.contains("text/html", ignoreCase = true)
        val isDefiniteBinaryMedia = (contentType.startsWith("video/", ignoreCase = true) && !hasM3u8Ext) ||
                (contentType.equals("application/octet-stream", ignoreCase = true) &&
                        (cleanUrl.endsWith(".ts", ignoreCase = true) ||
                                cleanUrl.endsWith(".m4s", ignoreCase = true) ||
                                cleanUrl.endsWith(".mp4", ignoreCase = true) ||
                                cleanUrl.endsWith(".key", ignoreCase = true)))

        return hasM3u8Ext && !isHtml && !isDefiniteBinaryMedia
    }

    fun isSafeHost(session: ProxySession, targetUrl: String): Boolean {
        if (isSameHostOrSubdomain(session.upstreamUrl, targetUrl)) return true
        if (!session.request.masterUrl.isNullOrBlank() &&
            isSameHostOrSubdomain(session.request.masterUrl, targetUrl)
        ) {
            return true
        }
        return session.isKnownSafeHost(targetUrl)
    }

    private fun handleMediaRequest(
        method: String,
        targetUrl: String,
        session: ProxySession,
        clientHeaders: Map<String, String>,
        clientOut: OutputStream,
        specificHeaders: Map<String, String>? = null,
        isMasterRoute: Boolean = false,
        fallbackOnFailure: Boolean = false,
        resource: ResourceRecord? = null,
        expectedPlaylist: Boolean? = null
    ): Boolean {
        activeTransfers.incrementAndGet()
        try {
            val reqId = requestSeq.incrementAndGet()
            val record = resource ?: session.getResourceRecord(targetUrl)
            val inferredType = record?.type ?: inferResourceType(targetUrl)
            val isMediaFile = inferredType == ResourceType.SEGMENT ||
                    inferredType == ResourceType.INIT_MAP ||
                    inferredType == ResourceType.KEY
            val isM3u8Url = targetUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true)
            val isPlaylistExpected = expectedPlaylist ?: (
                    isMasterRoute ||
                            inferredType == ResourceType.MASTER_PLAYLIST ||
                            inferredType == ResourceType.MEDIA_PLAYLIST ||
                            (isM3u8Url && !isMediaFile)
                    )

            val rangeHeader = clientHeaders["range"]
            val forwardRange = !isPlaylistExpected &&
                    inferredType != ResourceType.KEY &&
                    inferredType != ResourceType.SUBTITLE &&
                    !rangeHeader.isNullOrBlank()

            val resType = when {
                isMasterRoute -> ResourceType.MASTER_PLAYLIST.label
                else -> inferredType.label
            }

            val diag = RequestDiagnostic(
                requestId = reqId,
                method = method,
                routeType = if (isMasterRoute) "master" else "res",
                resourceType = resType,
                targetUrl = targetUrl,
                rangeReceived = rangeHeader,
                rangeForwarded = forwardRange,
                sessionId = session.sessionId,
                resourceId = record?.id
            )
            diag.logStart()

            val requestBuilder = Request.Builder().url(targetUrl)
            val isSafe = isSafeHost(session, targetUrl)
            applyUpstreamHeaders(
                requestBuilder = requestBuilder,
                session = session,
                targetUrl = targetUrl,
                specificHeaders = specificHeaders ?: record?.headers,
                isSafe = isSafe
            )

            if (forwardRange) {
                requestBuilder.header("Range", rangeHeader)
                val hasExplicitEncoding = (specificHeaders?.keys ?: emptySet()).any {
                    it.equals("accept-encoding", ignoreCase = true)
                } || session.effectiveHeaders.keys.any {
                    it.equals("accept-encoding", ignoreCase = true)
                }
                if (!hasExplicitEncoding) {
                    requestBuilder.header("Accept-Encoding", "identity")
                }
            }

            // Bypass stale HTTP cache for HLS playlist refreshes
            if (isPlaylistExpected) {
                requestBuilder.cacheControl(CacheControl.FORCE_NETWORK)
            }

            if (method == "HEAD") {
                requestBuilder.head()
            }

            val response: Response = try {
                executeUpstream(requestBuilder.build())
            } catch (t: Throwable) {
                diag.onError("connecting/waiting_response", t)
                Log.e(
                    TAG,
                    "Upstream request failed for ${sanitizeUrl(targetUrl)}: " +
                            "${t.javaClass.simpleName}: ${t.message}"
                )
                if (!fallbackOnFailure) {
                    writeError(clientOut, 502, "Bad Gateway: ${t.message}")
                }
                return false
            }

            try {
                val responseBody = response.body
                val contentType = response.header("Content-Type") ?: ""
                val contentLength = responseBody.contentLength()
                diag.onHeadersReceived(response.code, contentType, contentLength)

                if (!response.isSuccessful && response.code != 206) {
                    diag.onError("upstream_status_${response.code}", null)
                    Log.w(
                        TAG,
                        "Upstream error response: code=${response.code} " +
                                "message=${response.message} for ${sanitizeUrl(targetUrl)}"
                    )
                    if (!fallbackOnFailure) {
                        writeError(clientOut, response.code, response.message)
                    }
                    return false
                }

                val isExplicitHls = isExplicitHlsMimeType(contentType)
                val shouldSkipInspection = !isPlaylistExpected &&
                        record != null &&
                        isKnownBinaryResource(record.type) &&
                        !isExplicitHls

                val isHls = if (shouldSkipInspection) {
                    false
                } else {
                    checkIsHls(responseBody.source(), contentType, targetUrl)
                }

                if (isPlaylistExpected && !isHls) {
                    diag.onError("unexpected_content_type", null)
                    Log.w(
                        TAG,
                        "Expected HLS playlist for ${sanitizeUrl(targetUrl)} but got non-HLS " +
                                "(contentType='$contentType')"
                    )
                    if (!fallbackOnFailure) {
                        writeError(clientOut, 502, "Bad Gateway: Upstream did not return a valid HLS playlist")
                    }
                    return false
                }

                if (isHls) {
                    val bodyText = try {
                        responseBody.string()
                    } catch (t: Throwable) {
                        diag.onError("reading_playlist", t)
                        if (!fallbackOnFailure) {
                            writeError(clientOut, 502, "Bad Gateway: Could not read HLS playlist")
                        }
                        return false
                    }
                    if (bodyText.isBlank() || (!bodyText.contains("#EXTM3U") && !bodyText.contains("#EXT"))) {
                        diag.onError("empty_playlist", null)
                        if (!fallbackOnFailure) {
                            writeError(clientOut, 502, "Bad Gateway: Empty or malformed HLS playlist from upstream")
                        }
                        return false
                    }

                    val currentPlaylistUrl = response.request.url.toString()
                    session.addKnownHost(currentPlaylistUrl)
                    val proxyBaseUrl = "http://127.0.0.1:$port"

                    val rewritten = rewriteHlsPlaylist(
                        bodyText,
                        currentPlaylistUrl,
                        session,
                        proxyBaseUrl,
                        specificHeaders ?: record?.headers ?: session.effectiveHeaders
                    )
                    val bodyBytes = rewritten.toByteArray(Charsets.UTF_8)

                    diag.onFirstByte()
                    diag.bytesTransferred = bodyBytes.size.toLong()

                    val responseHeaders = mutableMapOf(
                        "Content-Type" to "application/vnd.apple.mpegurl",
                        "Content-Length" to bodyBytes.size.toString(),
                        "Access-Control-Allow-Origin" to "*",
                        "Accept-Ranges" to "none",
                        "Cache-Control" to "no-cache, no-store, must-revalidate",
                        "Pragma" to "no-cache",
                        "Connection" to "close"
                    )
                    writeResponse(
                        clientOut,
                        200,
                        "OK",
                        responseHeaders,
                        if (method != "HEAD") bodyBytes else ByteArray(0)
                    )
                    diag.onComplete()
                    return true
                } else {
                    return handleMediaStream(method, response, targetUrl, rangeHeader, clientOut, diag)
                }
            } catch (t: Throwable) {
                diag.onError("streaming_body", t)
                throw t
            } finally {
                response.close()
            }
        } finally {
            activeTransfers.decrementAndGet()
        }
    }

    /**
     * Applies the session header bundle, then explicit per-resource headers.
     * Sensitive headers are only sent to safe hosts; per-resource headers are
     * explicitly scoped by the playlist that declared them.
     */
    private fun applyUpstreamHeaders(
        requestBuilder: Request.Builder,
        session: ProxySession,
        targetUrl: String,
        specificHeaders: Map<String, String>?,
        isSafe: Boolean
    ) {
        val addedHeaderNames = mutableSetOf<String>()

        fun addHeader(name: String, value: String, explicit: Boolean) {
            val lower = name.lowercase()
            if (isHopByHopHeader(lower)) return
            if (!explicit && !isSafe && isSensitiveHeader(lower)) return
            if (addedHeaderNames.add(lower)) {
                requestBuilder.header(name, value)
            }
        }

        // 1. Session-wide headers (Cookie, Referer, User-Agent, Origin, etc.)
        for ((key, value) in session.effectiveHeaders) {
            addHeader(key, value, explicit = false)
        }

        // 2. Explicit request context if not already present
        session.request.userAgent?.takeIf { it.isNotBlank() }?.let { ua ->
            addHeader("User-Agent", ua, explicit = false)
        }
        if (isSafe) {
            session.request.cookie?.takeIf { it.isNotBlank() }?.let { cookie ->
                addHeader("Cookie", cookie, explicit = false)
            }
            session.request.authorization?.takeIf { it.isNotBlank() }?.let { auth ->
                addHeader("Authorization", auth, explicit = false)
            }
        }
        session.request.referer?.takeIf { it.isNotBlank() }?.let { referer ->
            addHeader("Referer", referer, explicit = false)
        }
        session.request.origin?.takeIf { it.isNotBlank() }?.let { origin ->
            addHeader("Origin", origin, explicit = false)
        }

        // 3. Per-resource headers last; these were declared by the playlist that
        // produced the resource and are therefore explicitly scoped.
        if (specificHeaders != null) {
            for ((key, value) in specificHeaders) {
                addHeader(key, value, explicit = true)
            }
        }
    }

    /**
     * Follows redirects manually. Cross-site redirects drop credentials while
     * same-site redirects retain the full header bundle.
     */
    private fun executeUpstream(initialRequest: Request): Response {
        var request = initialRequest
        var redirects = 0
        val originalHost = initialRequest.url.host

        while (true) {
            val response = proxyHttpClient.newCall(request).execute()
            if (response.code !in setOf(301, 302, 303, 307, 308)) {
                return response
            }

            val location = response.header("Location")
            if (location.isNullOrBlank() || redirects >= MAX_REDIRECTS) {
                return response
            }

            val newUrl = request.url.resolve(location)
            if (newUrl == null || (newUrl.scheme != "http" && newUrl.scheme != "https")) {
                return response
            }

            response.close()
            redirects++

            val sameSite = isSameSite(originalHost, newUrl.host)
            val builder = request.newBuilder().url(newUrl)
            if (!sameSite) {
                removeSensitiveHeaders(builder, request)
                Log.d(
                    TAG,
                    "Cross-site redirect ${sanitizeUrl(request.url.toString())} -> " +
                            sanitizeUrl(newUrl.toString()) + " (credentials dropped)"
                )
            } else {
                Log.d(
                    TAG,
                    "Redirect ${sanitizeUrl(request.url.toString())} -> " +
                            sanitizeUrl(newUrl.toString())
                )
            }

            val status = response.code
            if (status == 303 ||
                ((status == 301 || status == 302) &&
                        request.method != "GET" && request.method != "HEAD")
            ) {
                builder.method("GET", null)
                builder.removeHeader("Content-Type")
                builder.removeHeader("Content-Length")
            }

            request = builder.build()
        }
    }

    private fun removeSensitiveHeaders(builder: Request.Builder, request: Request) {
        for (name in request.headers.names()) {
            if (isSensitiveHeader(name.lowercase())) {
                builder.removeHeader(name)
            }
        }
    }

    private fun isSensitiveHeader(lowerName: String): Boolean {
        return lowerName == "cookie" ||
                lowerName == "authorization" ||
                lowerName == "proxy-authorization" ||
                lowerName.contains("token") ||
                lowerName.contains("auth") ||
                lowerName.contains("api-key") ||
                lowerName.contains("apikey") ||
                lowerName.contains("secret") ||
                lowerName.contains("signature")
    }

    private fun isSameSite(hostA: String?, hostB: String?): Boolean {
        if (hostA.isNullOrBlank() || hostB.isNullOrBlank()) return false
        return isSameHostOrSubdomain("https://$hostA", "https://$hostB")
    }

    private fun handleMediaStream(
        method: String,
        response: Response,
        targetUrl: String,
        rangeHeader: String?,
        clientOut: OutputStream,
        diag: RequestDiagnostic
    ): Boolean {
        val responseBody = response.body
        val upstreamCode = response.code
        val contentEncoding = response.header("Content-Encoding")
        val upstreamAcceptRanges = response.header("Accept-Ranges")

        val rangeSpec = parseRangeSpec(rangeHeader)
        val effectiveLength = responseBody.contentLength()

        // Check if client requested range, but upstream returned 200 OK.
        // Never slice an encoded representation; ranges apply to the actual bytes.
        val needsLocalRangeSlicing =
            upstreamCode == 200 && rangeSpec != null && effectiveLength >= 0L && contentEncoding == null

        val responseHeaders = linkedMapOf(
            "Access-Control-Allow-Origin" to "*",
            "Connection" to "close"
        )
        response.header("Content-Type")?.let { responseHeaders["Content-Type"] = it }
        contentEncoding?.let { responseHeaders["Content-Encoding"] = it }

        // Forward upstream Content-Disposition or fallback to URL metadata
        val upstreamDisposition = response.header("Content-Disposition")
        if (!upstreamDisposition.isNullOrBlank()) {
            responseHeaders["Content-Disposition"] = upstreamDisposition
        } else {
            val fallbackFilename = ExternalMediaMimeResolver.parseContentDispositionFilenameFromUrl(targetUrl)
            if (!fallbackFilename.isNullOrBlank()) {
                responseHeaders["Content-Disposition"] = "inline; filename=\"$fallbackFilename\""
            }
        }

        // Truthful Accept-Ranges handling
        if (upstreamAcceptRanges?.equals("none", ignoreCase = true) == true) {
            responseHeaders["Accept-Ranges"] = "none"
        } else {
            val canServiceRangeLocally = effectiveLength >= 0L && contentEncoding == null
            val upstreamSupportsRanges = upstreamAcceptRanges?.equals("bytes", ignoreCase = true) == true || upstreamCode == 206
            if (upstreamSupportsRanges || canServiceRangeLocally) {
                responseHeaders["Accept-Ranges"] = "bytes"
            } else if (upstreamAcceptRanges != null) {
                responseHeaders["Accept-Ranges"] = upstreamAcceptRanges
            }
        }

        var statusCode: Int
        var statusText: String
        var startOffset = 0L
        var bytesToStream = effectiveLength
        var chunked = false

        if (needsLocalRangeSlicing) {
            val resolvedRange = rangeSpec.resolve(effectiveLength)
            if (resolvedRange != null) {
                val (requestedStart, clampedEnd) = resolvedRange
                statusCode = 206
                statusText = "Partial Content"
                startOffset = requestedStart
                bytesToStream = clampedEnd - requestedStart + 1L
                responseHeaders["Content-Range"] = "bytes $requestedStart-$clampedEnd/$effectiveLength"
                responseHeaders["Content-Length"] = bytesToStream.toString()
            } else {
                responseHeaders["Content-Range"] = "bytes */$effectiveLength"
                writeStatusAndHeaders(clientOut, 416, "Range Not Satisfiable", responseHeaders)
                diag.onError("416_range_not_satisfiable", null)
                // The response has been committed; do not attempt a fallback write.
                return true
            }
        } else {
            statusCode = upstreamCode
            statusText = when (statusCode) {
                200 -> "OK"
                206 -> "Partial Content"
                204 -> "No Content"
                304 -> "Not Modified"
                else -> response.message.ifBlank { "OK" }
            }
            if (effectiveLength >= 0L) {
                responseHeaders["Content-Length"] = effectiveLength.toString()
            } else if (statusCode != 204 && statusCode != 304 && method != "HEAD") {
                responseHeaders["Transfer-Encoding"] = "chunked"
                chunked = true
            }
            response.header("Content-Range")?.let { responseHeaders["Content-Range"] = it }
            response.header("Cache-Control")?.let { responseHeaders["Cache-Control"] = it }
            response.header("ETag")?.let { responseHeaders["ETag"] = it }
            response.header("Last-Modified")?.let { responseHeaders["Last-Modified"] = it }
        }

        Log.i(
            TAG,
            "Progressive media stream: method=$method " +
                    "clientRange=${!rangeHeader.isNullOrBlank()} " +
                    "requestedRange=${rangeHeader ?: "none"} " +
                    "forwardedRange=${diag.rangeForwarded} " +
                    "upstreamStatus=$upstreamCode " +
                    "locallySliced=$needsLocalRangeSlicing " +
                    "contentLength=${responseHeaders["Content-Length"] ?: "unknown"} " +
                    "contentRange=${responseHeaders["Content-Range"] ?: "none"} " +
                    "acceptRanges=${responseHeaders["Accept-Ranges"] ?: "none"} " +
                    "contentType=${responseHeaders["Content-Type"] ?: "none"} " +
                    "contentDisposition=${responseHeaders.containsKey("Content-Disposition")} " +
                    "contentEncoding=${responseHeaders.containsKey("Content-Encoding")}"
        )

        // Flush status line and headers immediately to the client.
        writeStatusAndHeaders(clientOut, statusCode, statusText, responseHeaders)

        if (method == "HEAD" || statusCode == 204 || statusCode == 304) {
            diag.onComplete()
            return true
        }

        val inStream = responseBody.byteStream()
        var totalTransferred = 0L
        var firstByteLogged = false
        var firstBodyWritten = false
        var truncated = false

        try {
            val buffer = getTransferBuffer()
            if (startOffset > 0L) {
                var skipped = 0L
                while (skipped < startOffset) {
                    val n = inStream.skip(startOffset - skipped)
                    if (n <= 0) {
                        val toSkip = minOf(buffer.size.toLong(), startOffset - skipped).toInt()
                        val read = inStream.read(buffer, 0, toSkip)
                        if (read == -1) {
                            truncated = true
                            break
                        }
                        skipped += read
                    } else {
                        skipped += n
                    }
                }
            }

            val bufferedClientOut = clientOut as? BufferedClientOutputStream

            while (!truncated) {
                val remaining = if (bytesToStream >= 0) bytesToStream - totalTransferred else -1L
                if (bytesToStream >= 0 && remaining <= 0L) break

                val toRead = if (bytesToStream >= 0) {
                    minOf(buffer.size.toLong(), remaining).toInt()
                } else {
                    buffer.size
                }
                if (toRead <= 0) break

                val read = try {
                    inStream.read(buffer, 0, toRead)
                } catch (t: Throwable) {
                    diag.onError("upstream_body_read", t)
                    // Headers and possibly some bytes were already sent; the
                    // connection must close rather than emit a second response.
                    return true
                }
                if (read == -1) {
                    if (bytesToStream >= 0 && totalTransferred < bytesToStream) {
                        truncated = true
                    }
                    break
                }

                if (!firstByteLogged) {
                    diag.onFirstByte()
                    firstByteLogged = true
                }

                try {
                    if (chunked) {
                        writeChunk(clientOut, buffer, read)
                    } else {
                        clientOut.write(buffer, 0, read)
                    }

                    val bufferedBytes = bufferedClientOut?.bufferedBytes ?: 0
                    val shouldFlush = !firstBodyWritten ||
                            bufferedBytes >= BATCH_FLUSH_THRESHOLD ||
                            inStream.available() == 0

                    if (shouldFlush) {
                        clientOut.flush()
                        firstBodyWritten = true
                    }
                } catch (t: Throwable) {
                    diag.onError("client_write_disconnect", t)
                    // Client disconnected. The response is already committed.
                    return true
                }

                totalTransferred += read
            }
        } catch (t: Throwable) {
            diag.onError("streaming_transfer", t)
            return true
        }

        if (truncated) {
            diag.bytesTransferred = totalTransferred
            diag.onError("truncated_upstream_body", null)
            Log.w(
                TAG,
                "Upstream body truncated after $totalTransferred of " +
                        "${if (bytesToStream >= 0) bytesToStream else -1} bytes; closing connection"
            )
            // Do not finish a chunked response: the abrupt close signals truncation.
            return true
        }

        if (chunked) {
            try {
                clientOut.write(ZERO_CHUNK)
                clientOut.flush()
            } catch (t: Throwable) {
                diag.onError("client_write_disconnect", t)
                return true
            }
        } else {
            try {
                clientOut.flush()
            } catch (t: Throwable) {
                diag.onError("client_write_disconnect", t)
                return true
            }
        }

        diag.bytesTransferred = totalTransferred
        diag.onComplete()
        return true
    }

    sealed class ByteRangeSpec {
        data class FromStart(val start: Long, val end: Long?) : ByteRangeSpec()
        data class Suffix(val suffixLength: Long) : ByteRangeSpec()

        fun resolve(totalLength: Long): Pair<Long, Long>? {
            if (totalLength <= 0L) return null
            return when (this) {
                is FromStart -> {
                    if (start < 0L || start >= totalLength) return null
                    val resolvedEnd = if (end != null) {
                        if (end < start) return null
                        end.coerceAtMost(totalLength - 1L)
                    } else {
                        totalLength - 1L
                    }
                    Pair(start, resolvedEnd)
                }
                is Suffix -> {
                    if (suffixLength <= 0L) return null
                    val clampedSuffix = suffixLength.coerceAtMost(totalLength)
                    val start = totalLength - clampedSuffix
                    val end = totalLength - 1L
                    Pair(start, end)
                }
            }
        }
    }

    fun parseRangeSpec(rangeHeader: String?): ByteRangeSpec? {
        if (rangeHeader.isNullOrBlank() || !rangeHeader.startsWith("bytes=", ignoreCase = true)) {
            return null
        }
        val rangeVal = rangeHeader.substringAfter("=").trim()
        if (rangeVal.contains(",")) return null
        if (rangeVal.startsWith("-")) {
            val suffix = rangeVal.substring(1).trim().toLongOrNull() ?: return null
            if (suffix <= 0L) return null
            return ByteRangeSpec.Suffix(suffix)
        }
        val parts = rangeVal.split("-")
        if (parts.isEmpty()) return null
        val start = parts[0].trim().toLongOrNull() ?: return null
        if (start < 0L) return null
        val end = if (parts.size > 1 && parts[1].isNotBlank()) {
            val parsedEnd = parts[1].trim().toLongOrNull() ?: return null
            if (parsedEnd < start) return null
            parsedEnd
        } else {
            null
        }
        return ByteRangeSpec.FromStart(start, end)
    }

    fun parseRange(rangeHeader: String?): Pair<Long, Long?>? {
        val spec = parseRangeSpec(rangeHeader) ?: return null
        return when (spec) {
            is ByteRangeSpec.FromStart -> Pair(spec.start, spec.end)
            is ByteRangeSpec.Suffix -> Pair(-spec.suffixLength, null)
        }
    }

    private fun handleSubtitleRequest(
        method: String,
        subtitle: ExternalSubtitle,
        session: ProxySession,
        clientHeaders: Map<String, String>,
        clientOut: OutputStream
    ) {
        val requestBuilder = Request.Builder().url(subtitle.url)
        val isSafe = isSafeHost(session, subtitle.url)
        applyUpstreamHeaders(
            requestBuilder = requestBuilder,
            session = session,
            targetUrl = subtitle.url,
            specificHeaders = subtitle.headers,
            isSafe = isSafe
        )
        if (method == "HEAD") requestBuilder.head()

        val response = try {
            executeUpstream(requestBuilder.build())
        } catch (t: Throwable) {
            Log.w(TAG, "Subtitle fetch failed: ${t.javaClass.simpleName}: ${t.message}")
            writeError(clientOut, 502, "Bad Gateway")
            return
        }

        try {
            if (!response.isSuccessful) {
                writeError(clientOut, response.code, response.message)
                return
            }
            val mime = subtitle.mimeType.ifBlank { response.header("Content-Type") ?: "text/vtt" }
            val contentType = if (mime.contains("text/", ignoreCase = true) ||
                mime.contains("vtt", ignoreCase = true) ||
                mime.contains("ttml", ignoreCase = true)
            ) {
                mime
            } else {
                "text/vtt"
            }
            val headers = linkedMapOf(
                "Content-Type" to contentType,
                "Access-Control-Allow-Origin" to "*",
                "Connection" to "close"
            )
            val length = response.body.contentLength()
            if (length >= 0) headers["Content-Length"] = length.toString()
            writeStatusAndHeaders(clientOut, response.code, response.message.ifBlank { "OK" }, headers)
            if (method != "HEAD") {
                val buffer = getTransferBuffer()
                val inStream = response.body.byteStream()
                var firstBodyWritten = false
                val bufferedClientOut = clientOut as? BufferedClientOutputStream
                if (length >= 0) {
                    var remaining = length
                    while (remaining > 0L) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = inStream.read(buffer, 0, toRead)
                        if (read == -1) break
                        clientOut.write(buffer, 0, read)
                        val bufferedBytes = bufferedClientOut?.bufferedBytes ?: 0
                        if (!firstBodyWritten || bufferedBytes >= BATCH_FLUSH_THRESHOLD || inStream.available() == 0) {
                            clientOut.flush()
                            firstBodyWritten = true
                        }
                        remaining -= read
                    }
                    clientOut.flush()
                } else {
                    while (true) {
                        val read = inStream.read(buffer, 0, buffer.size)
                        if (read == -1) break
                        writeChunk(clientOut, buffer, read)
                        val bufferedBytes = bufferedClientOut?.bufferedBytes ?: 0
                        if (!firstBodyWritten || bufferedBytes >= BATCH_FLUSH_THRESHOLD || inStream.available() == 0) {
                            clientOut.flush()
                            firstBodyWritten = true
                        }
                    }
                    clientOut.write(ZERO_CHUNK)
                    clientOut.flush()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Subtitle transfer failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            response.close()
        }
    }

    private fun handleDrmLicenseRequest(
        method: String,
        clientIn: InputStream,
        clientHeaders: Map<String, String>,
        session: ProxySession,
        clientOut: OutputStream
    ) {
        val licenseUrl = session.request.drmLicenseUrl
        if (licenseUrl == null) {
            writeError(clientOut, 400, "No DRM license URL configured")
            return
        }

        val contentLength = clientHeaders["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength > MAX_LICENSE_BYTES) {
            writeError(clientOut, 413, "DRM license request too large")
            return
        }
        if (clientHeaders["expect"].equals("100-continue", ignoreCase = true)) {
            clientOut.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
            clientOut.flush()
        }
        val requestBodyBytes = readExactly(clientIn, contentLength.toInt())
        val mediaType = (clientHeaders["content-type"] ?: "application/octet-stream").toMediaTypeOrNull()
        val okRequestBody = requestBodyBytes.toRequestBody(mediaType)

        val requestBuilder = Request.Builder()
            .url(licenseUrl)
            .method(method.ifBlank { "POST" }, okRequestBody)

        val isSafe = isSafeHost(session, licenseUrl)
        applyUpstreamHeaders(
            requestBuilder = requestBuilder,
            session = session,
            targetUrl = licenseUrl,
            specificHeaders = session.request.drmHeaders,
            isSafe = isSafe
        )

        val response = try {
            executeUpstream(requestBuilder.build())
        } catch (t: Throwable) {
            Log.e(TAG, "DRM license request failed: ${t.javaClass.simpleName}: ${t.message}")
            writeError(clientOut, 502, "DRM License Request Failed")
            return
        }

        try {
            val responseHeaders = linkedMapOf(
                "Access-Control-Allow-Origin" to "*",
                "Connection" to "close"
            )
            response.header("Content-Type")?.let { responseHeaders["Content-Type"] = it }
            val length = response.body.contentLength()
            if (length >= 0) responseHeaders["Content-Length"] = length.toString()
            writeStatusAndHeaders(clientOut, response.code, response.message.ifBlank { "OK" }, responseHeaders)
            if (method != "HEAD") {
                val buffer = getTransferBuffer()
                val inStream = response.body.byteStream()
                var firstBodyWritten = false
                val bufferedClientOut = clientOut as? BufferedClientOutputStream
                if (length >= 0) {
                    var remaining = length
                    while (remaining > 0L) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = inStream.read(buffer, 0, toRead)
                        if (read == -1) break
                        clientOut.write(buffer, 0, read)
                        val bufferedBytes = bufferedClientOut?.bufferedBytes ?: 0
                        if (!firstBodyWritten || bufferedBytes >= BATCH_FLUSH_THRESHOLD || inStream.available() == 0) {
                            clientOut.flush()
                            firstBodyWritten = true
                        }
                        remaining -= read
                    }
                    clientOut.flush()
                } else {
                    while (true) {
                        val read = inStream.read(buffer, 0, buffer.size)
                        if (read == -1) break
                        writeChunk(clientOut, buffer, read)
                        val bufferedBytes = bufferedClientOut?.bufferedBytes ?: 0
                        if (!firstBodyWritten || bufferedBytes >= BATCH_FLUSH_THRESHOLD || inStream.available() == 0) {
                            clientOut.flush()
                            firstBodyWritten = true
                        }
                    }
                    clientOut.write(ZERO_CHUNK)
                    clientOut.flush()
                }
            }
        } finally {
            response.close()
        }
    }

    private val TAG_URI_ATTRIBUTE_REGEX = Regex(
        """(^|,)(\s*)URI(\s*)=(\s*)("[^"]*"|[^,]*)""",
        RegexOption.IGNORE_CASE
    )

    private val URI_BEARING_TAGS = setOf(
        "#EXT-X-MEDIA",
        "#EXT-X-KEY",
        "#EXT-X-SESSION-KEY",
        "#EXT-X-MAP",
        "#EXT-X-I-FRAME-STREAM-INF",
        "#EXT-X-SESSION-DATA",
        "#EXT-X-CONTENT-STEERING",
        "#EXT-X-PART",
        "#EXT-X-PRELOAD-HINT",
        "#EXT-X-RENDITION-REPORT"
    )

    fun isMasterPlaylist(playlistText: String): Boolean {
        return playlistText.contains("#EXT-X-STREAM-INF") ||
                playlistText.contains("#EXT-X-I-FRAME-STREAM-INF")
    }

    /**
     * Rewrites HLS playlist contents (both Master Playlists and Media Playlists):
     * - Preserves all tags (#EXTINF, #EXT-X-STREAM-INF, #EXT-X-ENDLIST, #EXT-X-KEY, #EXT-X-MAP, etc.).
     * - Rewrites variant URIs, media segments, and sub-renditions to proxy routes.
     * - Only rewrites URI attributes on known URI-bearing tags.
     */
    fun rewriteHlsPlaylist(
        playlistText: String,
        playlistUrl: String,
        session: ProxySession,
        proxyBaseUrl: String,
        parentHeaders: Map<String, String>? = null
    ): String {
        return if (isMasterPlaylist(playlistText)) {
            rewriteMasterPlaylist(playlistText, playlistUrl, session, proxyBaseUrl, parentHeaders)
        } else {
            rewriteMediaPlaylist(playlistText, playlistUrl, session, proxyBaseUrl, parentHeaders)
        }
    }

    fun rewriteMasterPlaylist(
        playlistText: String,
        playlistUrl: String,
        session: ProxySession,
        proxyBaseUrl: String,
        parentHeaders: Map<String, String>? = null
    ): String {
        val effectiveParentHeaders = parentHeaders
            ?: session.getResourceHeaders(playlistUrl)
            ?: session.effectiveHeaders
        val rewritten = StringBuilder()
        val lines = playlistText.lines()
        var expectVariantUri = false

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                rewritten.append(line).append("\n")
                continue
            }

            if (trimmed.startsWith("#")) {
                val modifiedLine = rewriteTagUris(
                    line,
                    playlistUrl,
                    session,
                    proxyBaseUrl,
                    effectiveParentHeaders
                )
                rewritten.append(modifiedLine).append("\n")
                if (trimmed.startsWith("#EXT-X-STREAM-INF")) {
                    expectVariantUri = true
                }
            } else if (expectVariantUri) {
                val resolved = resolveUrl(playlistUrl, trimmed)
                val resId = session.registerResource(
                    resolved,
                    effectiveParentHeaders,
                    ResourceType.MEDIA_PLAYLIST,
                    playlistUrl
                )
                rewritten.append("$proxyBaseUrl/session/${session.sessionId}/res/$resId.m3u8")
                    .append("\n")
                expectVariantUri = false
            } else {
                // Unknown standalone line in a master playlist: preserve it rather
                // than inventing a resource mapping.
                rewritten.append(line).append("\n")
            }
        }
        return rewritten.toString()
    }

    fun rewriteMediaPlaylist(
        playlistText: String,
        playlistUrl: String,
        session: ProxySession,
        proxyBaseUrl: String,
        parentHeaders: Map<String, String>? = null
    ): String {
        val effectiveParentHeaders = parentHeaders
            ?: session.getResourceHeaders(playlistUrl)
            ?: session.effectiveHeaders
        val rewritten = StringBuilder()
        val lines = playlistText.lines()
        var pendingByteRange: String? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                rewritten.append(line).append("\n")
                continue
            }

            if (trimmed.startsWith("#")) {
                val modifiedLine = rewriteTagUris(
                    line,
                    playlistUrl,
                    session,
                    proxyBaseUrl,
                    effectiveParentHeaders
                )
                rewritten.append(modifiedLine).append("\n")
                if (trimmed.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true)) {
                    pendingByteRange = trimmed.substringAfter(":", "").trim().ifBlank { null }
                }
            } else {
                val resolved = resolveUrl(playlistUrl, trimmed)
                val resourceType = inferSegmentType(resolved)
                val resId = session.registerResource(
                    resolved,
                    effectiveParentHeaders,
                    resourceType,
                    playlistUrl,
                    pendingByteRange
                )
                pendingByteRange = null
                val extension = extensionForType(resourceType).ifEmpty {
                    detectExtension(resolved).ifEmpty { ".ts" }
                }
                rewritten.append("$proxyBaseUrl/session/${session.sessionId}/res/$resId$extension")
                    .append("\n")
            }
        }
        return rewritten.toString()
    }

    private fun rewriteTagUris(
        line: String,
        playlistUrl: String,
        session: ProxySession,
        proxyBaseUrl: String,
        parentHeaders: Map<String, String>? = null
    ): String {
        val colonIndex = line.indexOf(':')
        if (colonIndex <= 0) return line
        val tagName = line.substring(0, colonIndex).trim()
        if (URI_BEARING_TAGS.none { it.equals(tagName, ignoreCase = true) }) {
            return line
        }

        val prefix = line.substring(0, colonIndex + 1)
        val attributes = line.substring(colonIndex + 1)
        val resourceType = resourceTypeForTag(tagName)
        val byteRange = findAttributeValue(attributes, "BYTERANGE")
        val rewrittenAttributes = TAG_URI_ATTRIBUTE_REGEX.replace(attributes) { match ->
            val group1 = match.groupValues[1]
            val group2 = match.groupValues[2]
            val group3 = match.groupValues[3]
            val group4 = match.groupValues[4]
            val rawValue = match.groupValues[5].trim()
            val uri = rawValue.removeSurrounding("\"")
            if (uri.isBlank()) {
                match.value
            } else {
                val resolved = resolveUrl(playlistUrl, uri)
                val type = if (resourceType == ResourceType.OTHER) {
                    inferResourceType(resolved)
                } else {
                    resourceType
                }
                val resId = session.registerResource(
                    resolved,
                    parentHeaders,
                    type,
                    playlistUrl,
                    byteRange
                )
                var extension = extensionForType(type)
                if (extension.isEmpty()) {
                    extension = detectExtension(resolved)
                }
                val proxyUri = "$proxyBaseUrl/session/${session.sessionId}/res/$resId$extension"
                "$group1${group2}URI${group3}=${group4}\"$proxyUri\""
            }
        }
        return prefix + rewrittenAttributes
    }

    /**
     * Finds an attribute without splitting on commas inside quoted values.
     */
    private fun findAttributeValue(attributes: String, name: String): String? {
        var start = 0
        var inQuotes = false
        var i = 0
        while (i <= attributes.length) {
            val atEnd = i == attributes.length
            val c = if (atEnd) ',' else attributes[i]
            if (c == '"') {
                inQuotes = !inQuotes
            } else if (c == ',' && !inQuotes) {
                val span = attributes.substring(start, i).trim()
                val eq = span.indexOf('=')
                if (eq > 0 && span.substring(0, eq).trim().equals(name, ignoreCase = true)) {
                    return span.substring(eq + 1).trim().removeSurrounding("\"")
                }
                start = i + 1
            }
            i++
        }
        return null
    }

    private fun resourceTypeForTag(tagName: String): ResourceType {
        return when {
            tagName.equals("#EXT-X-KEY", ignoreCase = true) ||
                    tagName.equals("#EXT-X-SESSION-KEY", ignoreCase = true) -> ResourceType.KEY
            tagName.equals("#EXT-X-MAP", ignoreCase = true) -> ResourceType.INIT_MAP
            tagName.equals("#EXT-X-MEDIA", ignoreCase = true) ||
                    tagName.equals("#EXT-X-I-FRAME-STREAM-INF", ignoreCase = true) ||
                    tagName.equals("#EXT-X-RENDITION-REPORT", ignoreCase = true) -> ResourceType.MEDIA_PLAYLIST
            tagName.equals("#EXT-X-PART", ignoreCase = true) ||
                    tagName.equals("#EXT-X-PRELOAD-HINT", ignoreCase = true) -> ResourceType.SEGMENT
            else -> ResourceType.OTHER
        }
    }

    private fun inferSegmentType(url: String): ResourceType {
        val clean = url.substringBefore("?").substringBefore("#")
        return when {
            clean.endsWith(".key", ignoreCase = true) -> ResourceType.KEY
            clean.endsWith(".vtt", ignoreCase = true) ||
                    clean.endsWith(".webvtt", ignoreCase = true) ||
                    clean.endsWith(".srt", ignoreCase = true) -> ResourceType.SUBTITLE
            else -> ResourceType.SEGMENT
        }
    }

    private fun extensionForType(type: ResourceType): String {
        return when (type) {
            ResourceType.MASTER_PLAYLIST, ResourceType.MEDIA_PLAYLIST -> ".m3u8"
            ResourceType.KEY -> ".key"
            ResourceType.INIT_MAP -> ".mp4"
            ResourceType.SUBTITLE -> ".vtt"
            ResourceType.SEGMENT, ResourceType.OTHER -> ""
        }
    }

    private fun detectExtension(url: String): String {
        val cleanUrl = url.substringBefore("?").substringBefore("#")
        val ext = cleanUrl.substringAfterLast(".", "")
        return when (ext.lowercase()) {
            "m3u8" -> ".m3u8"
            "ts" -> ".ts"
            "m4s" -> ".m4s"
            "mp4" -> ".mp4"
            "aac" -> ".aac"
            "m4a" -> ".m4a"
            "vtt" -> ".vtt"
            "webvtt" -> ".vtt"
            "key" -> ".key"
            else -> if (cleanUrl.contains(".m3u8", ignoreCase = true)) ".m3u8" else ""
        }
    }

    /**
     * Safely merges query parameters from parentUrl into childUrl:
     * - Preserves all parameters already present in childUrl without overriding them.
     * - Appends authentication and token parameters from parentUrl if missing on childUrl.
     */
    fun mergeQueryStrings(parentUrl: String, childUrl: String): String {
        if (!parentUrl.contains("?")) return childUrl
        val parentQuery = parentUrl.substringAfter("?").substringBefore("#").trim()
        if (parentQuery.isEmpty()) return childUrl

        val (childBase, childQuery) = if (childUrl.contains("?")) {
            childUrl.substringBefore("?") to childUrl.substringAfter("?").substringBefore("#")
        } else {
            childUrl.substringBefore("#") to ""
        }
        val childHash = if (childUrl.contains("#")) "#" + childUrl.substringAfter("#") else ""

        val childParams = LinkedHashMap<String, String>()
        if (childQuery.isNotEmpty()) {
            for (part in childQuery.split("&")) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                if (eq != -1) {
                    childParams[part.substring(0, eq).trim()] = part.substring(eq + 1)
                } else {
                    childParams[part.trim()] = ""
                }
            }
        }

        val parentParams = parentQuery.split("&").filter { it.isNotEmpty() }
        for (param in parentParams) {
            val eq = param.indexOf('=')
            val key = (if (eq != -1) param.substring(0, eq) else param).trim()
            val value = if (eq != -1) param.substring(eq + 1) else ""
            if (!childParams.containsKey(key)) {
                childParams[key] = value
            }
        }

        val mergedQuery = childParams.entries.joinToString("&") { (k, v) ->
            if (v.isEmpty()) k else "$k=$v"
        }
        return "$childBase?$mergedQuery$childHash"
    }

    /**
     * Resolves child resource URLs against the final playlist URL.
     * Preserves existing query strings on child URLs. Parent authentication query
     * parameters are only inherited when the child has no query at all, the host
     * is the same site, and the parent query clearly carries a signed credential.
     */
    fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        val trimmed = relativeUrl.trim()
        val resolved = when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) -> trimmed

            trimmed.startsWith("//") -> {
                val scheme = if (baseUrl.startsWith("http://", ignoreCase = true)) "http:" else "https:"
                scheme + trimmed
            }

            else -> {
                try {
                    URI.create(baseUrl).resolve(trimmed).toString()
                } catch (_: Throwable) {
                    try {
                        java.net.URL(java.net.URL(baseUrl), trimmed).toString()
                    } catch (_: Throwable) {
                        trimmed
                    }
                }
            }
        }

        // Only inherit parent auth query parameters if child does NOT have its own
        // query string. This is an explicit compatibility rule for providers that
        // sign only the playlist URL.
        if (!trimmed.contains("?") && baseUrl.contains("?")) {
            val isSafeHost = isSameHostOrSubdomain(baseUrl, resolved)
            if (isSafeHost) {
                val baseQuery = baseUrl.substringAfter("?").substringBefore("#").trim()
                val isAuthQuery = baseQuery.contains("token=", ignoreCase = true) ||
                        baseQuery.contains("auth=", ignoreCase = true) ||
                        baseQuery.contains("sig=", ignoreCase = true) ||
                        baseQuery.contains("sign=", ignoreCase = true) ||
                        baseQuery.contains("key=", ignoreCase = true) ||
                        baseQuery.contains("expires=", ignoreCase = true) ||
                        baseQuery.contains("exp=", ignoreCase = true) ||
                        baseQuery.contains("hdnts=", ignoreCase = true) ||
                        baseQuery.contains("hash=", ignoreCase = true)
                if (isAuthQuery && baseQuery.isNotEmpty()) {
                    return if (resolved.contains("#")) {
                        val basePart = resolved.substringBefore("#")
                        val hashPart = resolved.substringAfter("#")
                        "$basePart?$baseQuery#$hashPart"
                    } else {
                        "$resolved?$baseQuery"
                    }
                }
            }
        }

        return resolved
    }

    fun isSameHostOrSubdomain(base: String, target: String): Boolean {
        return try {
            val baseHost = URI.create(base).host?.lowercase() ?: return false
            val targetHost = URI.create(target).host?.lowercase() ?: return false
            if (baseHost == targetHost) return true
            if (targetHost.endsWith(".$baseHost") || baseHost.endsWith(".$targetHost")) return true
            val baseParts = baseHost.split(".")
            val targetParts = targetHost.split(".")
            if (baseParts.size >= 2 && targetParts.size >= 2) {
                // If it's an IP address, only exact match
                if (baseParts.all { it.toIntOrNull() != null } ||
                    targetParts.all { it.toIntOrNull() != null }
                ) {
                    return false
                }
                val secondLevelTLDs = setOf("co", "com", "org", "net", "edu", "gov", "ac", "mil")
                val baseIsTwoPartTLD =
                    baseParts.size >= 3 && secondLevelTLDs.contains(baseParts[baseParts.size - 2])
                val targetIsTwoPartTLD =
                    targetParts.size >= 3 && secondLevelTLDs.contains(targetParts[targetParts.size - 2])

                val baseDomain = if (baseIsTwoPartTLD) {
                    baseParts.takeLast(3).joinToString(".")
                } else {
                    baseParts.takeLast(2).joinToString(".")
                }
                val targetDomain = if (targetIsTwoPartTLD) {
                    targetParts.takeLast(3).joinToString(".")
                } else {
                    targetParts.takeLast(2).joinToString(".")
                }
                baseDomain.equals(targetDomain, ignoreCase = true)
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun isHopByHopHeader(header: String): Boolean {
        val lower = header.lowercase()
        return lower == "host" || lower == "connection" || lower == "transfer-encoding" ||
                lower == "keep-alive" || lower == "proxy-authenticate" ||
                lower == "proxy-authorization" || lower == "upgrade" || lower == "te" ||
                lower == "accept-encoding" || lower == "range" || lower == "content-length"
    }

    private fun readHttpRequest(input: InputStream): ParsedHttpRequest? {
        val requestLine = readHttpLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null

        val headers = mutableMapOf<String, String>()
        var totalHeaderBytes = requestLine.length
        while (true) {
            val line = readHttpLine(input) ?: break
            if (line.isEmpty()) break
            totalHeaderBytes += line.length
            if (totalHeaderBytes > 64 * 1024) {
                throw IOException("HTTP request headers too large")
            }
            val split = line.split(":", limit = 2)
            if (split.size == 2) {
                headers[split[0].trim().lowercase()] = split[1].trim()
            }
        }
        return ParsedHttpRequest(parts[0].uppercase(), parts[1], headers)
    }

    private fun readHttpLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (buffer.size() < 16 * 1024) {
            val b = input.read()
            if (b == -1) {
                return if (buffer.size() == 0) null else buffer.toString(Charsets.US_ASCII.name())
            }
            if (b == '\n'.code) {
                val bytes = buffer.toByteArray()
                val length = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                    bytes.size - 1
                } else {
                    bytes.size
                }
                return String(bytes, 0, length, Charsets.US_ASCII)
            }
            buffer.write(b)
        }
        throw IOException("HTTP header line too long")
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(result, offset, length - offset)
            if (read == -1) break
            offset += read
        }
        return if (offset == length) result else result.copyOf(offset)
    }

    private fun writeChunk(out: OutputStream, buffer: ByteArray, length: Int) {
        val hex = Integer.toHexString(length).toByteArray(Charsets.US_ASCII)
        out.write(hex)
        out.write(CRLF)
        out.write(buffer, 0, length)
        out.write(CRLF)
    }

    private fun writeStatusAndHeaders(
        out: OutputStream,
        code: Int,
        text: String,
        headers: Map<String, String>
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(" ").append(text).append("\r\n")
        for ((k, v) in headers) {
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun writeResponse(
        out: OutputStream,
        code: Int,
        text: String,
        headers: Map<String, String>,
        body: ByteArray
    ) {
        writeStatusAndHeaders(out, code, text, headers)
        out.write(body)
        out.flush()
    }

    private fun writeNotFound(out: OutputStream, msg: String) {
        writeError(out, 404, "Not Found: $msg")
    }

    private fun writeError(out: OutputStream, code: Int, msg: String) {
        val body = ("Error $code: $msg").toByteArray(Charsets.UTF_8)
        val headers = mapOf(
            "Content-Type" to "text/plain; charset=utf-8",
            "Content-Length" to body.size.toString(),
            "Connection" to "close"
        )
        try {
            writeResponse(out, code, "Error", headers, body)
        } catch (_: Throwable) {
        }
    }

    private fun writeCorsOk(out: OutputStream) {
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, HEAD, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "*",
            "Content-Length" to "0",
            "Connection" to "close"
        )
        writeStatusAndHeaders(out, 200, "OK", headers)
        out.flush()
    }

    internal fun sanitizeUrl(url: String): String {
        return try {
            val uri = URI.create(url)
            val host = uri.host ?: "unknown"
            val path = uri.path ?: ""
            val ext = path.substringAfterLast(".", "")
            val shortPath = if (path.length > 35) {
                path.take(20) + "..." + (if (ext.isNotEmpty()) ".$ext" else "")
            } else {
                path
            }
            "${uri.scheme}://$host$shortPath"
        } catch (_: Throwable) {
            url.substringBefore("?").take(35)
        }
    }

    /**
     * Test-only reset. Keeps the worker pool reusable while clearing sessions
     * and the listening socket.
     */
    @Synchronized
    internal fun resetForTest() {
        sessions.clear()
        stopListening()
        totalCompletedRequests.set(0)
        totalBytesTransferred.set(0)
        activeTransfers.set(0)
        totalTransferFailures.set(0)
        hlsInspectionCount.set(0)
        verboseDiagnostics = false
    }

    @Synchronized
    override fun close() {
        isRunning.set(false)
        sessions.clear()
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        cleanupExecutor?.shutdownNow()
        cleanupExecutor = null
        Log.d(TAG, "Server closed and all sessions cleared")
    }
}
