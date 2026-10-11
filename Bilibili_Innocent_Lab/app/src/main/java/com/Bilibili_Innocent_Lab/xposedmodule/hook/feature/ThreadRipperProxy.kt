package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 一路 DASH 流（视频或音频的一个 representation）。同一次 playurl 回包的所有流共用一份 [bans]，
 * 对应原项目"换视频时重置封禁表"。
 */
internal class ThreadRipperStreamSource(
    val primary: String,
    val backups: List<String>,
    val audio: Boolean,
    val bans: ThreadRipperBanList,
    settings: () -> ThreadRipperSettings,
) {
    val resolver = ThreadRipperCdnResolver(primary, backups, { settings().mode }, bans, { settings().customHosts })
    val fileName: String = primary.substringBefore('?').substringAfterLast('/').ifEmpty { "media.m4s" }
}

/**
 * 只监听 127.0.0.1 的 Range 代理：宿主播放器向它要数据，它用 [ThreadRipperDownloader] 多连接、多节点并发下载，
 * 按顺序写回。对应原项目"兼容模式"——解码、缓冲与清晰度切换仍由宿主播放器负责，这里只接管下载。
 *
 * 路径带 128 位随机令牌，只有登记过的流才会被服务；上游地址全部经过 [ThreadRipperCdnPolicy.isBilibiliMediaUrl]。
 * 每个响应都 `Connection: close`，播放器拖动时断开重连即可，代理据此取消该流的全部下载。
 */
internal class ThreadRipperProxyServer(
    private val downloader: ThreadRipperDownloader,
    private val log: (String) -> Unit = {},
) {
    private val sources = object : LinkedHashMap<String, ThreadRipperStreamSource>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ThreadRipperStreamSource>?) = size > MAX_SOURCES
    }
    private val random = SecureRandom()
    private val openStreams = ConcurrentHashMap.newKeySet<ThreadRipperCancel>()

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
        log("代理已监听 127.0.0.1:${socket.localPort}")
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        openStreams.forEach { it.cancel(ThreadRipperCanceled("代理已停止")) }
    }

    /** 登记一路流，返回给宿主播放器用的本地地址。 */
    fun register(source: ThreadRipperStreamSource): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        synchronized(sources) { sources[token] = source }
        return "http://127.0.0.1:$port/btr/$token/${source.fileName}"
    }

    private fun lookup(token: String): ThreadRipperStreamSource? = synchronized(sources) { sources[token] }

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
            // 播放器断开（拖动、换清晰度、退出）时立刻停掉这一路的全部下载。
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
        val headers = upstreamHeaders(request)
        val config = downloader.effective()
        if (config.autoConcurrency) downloader.autoConcurrency.newSession()
        val resolver = source.resolver
        val headLength = maxOf(64L * 1024, config.minChunkBytes)
        val head = try {
            downloader.probe(resolver, start, start + headLength - 1, cancel, headers)
        } catch (error: IOException) {
            if (cancel.cancelled) return
            log("首块失败：${error.message}")
            // 起播前失败就回 502，宿主播放器会改用 backupUrl 里的原始地址。
            return respondError(output, 502, "Bad Gateway")
        }
        val total = head.total ?: return respondError(output, 502, "Bad Gateway")
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
        val headBytes = minOf(head.bytes.size.toLong(), end - start + 1).toInt()
        output.write(head.bytes, 0, headBytes)
        output.flush()
        if (start + headBytes > end) return
        ThreadRipperStreamWindow(downloader, source, headers, cancel, total, log).run(start + headBytes, end, output)
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
        private const val MAX_SOURCES = 256
        private const val MAX_HEADER_BYTES = 16 * 1024
    }
}

/**
 * 一次响应的滑动窗口，对应原项目 `downloadStartupMediaRange`/`downloadRange` 的流式部分。
 *
 * 网页端一次只下播放器要的一段；ffmpeg 发的是开放区间并顺序读取，所以这里按写出位置向前滚动：
 * 在途块数不超过本路预算、领先写出位置不超过 [maxAhead]，按测速加权分配首选节点，完成的块按顺序写回。
 * 视频与音频共用全局连接名额，音频只占约 1/8，与原项目一致。
 */
internal class ThreadRipperStreamWindow(
    private val downloader: ThreadRipperDownloader,
    private val source: ThreadRipperStreamSource,
    private val headers: Map<String, String>,
    private val cancel: ThreadRipperCancel,
    private val total: Long,
    private val log: (String) -> Unit,
) {
    private class Slot(val piece: ThreadRipperPiece) {
        @Volatile
        var result: ThreadRipperResult? = null

        @Volatile
        var error: IOException? = null
    }

    private val slots = ConcurrentHashMap<Int, Slot>()

    private fun budget(concurrency: Int): Int {
        val rescueReserve = maxOf(1, minOf(16, (concurrency + 7) / 8))
        val mediaBudget = maxOf(1, concurrency - rescueReserve)
        val audioBudget = maxOf(1, minOf(mediaBudget, (concurrency + 7) / 8))
        return if (source.audio) audioBudget else maxOf(1, mediaBudget - audioBudget)
    }

    fun run(from: Long, end: Long, output: OutputStream) {
        val resolver = source.resolver
        var writePos = from
        var writeIndex = 1
        var nextStart = from
        var nextIndex = 1
        var candidates: List<String> = emptyList()
        val primaries = ArrayDeque<String>()
        val local = ThreadRipperCancel(cancel)
        try {
            while (writePos <= end) {
                val config = downloader.effective()
                val budget = budget(config.concurrency)
                val chunk = downloader.adaptiveMinChunk(end - nextStart + 1, budget, maxOf(1, candidates.size))
                val maxAhead = (budget * chunk * 2).coerceIn(MIN_AHEAD_BYTES, MAX_AHEAD_BYTES)
                while (nextIndex - writeIndex < budget && nextStart <= end && nextStart - writePos < maxAhead) {
                    if (primaries.isEmpty()) {
                        candidates = resolver.rangeCandidates()
                        primaries.addAll(downloader.assignPrimaries(candidates, resolver, budget))
                    }
                    val piece = ThreadRipperPiece(nextIndex, nextStart, minOf(nextStart + chunk - 1, end))
                    val primary = primaries.removeFirstOrNull()
                    val preferred = if (primary != null) listOf(primary) + candidates.filter { it != primary } else candidates
                    val startup = nextIndex <= budget
                    val priority = (if (startup) 120 else 50) - minOf(if (startup) 30 else 20, nextIndex - writeIndex)
                    val slot = Slot(piece)
                    slots[piece.index] = slot
                    downloader.submit {
                        try {
                            slot.result = downloader.downloadPiece(
                                piece, resolver, local, headers, preferred,
                                if (startup) ThreadRipperDownloader.StartupMode.STARTUP else ThreadRipperDownloader.StartupMode.NONE,
                                priority,
                            )
                        } catch (error: IOException) {
                            slot.error = error
                        } catch (error: Throwable) {
                            slot.error = IOException(error.message ?: error.javaClass.simpleName, error)
                        }
                    }
                    nextStart = piece.end + 1
                    nextIndex += 1
                }
                val slot = slots[writeIndex] ?: throw ThreadRipperTransferError("调度错位")
                val waitStarted = nowMs()
                var reported = 0L
                while (slot.result == null && slot.error == null) {
                    local.throwIfCancelled()
                    Thread.sleep(WAIT_POLL_MS)
                    val waited = nowMs() - waitStarted
                    // 写出端在等下一块：播放器缓冲正在被吃掉，交给自动线程数判断要不要加线程。
                    if (config.autoConcurrency && waited - reported >= 1000) {
                        reported = waited
                        downloader.autoConcurrency.starved(waited)
                    }
                }
                slot.error?.let { throw it }
                val result = slot.result!!
                if (result.total != null && result.total != total) throw ThreadRipperTransferError("不同 CDN 返回的文件总长度不一致")
                slots.remove(writeIndex)
                val writeStarted = nowMs()
                output.write(result.bytes)
                // 写出被播放器顶住超过 1 秒，说明它的缓冲已经很满。
                if (config.autoConcurrency && nowMs() - writeStarted >= 1000) downloader.autoConcurrency.comfortable()
                writePos = slot.piece.end + 1
                writeIndex += 1
            }
            output.flush()
        } catch (error: IOException) {
            if (!cancel.cancelled) log("分片下载中断：${error.message}")
            throw error
        } finally {
            local.cancel(ThreadRipperCanceled("响应结束"))
            local.detach()
            slots.clear()
        }
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    companion object {
        private const val WAIT_POLL_MS = 20L
        private const val MIN_AHEAD_BYTES = 4L * 1024 * 1024
        private const val MAX_AHEAD_BYTES = 32L * 1024 * 1024
    }
}
