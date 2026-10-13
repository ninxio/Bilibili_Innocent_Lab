package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 一路 DASH 流（视频或音频的一个 representation）。同一次 playurl 回包的所有流共用一份 [bans]，
 * 对应原项目"换视频时重置封禁表"。预取缓存挂在流上，跨越播放器的多次 Range 请求。
 */
internal class ThreadRipperStreamSource(
    val primary: String,
    val backups: List<String>,
    val audio: Boolean,
    val bans: ThreadRipperBanList,
    hostSpeeds: ThreadRipperHostSpeeds? = null,
    /** 播放响应里下发的文件大小；0 = 未知。已知时不必先探测就能立刻回响应头。 */
    val declaredSize: Long = 0,
    settings: () -> ThreadRipperSettings,
) {
    val resolver = ThreadRipperCdnResolver(primary, backups, { settings().mode }, bans, { settings().customHosts }, hostSpeeds = hostSpeeds)
    val fileName: String = primary.substringBefore('?').substringAfterLast('/').ifEmpty { "media.m4s" }

    @Volatile
    var prefetcher: ThreadRipperPrefetcher? = null
}

/**
 * 只监听 127.0.0.1 的 Range 代理：宿主播放器向它要数据，它用 [ThreadRipperDownloader] 多连接、多节点并发下载，
 * 按顺序写回。对应原项目"兼容模式"——解码、缓冲与清晰度切换仍由宿主播放器负责，这里只接管下载。
 *
 * 实测宿主播放器按 1 MiB 的闭区间一段段顺序请求，所以下载不按"一次响应"组织，而由每路流的
 * [ThreadRipperPrefetcher] 在播放器读取位置之前持续预取，后续请求直接从内存交付。
 *
 * 路径带 128 位随机令牌，只有登记过的流才会被服务；上游地址全部经过 [ThreadRipperCdnPolicy.isBilibiliMediaUrl]。
 * 每个响应都 `Connection: close`。
 */
internal class ThreadRipperProxyServer(
    private val downloader: ThreadRipperDownloader,
    private val log: (String) -> Unit = {},
    /** 每次响应结束输出一行统计：只有字节数、耗时、节点主机名与计数，不含签名地址。 */
    private val stats: ((String) -> Unit)? = null,
    private val memoryCapBytes: Long = DEFAULT_MEMORY_CAP,
) {
    private val sources = object : LinkedHashMap<String, ThreadRipperStreamSource>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ThreadRipperStreamSource>?): Boolean {
            if (size <= MAX_SOURCES) return false
            eldest?.value?.prefetcher?.close()
            return true
        }
    }
    private val random = SecureRandom()
    private val openStreams = ConcurrentHashMap.newKeySet<ThreadRipperCancel>()
    private val prefetchers = ConcurrentHashMap.newKeySet<ThreadRipperPrefetcher>()

    /** 全部预取缓存占用的字节数；超过上限只下播放器正在等的块。 */
    val memory = AtomicLong()
    private val janitor = Executors.newSingleThreadScheduledExecutor(ThreadRipperDownloader.daemonThreads("BTR-janitor"))

    @Volatile
    private var server: ServerSocket? = null

    val port: Int get() = server?.localPort ?: -1

    /** 正在服务的流数量，供诊断显示。 */
    val activeStreams: Int get() = openStreams.size

    @Synchronized
    fun start(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = socket
        Thread({ acceptLoop(socket) }, "BTR-proxy").apply { isDaemon = true }.start()
        // 播放器离开一路流（换清晰度、退出视频）后，它的预取缓存在空闲一段时间后释放。
        janitor.scheduleWithFixedDelay({
            prefetchers.filter { it.idleMs() > IDLE_RELEASE_MS }.forEach {
                it.close()
                prefetchers -= it
            }
        }, 5, 5, TimeUnit.SECONDS)
        log("代理已监听 127.0.0.1:${socket.localPort}")
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        openStreams.forEach { it.cancel(ThreadRipperCanceled("代理已停止")) }
        prefetchers.forEach { it.close() }
        prefetchers.clear()
    }

    /** 登记一路流，返回给宿主播放器用的本地地址。 */
    fun register(source: ThreadRipperStreamSource): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        synchronized(sources) { sources[token] = source }
        return "http://127.0.0.1:$port/btr/$token/${source.fileName}"
    }

    private fun lookup(token: String): ThreadRipperStreamSource? = synchronized(sources) { sources[token] }

    private fun prefetcherFor(source: ThreadRipperStreamSource): ThreadRipperPrefetcher = synchronized(source) {
        source.prefetcher?.takeUnless { it.isClosed } ?: ThreadRipperPrefetcher(downloader, source, memory, memoryCapBytes)
            .also {
                source.prefetcher = it
                prefetchers += it
            }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            downloader.submit { handle(client) }
        }
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): Request? {
        val lines = ArrayList<String>()
        val line = StringBuilder()
        var total = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) return null
            if (++total > MAX_HEADER_BYTES) return null
            if (byte == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                line.setLength(0)
                if (text.isEmpty()) break
                lines += text
            } else {
                line.append(byte.toChar())
            }
        }
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 2) return null
        val headers = lines.drop(1).mapNotNull { header ->
            val colon = header.indexOf(':')
            if (colon <= 0) null else header.substring(0, colon).trim().lowercase() to header.substring(colon + 1).trim()
        }.toMap()
        return Request(parts[0].uppercase(), parts[1], headers)
    }

    private fun handle(client: Socket) {
        val cancel = ThreadRipperCancel()
        openStreams += cancel
        try {
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()
            val request = readRequest(input) ?: return
            if (request.method != "GET" && request.method != "HEAD") return respondError(output, 405, "Method Not Allowed")
            val token = Regex("""^/btr/([0-9a-f]{32})/""").find(request.path)?.groupValues?.get(1)
            val source = token?.let(::lookup) ?: return respondError(output, 404, "Not Found")
            // 播放器断开（拖动、换清晰度、退出）时停止这次响应；预取缓存保留，供它回来时直接用。
            downloader.submit {
                runCatching {
                    @Suppress("ControlFlowWithEmptyBody")
                    while (input.read() >= 0) {
                    }
                }
                cancel.cancel(ThreadRipperCanceled("播放器已断开"))
            }
            serve(source, request, output, cancel)
        } catch (error: IOException) {
            if (!cancel.cancelled) log("代理中断：${error.message}")
        } catch (error: Throwable) {
            log("代理异常：$error")
        } finally {
            cancel.cancel()
            cancel.detach()
            openStreams -= cancel
            runCatching { client.close() }
        }
    }

    private fun upstreamHeaders(request: Request): Map<String, String> = buildMap {
        put("User-Agent", request.headers["user-agent"] ?: DEFAULT_USER_AGENT)
        request.headers["referer"]?.let { put("Referer", it) }
    }

    private fun serve(source: ThreadRipperStreamSource, request: Request, output: OutputStream, cancel: ThreadRipperCancel) {
        val requested = request.headers["range"]?.let(ThreadRipperCdnPolicy::parseRangeHeader)
        if (request.headers.containsKey("range") && requested == null) return respondError(output, 416, "Range Not Satisfiable")
        val start = requested?.start ?: 0L
        val prefetcher = prefetcherFor(source)
        prefetcher.headers = upstreamHeaders(request)
        val report = ThreadRipperPrefetcher.Report(System.nanoTime(), downloader.counters.snapshot())
        val total = prefetcher.total ?: try {
            prefetcher.probe(start, cancel, report)
        } catch (error: IOException) {
            if (cancel.cancelled) return
            report.end = "probe-failed"
            emitStats(source, requested, null, report)
            log("首块失败：${error.message}")
            // 起播前失败就回 502，宿主播放器会改用 backupUrl 里的原始地址。
            return respondError(output, 502, "Bad Gateway")
        }
        if (start >= total) return respondError(output, 416, "Range Not Satisfiable", total)
        val end = minOf(requested?.end ?: (total - 1), total - 1)
        val partial = requested != null
        val status = if (partial) "206 Partial Content" else "200 OK"
        val responseHeaders = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: video/mp4\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: ").append(end - start + 1).append("\r\n")
            if (partial) append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(total).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(responseHeaders.toByteArray(Charsets.ISO_8859_1))
        if (request.method == "HEAD") return output.flush()
        try {
            prefetcher.read(start, end, cancel, report) { bytes, offset, length -> output.write(bytes, offset, length) }
            output.flush()
        } catch (error: IOException) {
            report.end = if (cancel.cancelled) "closed" else "error"
            throw error
        } finally {
            emitStats(source, requested, total, report)
        }
    }

    private fun emitStats(source: ThreadRipperStreamSource, requested: ThreadRipperCdnPolicy.RequestedRange?, total: Long?, report: ThreadRipperPrefetcher.Report) {
        val sink = stats ?: return
        runCatching {
            val seconds = (System.nanoTime() - report.startedNs) / 1e9
            val now = downloader.counters.snapshot()
            val d = LongArray(now.size) { now[it] - report.counters[it] }
            val hosts = report.hosts.entries.sortedByDescending { it.value }
                .joinToString(",") { "${it.key.substringBefore('.')}:${it.value}" }
            val prefetcher = source.prefetcher
            sink(buildString {
                append("[BTR] ").append(if (source.audio) "audio" else "video")
                append(" req=").append(requested?.start ?: 0).append('-').append(requested?.end ?: "")
                if (total != null) append(" total=").append("%.1fMB".format(total / 1048576.0))
                append(" sent=").append("%.2fMB".format(report.written / 1048576.0))
                append(" in=").append("%.2fs".format(seconds))
                append(" cached=").append(report.cachedBlocks).append('/').append(report.blocks)
                append(" wait=").append(report.waitedMs).append("ms")
                if (report.probeMs > 0) append(" probe=").append(report.probeMs).append("ms")
                append(" ahead=").append("%.1fMB".format((prefetcher?.aheadBytes() ?: 0) / 1048576.0))
                append(" mem=").append("%.0fMB".format(memory.get() / 1048576.0))
                append(" threads=").append(downloader.effective().concurrency)
                append(" att=").append(d[0]).append(" hedge=").append(d[1]).append(" fail=").append(d[2])
                append(" ttfb=").append(if (d[4] > 0) d[3] / d[4] else 0).append("ms")
                append(" hosts=").append(hosts)
                append(" end=").append(report.end)
            })
            val failures = downloader.counters.drainFailures()
            if (failures.isNotEmpty()) {
                sink("[BTR] failures " + failures.entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key}×${it.value}" })
            }
        }
    }

    private fun respondError(output: OutputStream, code: Int, text: String, total: Long? = null) {
        val body = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(text).append("\r\n")
            if (total != null) append("Content-Range: bytes */").append(total).append("\r\n")
            append("Content-Length: 0\r\nConnection: close\r\n\r\n")
        }
        runCatching {
            output.write(body.toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }
    }

    companion object {
        const val DEFAULT_USER_AGENT = "Bilibili Freedoooooom/MarkII"
        /** 所有流的预取缓存合计上限：在宿主进程的 Java 堆里，不能无限制。 */
        const val DEFAULT_MEMORY_CAP = 96L * 1024 * 1024
        private const val MAX_SOURCES = 256
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val IDLE_RELEASE_MS = 20_000L
    }
}

/**
 * 一路流的顺序预取缓存，对应原项目全接管模式下的"缓冲区前方保持 N 秒"。
 *
 * 播放器每次请求只要 1 MiB，但它会一直顺序读下去，所以这里在读取位置之前持续保持一段预取：
 * 长度按播放器实测的消费速率换算成约 [AHEAD_SECONDS] 秒，并受单路与全局内存上限约束。
 * 每块走一个连接，多块并行；首字节来自哪个节点由 [ThreadRipperDownloader.assignPrimaries] 按测速加权决定。
 * 只有播放器正在等的块（或紧挨着读取位置的块）才允许发 hedge 副本，远处的块只在完全停住时救援——
 * 网页端用播放截止时间做同样的判断。
 *
 * 拖动到缓存之外时开一个新会话：先下两小块尽快起播，再逐步放大到 [BLOCK_BYTES]。
 */
internal class ThreadRipperPrefetcher(
    private val downloader: ThreadRipperDownloader,
    private val source: ThreadRipperStreamSource,
    private val memory: AtomicLong,
    private val memoryCap: Long,
) {
    /** 一次响应的统计；预取器与代理共同填写，异常结束时也保留已有数据。 */
    class Report(val startedNs: Long, val counters: LongArray) {
        var written = 0L
        var blocks = 0
        var cachedBlocks = 0
        var waitedMs = 0L
        var probeMs = 0L
        var end = "done"
        val hosts = LinkedHashMap<String, Int>()

        fun count(host: String) {
            if (host.isNotEmpty()) hosts[host] = (hosts[host] ?: 0) + 1
        }
    }

    private class Block(val start: Long, val end: Long) {
        @Volatile
        var data: ByteArray? = null

        @Volatile
        var error: IOException? = null

        /** 播放器正在等它。 */
        @Volatile
        var urgent = false

        @Volatile
        var url = ""
        val cancel = ThreadRipperCancel()
        val length: Long get() = end - start + 1
        val done: Boolean get() = data != null || error != null

        /** 边下边收：各副本到达的区间合并后，从块头起连续可用的字节数。 */
        private var buffer: ByteArray? = null
        private var ranges = ArrayList<LongArray>()
        private var filled = 0L

        @Synchronized
        fun accept(position: Long, bytes: ByteArray) {
            if (data != null) return
            val offset = position - start
            if (offset < 0 || offset + bytes.size > length) return
            val target = buffer ?: ByteArray(length.toInt()).also { buffer = it }
            System.arraycopy(bytes, 0, target, offset.toInt(), bytes.size)
            ranges.add(longArrayOf(offset, offset + bytes.size))
            ranges.sortBy { it[0] }
            val merged = ArrayList<LongArray>()
            for (range in ranges) {
                val last = merged.lastOrNull()
                if (last != null && range[0] <= last[1]) last[1] = maxOf(last[1], range[1]) else merged += range
            }
            ranges = merged
            filled = if (merged.firstOrNull()?.get(0) == 0L) merged[0][1] else 0L
        }

        /** 当前可交付的字节与其中连续可用的长度。 */
        @Synchronized
        fun readable(): Pair<ByteArray?, Long> = data?.let { it to length } ?: (buffer to filled)

        @Synchronized
        fun complete(bytes: ByteArray) {
            data = bytes
            buffer = null
            ranges = ArrayList()
        }
    }

    private val lock = Any()
    private val blocks = TreeMap<Long, Block>()

    /**
     * 文件总长。播放响应下发了大小就直接用：宿主播放器读响应头约 3.5 秒超时，
     * 新流先探测再回头曾让它放弃代理改走直连。节点返回的总长与之不符时作废，退回探测。
     */
    @Volatile
    var total: Long? = source.declaredSize.takeIf { it > 0 }
        private set

    @Volatile
    var headers: Map<String, String> = emptyMap()

    /** 播放器下一次大概率要读的位置（最近一次读取停下的地方）。 */
    private var cursor = 0L

    /**
     * 正在读取的各个位置。宿主播放器会对同一路流同时开两个请求（例如正常顺序读与提前读后面的分段），
     * 只用一个游标时两者会互相把对方的块挤出窗口。
     */
    private val readers = HashMap<Long, Long>()
    private var readerSequence = 0L
    private var sessionStart = 0L
    private var candidates: List<String> = emptyList()
    private val primaries = ArrayDeque<String>()
    private val served = ArrayDeque<LongArray>()

    @Volatile
    private var lastReadAt = System.nanoTime()

    @Volatile
    var isClosed = false
        private set

    fun idleMs(): Long = (System.nanoTime() - lastReadAt) / 1_000_000

    /** 文件总长未知时的首块探测：只做一次；探测到的头部留在缓存里直接交付。 */
    fun probe(start: Long, cancel: ThreadRipperCancel, report: Report): Long {
        total?.let { return it }
        val config = downloader.effective()
        if (config.autoConcurrency) downloader.autoConcurrency.newSession()
        val headLength = maxOf(64L * 1024, config.minChunkBytes)
        val begin = System.nanoTime()
        val head = downloader.probe(source.resolver, start, start + headLength - 1, cancel, headers)
        report.probeMs = (System.nanoTime() - begin) / 1_000_000
        val size = head.total ?: throw ThreadRipperTransferError("CDN 未返回文件总长")
        synchronized(lock) {
            if (total == null) {
                total = size
                val block = Block(start, start + head.bytes.size - 1).also {
                    it.data = head.bytes
                    it.url = head.url
                }
                memory.addAndGet(block.length)
                blocks[block.start] = block
                cursor = start
                sessionStart = start
            }
        }
        schedule()
        return size
    }

    /** 把 [start, end] 交给 [sink]；缺的块立刻以最高优先级下载，其余按预取窗口推进。 */
    fun read(start: Long, end: Long, cancel: ThreadRipperCancel, report: Report, sink: (ByteArray, Int, Int) -> Unit) {
        lastReadAt = System.nanoTime()
        var position = start
        val reader = synchronized(lock) {
            if (blockAtLocked(position) == null && isSeekLocked(position)) newSessionLocked(position)
            cursor = position
            (++readerSequence).also { readers[it] = position }
        }
        try {
            readLoop(reader, start, end, cancel, report, sink)
        } finally {
            synchronized(lock) { readers.remove(reader) }
        }
    }

    private fun readLoop(reader: Long, start: Long, end: Long, cancel: ThreadRipperCancel, report: Report, sink: (ByteArray, Int, Int) -> Unit) {
        var position = start
        schedule()
        while (position <= end) {
            cancel.throwIfCancelled()
            if (isClosed) throw ThreadRipperCanceled("预取已释放")
            if (total == null) throw ThreadRipperTransferError("文件总长已作废，等待播放器重试")
            val block = synchronized(lock) { blockAtLocked(position) } ?: run {
                // 刚被窗口驱逐或下载失败被移除：读取位置上的块总会越过并行预算立刻下。
                schedule()
                Thread.sleep(WAIT_POLL_MS)
                continue
            }
            report.blocks += 1
            if (block.done) report.cachedBlocks += 1
            block.urgent = true
            val target = minOf(end, block.end)
            var waitStarted = 0L
            var reported = 0L
            // 块内边下边交付：宿主网络层有读取低速看门狗，长时间不给字节会被判为慢地址并切到备用地址。
            while (position <= target) {
                cancel.throwIfCancelled()
                val (bytes, available) = block.readable()
                val last = block.start + available - 1
                if (bytes != null && last >= position) {
                    if (waitStarted != 0L) {
                        report.waitedMs += (System.nanoTime() - waitStarted) / 1_000_000
                        waitStarted = 0L
                    }
                    val count = minOf(last, target) - position + 1
                    sink(bytes, (position - block.start).toInt(), count.toInt())
                    report.written += count
                    position += count
                    synchronized(lock) {
                        cursor = position
                        readers[reader] = position
                        served.addLast(longArrayOf(System.nanoTime() / 1_000_000, count))
                    }
                    lastReadAt = System.nanoTime()
                    continue
                }
                val failure = block.error
                if (block.data == null && failure != null) {
                    synchronized(lock) { if (blocks[block.start] === block) blocks.remove(block.start) }
                    // 因窗口移动被取消的块不是坏块：回到外层重新查找，读取位置上的块会立刻重下。
                    if (failure is ThreadRipperCanceled && failure !is ThreadRipperTimeout) break
                    // 真正下载失败：这次响应交给播放器自己重试。
                    throw failure
                }
                if (waitStarted == 0L) waitStarted = System.nanoTime()
                Thread.sleep(WAIT_POLL_MS)
                val waited = (System.nanoTime() - waitStarted) / 1_000_000
                if (downloader.effective().autoConcurrency && waited - reported >= 1000) {
                    reported = waited
                    downloader.autoConcurrency.starved(waited)
                }
            }
            if (waitStarted != 0L) report.waitedMs += (System.nanoTime() - waitStarted) / 1_000_000
            if (position > target) report.count(ThreadRipperCdnPolicy.hostOf(block.url))
            schedule()
        }
    }

    /** 预取窗口长度：按最近 10 秒播放器读取的速率保持约 [AHEAD_SECONDS] 秒。 */
    fun aheadBytes(): Long = synchronized(lock) { aheadBytesLocked() }

    private fun aheadBytesLocked(): Long {
        val now = System.nanoTime() / 1_000_000
        while (served.isNotEmpty() && now - served.first()[0] > RATE_WINDOW_MS) served.removeFirst()
        val cap = if (source.audio) MAX_AHEAD_AUDIO else MAX_AHEAD_VIDEO
        val floor = if (source.audio) MIN_AHEAD_AUDIO else MIN_AHEAD_VIDEO
        if (served.size < 4) return if (source.audio) MIN_AHEAD_AUDIO * 2 else MIN_AHEAD_VIDEO * 2
        val span = maxOf(1000L, now - served.first()[0])
        val rate = served.sumOf { it[1] } * 1000.0 / span
        return (rate * AHEAD_SECONDS).toLong().coerceIn(floor, cap)
    }

    private fun blockAtLocked(position: Long): Block? {
        val entry = blocks.floorEntry(position) ?: return null
        return entry.value.takeIf { position <= it.end }
    }

    /** 读取位置跳出了缓存窗口：拖动、换段或很久之后回来。窗口内的跳跃只是顺序续读。 */
    private fun isSeekLocked(position: Long): Boolean {
        val ahead = aheadBytesLocked()
        return anchorsLocked().none { position >= it - KEEP_BEHIND && position <= it + ahead }
    }

    /** 预取围绕的位置：正在读的各个读者，加上最近一次读取停下的地方（两次请求之间也要继续预取）。 */
    private fun anchorsLocked(): List<Long> = (readers.values + cursor).distinct().sorted()

    private fun distanceLocked(position: Long): Long = anchorsLocked().minOf { kotlin.math.abs(position - it) }

    private fun newSessionLocked(position: Long) {
        sessionStart = position
        cursor = position
        primaries.clear()
        if (downloader.effective().autoConcurrency) downloader.autoConcurrency.newSession()
    }

    /** 每路的并行块数，与原项目一致：音频约占 1/8，留出救援余量。 */
    private fun budget(concurrency: Int): Int {
        val rescueReserve = maxOf(1, minOf(16, (concurrency + 7) / 8))
        val mediaBudget = maxOf(1, concurrency - rescueReserve)
        val audioBudget = maxOf(1, minOf(mediaBudget, (concurrency + 7) / 8))
        return if (source.audio) audioBudget else maxOf(1, mediaBudget - audioBudget)
    }

    /** 起播时先下小块尽快交付，之后放大到 [BLOCK_BYTES]。 */
    private fun blockSizeLocked(offset: Long): Long = when {
        offset < 256L * 1024 -> 128L * 1024
        offset < 1024L * 1024 -> 256L * 1024
        else -> BLOCK_BYTES
    }

    private fun schedule() {
        val launch = ArrayList<Pair<Block, List<String>>>()
        synchronized(lock) {
            if (isClosed) return
            val size = total ?: return
            val ahead = aheadBytesLocked()
            val anchors = anchorsLocked()
            // 只有落在所有读取位置的窗口之外（读过很久，或回拖之后的远处）的块才释放。
            val stale = blocks.values.filter { block ->
                anchors.all { block.end < it - KEEP_BEHIND || block.start > it + ahead + BLOCK_BYTES }
            }
            for (block in stale) {
                blocks.remove(block.start)
                if (block.done) {
                    if (block.data != null) memory.addAndGet(-block.length)
                } else {
                    block.cancel.cancel(ThreadRipperCanceled("预取窗口已移动"))
                }
            }
            val inFlight = blocks.values.count { !it.done }
            var slots = budget(downloader.effective().concurrency) - inFlight
            // 正在读的位置优先：从每个读取位置向前找第一个空缺，按顺序补齐到窗口末尾。
            val ordered = readers.values.sorted() + anchors.filter { it !in readers.values }
            for (anchor in ordered) {
                val limit = minOf(size - 1, anchor + ahead - 1)
                var frontier = anchor
                while (frontier <= limit && (slots > 0 || frontier == anchor)) {
                    val existing = blockAtLocked(frontier)
                    if (existing != null) {
                        frontier = existing.end + 1
                        continue
                    }
                    val next = blocks.ceilingEntry(frontier)?.value
                    val sizeHere = blockSizeLocked(frontier - sessionStart)
                    val end = minOf(size - 1, frontier + sizeHere - 1, (next?.start ?: Long.MAX_VALUE) - 1)
                    val nearCursor = frontier - anchor < BLOCK_BYTES * 2
                    if (!nearCursor && memory.get() >= memoryCap) break
                    val block = Block(frontier, end)
                    blocks[block.start] = block
                    if (primaries.isEmpty()) {
                        candidates = source.resolver.rangeCandidates()
                        primaries.addAll(downloader.assignPrimaries(candidates, source.resolver, maxOf(4, slots)))
                    }
                    val primary = primaries.removeFirstOrNull()
                    launch += block to when {
                        // 会话第一块和原项目的首块探测一样，在起播候选（含 B 站下发的原始地址）里抢：
                        // 海外原始节点首字节往往只要几十毫秒，大陆节点要一秒以上。
                        block.start == sessionStart -> source.resolver.startupCandidates()
                        // 起播的第一兆同样允许原始地址：拖动后最怕的是首字节慢，而不是单连接吞吐。
                        block.start - sessionStart < 1024L * 1024 ->
                            (listOfNotNull(primary) + source.resolver.startupCandidates() + candidates).distinct()
                        primary != null -> listOf(primary) + candidates.filter { it != primary }
                        else -> candidates
                    }
                    slots -= 1
                    frontier = end + 1
                }
            }
        }
        for ((block, preferred) in launch) start(block, preferred)
    }

    private fun start(block: Block, preferred: List<String>) {
        downloader.submit {
            try {
                val distance = synchronized(lock) { distanceLocked(block.start) }
                val startup = block.start - sessionStart < 1024L * 1024
                val result = downloader.downloadPiece(
                    ThreadRipperPiece(0, block.start, block.end), source.resolver, block.cancel, headers, preferred,
                    when {
                        // 拖动后的第一块：播放器的低速看门狗最容易在这里触发，直接让最快的几个节点同时抢。
                        block.start == sessionStart -> ThreadRipperDownloader.StartupMode.PROBE
                        startup -> ThreadRipperDownloader.StartupMode.STARTUP
                        else -> ThreadRipperDownloader.StartupMode.NONE
                    },
                    priority = (if (startup) 120 else 80) - minOf(60, (distance / BLOCK_BYTES).toInt()),
                    hedgeWhen = { block.urgent || synchronized(lock) { distanceLocked(block.start) } < BLOCK_BYTES * 2 },
                    onChunk = block::accept,
                )
                val total = total
                if (result.total != null && total != null && result.total != total) {
                    // 下发的大小不可信：作废，后续请求重新探测；这一块按失败交给播放器重试。
                    synchronized(lock) { if (this.total == total) this.total = null }
                    throw ThreadRipperTransferError("CDN 返回的文件总长与播放响应不一致")
                }
                block.url = result.url
                // 计数与赋值同在锁内：窗口驱逐据 data 是否为空决定减计数还是取消下载。
                synchronized(lock) {
                    if (blocks[block.start] === block && !isClosed) memory.addAndGet(block.length)
                    block.complete(result.bytes)
                }
            } catch (error: IOException) {
                block.error = error
            } catch (error: Throwable) {
                block.error = IOException(error.message ?: error.javaClass.simpleName, error)
            } finally {
                block.cancel.detach()
            }
            schedule()
        }
    }

    /** 释放全部缓存并停止在途下载。 */
    fun close() {
        val all = synchronized(lock) {
            if (isClosed) return
            isClosed = true
            blocks.values.toList().also { blocks.clear() }
        }
        for (block in all) {
            if (block.data != null) memory.addAndGet(-block.length) else block.cancel.cancel(ThreadRipperCanceled("预取已释放"))
        }
    }

    companion object {
        const val BLOCK_BYTES = 512L * 1024
        const val AHEAD_SECONDS = 30
        private const val RATE_WINDOW_MS = 10_000L
        private const val MIN_AHEAD_VIDEO = 8L * 1024 * 1024
        private const val MAX_AHEAD_VIDEO = 48L * 1024 * 1024
        private const val MIN_AHEAD_AUDIO = 1L * 1024 * 1024
        private const val MAX_AHEAD_AUDIO = 6L * 1024 * 1024
        private const val KEEP_BEHIND = 2L * 1024 * 1024
        private const val WAIT_POLL_MS = 10L
    }
}
