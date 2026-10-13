package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ThreadRipperCdnPolicy.Mode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下载调度与本地代理的行为测试，对照原项目 `shared-core-test.js` 的下载器用例，
 * 外加代理端到端：真实回环 socket，假的上游。
 */
class ThreadRipperDownloaderTest {
    private fun mediaUrl(host: String, os: String = "x") = "https://$host/upgcxcode/00/00/1/1-1-30080.m4s?deadline=1&os=$os"

    /** 上游行为：按主机名决定回复；字节内容是位置的函数，便于核对拼接顺序。 */
    private class FakeUpstream(private val total: Long, private val behave: (host: String, start: Long, end: Long) -> Reply = { _, _, _ -> Reply.Ok }) : ThreadRipperFetcher {
        sealed class Reply {
            data object Ok : Reply()
            data class Status(val code: Int) : Reply()
            /** 发完响应头后卡住，直到被取消。 */
            data object Stall : Reply()
            /** 只发 [bytes] 字节就断开。 */
            data class Partial(val bytes: Int) : Reply()
        }

        val requests = ConcurrentHashMap<String, AtomicInteger>()
        fun count(host: String) = requests[host]?.get() ?: 0

        override fun newCall(url: String, start: Long, end: Long, headers: Map<String, String>, settings: ThreadRipperSettings) =
            object : ThreadRipperFetcher.Call {
                private val cancelled = CountDownLatch(1)

                override fun execute(): ThreadRipperFetcher.Response {
                    val host = URI(url).host
                    requests.getOrPut(host) { AtomicInteger() }.incrementAndGet()
                    // 与真实 CDN 一样：起点越界回 416，终点越界截到文件末尾。
                    val end = minOf(end, total - 1)
                    val reply = if (start >= total) Reply.Status(416) else behave(host, start, end)
                    val status = if (reply is Reply.Status) reply.code else 206
                    val body: InputStream = when (reply) {
                        is Reply.Status -> ByteArrayInputStream(ByteArray(0))
                        Reply.Stall -> object : InputStream() {
                            override fun read(): Int {
                                cancelled.await(30, TimeUnit.SECONDS)
                                throw IOException("canceled")
                            }
                        }
                        is Reply.Partial -> object : InputStream() {
                            var sent = 0
                            override fun read(): Int = throw IOException()
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                if (sent >= reply.bytes) throw IOException("连接中断")
                                val count = minOf(len, reply.bytes - sent)
                                for (i in 0 until count) b[off + i] = byteAt(start + sent + i)
                                sent += count
                                return count
                            }
                        }
                        Reply.Ok -> ByteArrayInputStream(ByteArray((end - start + 1).toInt()) { byteAt(start + it) })
                    }
                    return object : ThreadRipperFetcher.Response {
                        override val status = status
                        override val contentRange = if (status == 206) "bytes $start-$end/$total" else null
                        override val body = body
                        override fun close() = cancelled.countDown()
                    }
                }

                override fun cancel() = cancelled.countDown()
            }
    }

    private fun downloader(upstream: ThreadRipperFetcher, settings: ThreadRipperSettings = ThreadRipperSettings()) =
        ThreadRipperDownloader(upstream, { settings })

    private fun range(downloader: ThreadRipperDownloader, resolver: ThreadRipperCdnResolver, start: Long, end: Long): ByteArray {
        val cancel = ThreadRipperCancel()
        val pieces = ThreadRipperCdnPolicy.splitRange(start, end, 7, 64L * 1024)
        val candidates = resolver.rangeCandidates()
        val primaries = downloader.assignPrimaries(candidates, resolver, pieces.size)
        val parts = pieces.mapIndexed { index, piece ->
            downloader.downloadPiece(ThreadRipperPiece(index, piece.start, piece.end), resolver, cancel, emptyMap(),
                listOf(primaries[index]) + candidates.filter { it != primaries[index] })
        }
        return ThreadRipperDownloader.concat(parts.map { it.bytes }, end - start + 1)
    }

    @Test fun `the downloader bans a silent node, keeps a partial one and stops using the banned node`() {
        val dead = "upos-sz-mirrorhw.bilivideo.com"
        val partial = "upos-sz-mirrorbos.bilivideo.com"
        val upstream = FakeUpstream(8 * MB) { host, _, _ ->
            when (host) {
                dead -> FakeUpstream.Reply.Status(503)
                partial -> FakeUpstream.Reply.Partial(1024)
                else -> FakeUpstream.Reply.Ok
            }
        }
        var now = 1_000_000L
        val banned = ArrayList<String>()
        val bans = ThreadRipperBanList(onBan = { host, _, _ -> synchronized(banned) { banned += host } })
        val resolver = ThreadRipperCdnResolver(mediaUrl("upos-sz-mirrorali.bilivideo.com"), emptyList(), { Mode.MAINLAND }, bans, clock = { now })
        val downloader = downloader(upstream)
        repeat(4) {
            val bytes = range(downloader, resolver, 0, MB - 1)
            assertArrayEquals(ByteArray(MB.toInt()) { byteAt(it.toLong()) }, bytes)
            now += 61_000
        }
        assertEquals(listOf(dead), banned)
        assertTrue("banned only after two empty replies", upstream.count(dead) >= 2)
        assertTrue("a node that sent data is not banned", bans.allows(mediaUrl(partial)))
        val before = upstream.count(dead)
        repeat(2) { range(downloader, resolver, 0, MB - 1); now += 61_000 }
        assertEquals("a banned node gets no new requests in this video", before, upstream.count(dead))
    }

    @Test fun `a piece with a single address survives failed replies instead of ending the video`() {
        var requests = 0
        val upstream = FakeUpstream(8 * MB) { _, _, _ -> if (++requests <= 2) FakeUpstream.Reply.Status(503) else FakeUpstream.Reply.Ok }
        val only = mediaUrl("upos-sz-mirrorali.bilivideo.com")
        val resolver = ThreadRipperCdnResolver(only, emptyList(), { Mode.CUSTOM }, null, { listOf("upos-sz-mirrorali.bilivideo.com") })
        val result = downloader(upstream).downloadPiece(ThreadRipperPiece(0, 0, 32767), resolver, ThreadRipperCancel(), emptyMap(), listOf(only))
        assertEquals(32768, result.bytes.size)
        assertEquals(3, requests)
    }

    @Test fun `a stalled first copy is rescued by a hedge copy on another node`() {
        val slow = "upos-sz-mirrorhw.bilivideo.com"
        val upstream = FakeUpstream(8 * MB) { host, _, _ -> if (host == slow) FakeUpstream.Reply.Stall else FakeUpstream.Reply.Ok }
        val resolver = ThreadRipperCdnResolver(mediaUrl(slow), emptyList(), { Mode.MAINLAND })
        val started = System.nanoTime()
        val result = downloader(upstream).downloadPiece(ThreadRipperPiece(0, 4096, 4096 + 256 * 1024 - 1), resolver,
            ThreadRipperCancel(), emptyMap(), listOf(mediaUrl(slow), mediaUrl("upos-sz-mirrorali.bilivideo.com")))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertArrayEquals(ByteArray(256 * 1024) { byteAt(4096L + it) }, result.bytes)
        assertEquals("upos-sz-mirrorali.bilivideo.com", URI(result.url).host)
        assertTrue("the hedge starts after the delay, long before the stall timeout: $elapsedMs ms", elapsedMs < 3500)
    }

    @Test fun `faster nodes get more pieces and a node under a twelfth of the best gets none`() {
        val resolver = ThreadRipperCdnResolver(mediaUrl("upos-sz-mirrorali.bilivideo.com"), emptyList(), { Mode.MAINLAND })
        val urls = resolver.urls().take(3)
        resolver.success(urls[0], 9_000_000.0)
        resolver.success(urls[1], 3_000_000.0)
        resolver.success(urls[2], 500_000.0)
        val primaries = downloader(FakeUpstream(MB)).assignPrimaries(urls, resolver, 12)
        assertEquals(12, primaries.size)
        assertEquals(9, primaries.count { it == urls[0] })
        assertEquals(3, primaries.count { it == urls[1] })
        assertFalse(urls[2] in primaries)
        // 交错分发：前几块不会全落在同一个节点上。
        assertTrue(primaries.take(4).toSet().size > 1)
    }

    @Test fun `the automatic thread count climbs on starvation, is pushed back by refusals and opens sessions wider`() {
        var now = 0L
        val auto = ThreadRipperAutoConcurrency { now }
        assertEquals(8, auto.threads())
        auto.newSession()
        assertEquals("each start opens with 16 threads", 16, auto.threads())
        now += 3000
        assertTrue(auto.starved(1200))
        assertEquals(24, auto.threads())
        now += 3000
        assertTrue(auto.pushback(429))
        assertEquals(16, auto.threads())
        now += 3000
        assertFalse("a refused level is not climbed again for a while", auto.starved(4000))
        assertEquals(16, auto.threads())
    }

    @Test fun `the proxy streams an open-ended range in order and answers what the player asks`() {
        val total = 3 * MB + 12345
        val upstream = FakeUpstream(total)
        val downloader = downloader(upstream, ThreadRipperSettings(concurrency = 16))
        val proxy = ThreadRipperProxyServer(downloader)
        proxy.start()
        try {
            val source = ThreadRipperStreamSource(mediaUrl("upos-sz-mirrorcosov.bilivideo.com"), emptyList(), false, ThreadRipperBanList()) { ThreadRipperSettings(concurrency = 16) }
            val local = proxy.register(source)
            assertTrue(local.startsWith("http://127.0.0.1:${proxy.port}/btr/"))
            assertTrue(local.endsWith("/1-1-30080.m4s"))

            val (head, body) = request(local, "GET", "bytes=1000-")
            assertTrue(head, head.startsWith("HTTP/1.1 206"))
            assertTrue(head, head.contains("Content-Range: bytes 1000-${total - 1}/$total"))
            assertEquals(total - 1000, body.size.toLong())
            assertArrayEquals(ByteArray((total - 1000).toInt()) { byteAt(1000L + it) }, body)
            // 多个大陆节点都分到了数据。
            assertTrue(upstream.requests.keys.count { it in ThreadRipperCdnPolicy.MAINLAND_HOSTS } > 1)

            val (closedHead, closed) = request(local, "GET", "bytes=10-99")
            assertTrue(closedHead.contains("Content-Range: bytes 10-99/$total"))
            assertArrayEquals(ByteArray(90) { byteAt(10L + it) }, closed)

            val (fullHead, full) = request(local, "GET", null)
            assertTrue(fullHead.startsWith("HTTP/1.1 200"))
            assertEquals(total, full.size.toLong())

            val (headOnly, empty) = request(local, "HEAD", null)
            assertTrue(headOnly.contains("Content-Length: $total"))
            assertEquals(0, empty.size)

            // 文件末尾 100 字节：首块探测越过末尾，CDN 截短后照常交付。
            val (tailHead, tail) = request(local, "GET", "bytes=${total - 100}-")
            assertTrue(tailHead, tailHead.contains("Content-Range: bytes ${total - 100}-${total - 1}/$total"))
            assertArrayEquals(ByteArray(100) { byteAt(total - 100 + it) }, tail)

            assertTrue(request(local.replace(Regex("/btr/[0-9a-f]+/"), "/btr/${"0".repeat(32)}/"), "GET", null).first.startsWith("HTTP/1.1 404"))
        } finally {
            proxy.stop()
        }
    }

    @Test(timeout = 60_000)
    fun `streaming with the automatic thread count does not deadlock the slot and controller locks`() {
        val total = 4 * MB
        val settings = ThreadRipperSettings(autoConcurrency = true)
        val proxy = ThreadRipperProxyServer(downloader(FakeUpstream(total), settings))
        proxy.start()
        try {
            val sources = List(2) { index ->
                proxy.register(ThreadRipperStreamSource(mediaUrl("upos-sz-mirrorali.bilivideo.com", "s$index"), emptyList(), index == 1, ThreadRipperBanList()) { settings })
            }
            val results = sources.map { local -> java.util.concurrent.CompletableFuture.supplyAsync { request(local, "GET", "bytes=0-") } }
            results.forEach { future ->
                val (head, body) = future.get(50, TimeUnit.SECONDS)
                assertTrue(head, head.startsWith("HTTP/1.1 206"))
                assertArrayEquals(ByteArray(total.toInt()) { byteAt(it.toLong()) }, body)
            }
        } finally {
            proxy.stop()
        }
    }

    @Test(timeout = 60_000)
    fun `sequential 1 MiB player requests are served from the prefetch cache instead of re-probing each range`() {
        // 真机上宿主播放器按 1 MiB 闭区间顺序请求；每段都重新探测曾让每 MiB 产生 15 个上游请求。
        val total = 12 * MB
        val upstream = FakeUpstream(total)
        val lines = java.util.Collections.synchronizedList(ArrayList<String>())
        val proxy = ThreadRipperProxyServer(downloader(upstream, ThreadRipperSettings(concurrency = 16)), stats = { lines += it })
        proxy.start()
        try {
            val local = proxy.register(ThreadRipperStreamSource(mediaUrl("upos-sz-mirrorcosov.bilivideo.com"), emptyList(), false, ThreadRipperBanList()) { ThreadRipperSettings(concurrency = 16) })
            for (index in 0 until 8) {
                val from = index * MB
                val (head, body) = request(local, "GET", "bytes=$from-${from + MB - 1}")
                assertTrue(head, head.contains("Content-Range: bytes $from-${from + MB - 1}/$total"))
                assertArrayEquals(ByteArray(MB.toInt()) { byteAt(from + it) }, body)
                Thread.sleep(50)
            }
            val requests = upstream.requests.values.sumOf { it.get() }
            assertTrue("upstream requests for 8 MiB: $requests", requests < 45)
            val cached = lines.filter { it.startsWith("[BTR] video") }.drop(1)
                .map { Regex("""cached=(\d+)/(\d+)""").find(it)!!.groupValues.let { g -> g[1].toInt() to g[2].toInt() } }
            assertTrue("later requests come from the cache: $cached", cached.sumOf { it.first } * 2 >= cached.sumOf { it.second })
        } finally {
            proxy.stop()
        }
    }

    @Test(timeout = 60_000)
    fun `two players reading far apart in the same stream do not evict each other`() {
        // 真机上宿主播放器会对同一路流同时请求相距很远的两段；单游标时互相驱逐，读到被取消的块就报错。
        val total = 64 * MB
        val proxy = ThreadRipperProxyServer(downloader(FakeUpstream(total), ThreadRipperSettings(concurrency = 16)))
        proxy.start()
        try {
            val local = proxy.register(ThreadRipperStreamSource(mediaUrl("upos-sz-mirrorcosov.bilivideo.com"), emptyList(), false, ThreadRipperBanList()) { ThreadRipperSettings(concurrency = 16) })
            val far = 40 * MB
            val results = listOf(0L, far).map { from ->
                java.util.concurrent.CompletableFuture.supplyAsync { from to request(local, "GET", "bytes=$from-${from + 4 * MB - 1}") }
            }.map { it.get(50, TimeUnit.SECONDS) }
            for ((from, response) in results) {
                assertTrue(response.first, response.first.startsWith("HTTP/1.1 206"))
                assertArrayEquals(ByteArray((4 * MB).toInt()) { byteAt(from + it) }, response.second)
            }
        } finally {
            proxy.stop()
        }
    }

    @Test(timeout = 60_000)
    fun `a declared DASH size lets the proxy answer headers before the slow first byte arrives`() {
        // 宿主播放器读响应头约 3.5 秒超时；新流先探测再回头曾让它放弃代理。
        val total = 4 * MB
        val fake = FakeUpstream(total)
        val slow = object : ThreadRipperFetcher {
            override fun newCall(url: String, start: Long, end: Long, headers: Map<String, String>, settings: ThreadRipperSettings): ThreadRipperFetcher.Call {
                val inner = fake.newCall(url, start, end, headers, settings)
                return object : ThreadRipperFetcher.Call {
                    override fun execute(): ThreadRipperFetcher.Response {
                        Thread.sleep(1500)
                        return inner.execute()
                    }
                    override fun cancel() = inner.cancel()
                }
            }
        }
        val proxy = ThreadRipperProxyServer(downloader(slow))
        proxy.start()
        try {
            val local = proxy.register(ThreadRipperStreamSource(mediaUrl("upos-sz-mirrorcosov.bilivideo.com"), emptyList(), false, ThreadRipperBanList(), declaredSize = total) { ThreadRipperSettings() })
            val uri = URI(local)
            Socket(uri.host, uri.port).use { socket ->
                socket.soTimeout = 30_000
                val started = System.nanoTime()
                socket.getOutputStream().write("GET ${uri.rawPath} HTTP/1.1\r\nRange: bytes=1048576-2097151\r\n\r\n".toByteArray())
                val input = socket.getInputStream()
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                val headerMs = (System.nanoTime() - started) / 1_000_000
                assertTrue("headers after $headerMs ms", headerMs < 800)
                assertTrue(head.toString(), head.contains("Content-Range: bytes 1048576-2097151/$total"))
                assertArrayEquals(ByteArray(MB.toInt()) { byteAt(MB + it) }, input.readBytes())
            }
        } finally {
            proxy.stop()
        }
    }

    @Test fun `a completed transfer releases its connection for keep-alive instead of disconnecting`() {
        val released = AtomicInteger()
        val closed = AtomicInteger()
        val fetcher = object : ThreadRipperFetcher {
            override fun newCall(url: String, start: Long, end: Long, headers: Map<String, String>, settings: ThreadRipperSettings) =
                object : ThreadRipperFetcher.Call {
                    override fun execute() = object : ThreadRipperFetcher.Response {
                        override val status = 206
                        override val contentRange = "bytes $start-$end/${MB}"
                        override val body: InputStream = ByteArrayInputStream(ByteArray((end - start + 1).toInt()))
                        override fun release() { released.incrementAndGet() }
                        override fun close() { closed.incrementAndGet() }
                    }
                    override fun cancel() { closed.incrementAndGet() }
                }
        }
        val only = mediaUrl("upos-sz-mirrorali.bilivideo.com")
        val resolver = ThreadRipperCdnResolver(only, emptyList(), { Mode.CUSTOM }, null, { listOf("upos-sz-mirrorali.bilivideo.com") })
        ThreadRipperDownloader(fetcher, { ThreadRipperSettings() }).downloadPiece(ThreadRipperPiece(0, 0, 65535), resolver, ThreadRipperCancel(), emptyMap(), listOf(only))
        assertEquals(1, released.get())
        assertEquals(0, closed.get())
    }

    @Test fun `the proxy answers 502 when no node delivers, so the player falls back to its backup address`() {
        val upstream = FakeUpstream(MB) { _, _, _ -> FakeUpstream.Reply.Status(503) }
        val proxy = ThreadRipperProxyServer(downloader(upstream))
        proxy.start()
        try {
            val local = proxy.register(ThreadRipperStreamSource(mediaUrl("upos-sz-mirrorali.bilivideo.com"), emptyList(), true, ThreadRipperBanList()) { ThreadRipperSettings() })
            assertTrue(request(local, "GET", "bytes=0-").first.startsWith("HTTP/1.1 502"))
        } finally {
            proxy.stop()
        }
    }

    private fun request(url: String, method: String, range: String?): Pair<String, ByteArray> {
        val uri = URI(url)
        Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 30_000
            val request = buildString {
                append("$method ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\nUser-Agent: test\r\n")
                if (range != null) append("Range: $range\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply { write(request.toByteArray()); flush() }
            val all = socket.getInputStream().readBytes()
            val split = (0 until all.size - 3).first { all[it] == '\r'.code.toByte() && all[it + 1] == '\n'.code.toByte() && all[it + 2] == '\r'.code.toByte() && all[it + 3] == '\n'.code.toByte() }
            return String(all, 0, split, Charsets.ISO_8859_1) to all.copyOfRange(split + 4, all.size)
        }
    }

    private companion object {
        const val MB = 1024L * 1024
        fun byteAt(position: Long): Byte = ((position * 31 + 7) % 251).toByte()
    }
}
