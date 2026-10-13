package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 线程撕裂者的多连接分片下载调度器，对应原项目 `idm-downloader.js`。
 *
 * 一段数据切成多块，分给多条连接、多个节点一起下；每块都核对 206 与 Content-Range，
 * 中途断了从已收到的位置续传，拖慢的块在另一节点再发一份副本（hedge）去救，先到者胜。
 * 网页端靠 Promise 与 AbortController，这里换成线程池、阻塞 IO 与 [ThreadRipperCancel]。
 *
 * 网页端能读到播放头位置，用"播放截止时间"排队与救援；宿主播放器不暴露这些，
 * 所以这里沿用原项目兼容模式（无截止时间）的规则，并用代理写出端"等下一块"的时长代替卡顿信号。
 */
internal data class ThreadRipperSettings(
    val mode: ThreadRipperCdnPolicy.Mode = ThreadRipperCdnPolicy.Mode.MAINLAND,
    val customHosts: List<String> = emptyList(),
    val concurrency: Int = DEFAULT_CONCURRENCY,
    val autoConcurrency: Boolean = false,
    val minChunkBytes: Long = 64L * 1024,
    val firstByteTimeoutMs: Long = 5500,
    val stallTimeoutMs: Long = 4000,
    val attemptTimeoutMs: Long = 15000,
    val hedgeDelayMs: Long = 900,
) {
    companion object {
        /** 手机上不提供原项目的 128 档：线程与内存开销都不划算。 */
        val CONCURRENCY_CHOICES = listOf(4, 8, 16, 32, 64)
        const val DEFAULT_CONCURRENCY = 8

        fun normalizeConcurrency(value: Int): Int = if (value in CONCURRENCY_CHOICES) value else DEFAULT_CONCURRENCY
    }
}

/**
 * 可从任意线程触发的取消令牌；子令牌随父令牌一起取消。
 *
 * 子任务结束后要 [detach]：一路流的令牌活得和整段播放一样长，不解绑的话每一块、每一次尝试
 * 都会在它上面留下一个监听器（连带已断开的连接对象）。
 */
internal class ThreadRipperCancel(private val parent: ThreadRipperCancel? = null) {
    @Volatile
    var reason: IOException? = null
        private set
    private val listeners = ArrayList<() -> Unit>()
    private val parentListener: (() -> Unit)? = parent?.let { owner -> { cancel(owner.reason ?: ThreadRipperCanceled("已取消")) } }

    val cancelled: Boolean get() = reason != null

    init {
        parentListener?.let { parent?.onCancel(it) }
    }

    /** 与父令牌解绑并丢弃自己的监听器；之后父令牌取消不再波及这里。 */
    fun detach() {
        parentListener?.let { listener -> parent?.let { synchronized(it) { it.listeners.remove(listener) } } }
        synchronized(this) { listeners.clear() }
    }

    fun cancel(cause: IOException = ThreadRipperCanceled("已取消")) {
        val run: List<() -> Unit>
        synchronized(this) {
            if (reason != null) return
            reason = cause
            run = listeners.toList()
            listeners.clear()
        }
        run.forEach { runCatching(it) }
    }

    fun onCancel(listener: () -> Unit) {
        synchronized(this) {
            if (reason == null) {
                listeners += listener
                return
            }
        }
        runCatching(listener)
    }

    fun throwIfCancelled() {
        reason?.let { throw it }
    }

    /** 可被取消打断的等待。 */
    fun sleep(ms: Long) {
        val deadline = System.nanoTime() + ms * 1_000_000
        while (true) {
            throwIfCancelled()
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) return
            Thread.sleep(minOf(left, 50))
        }
    }
}

internal open class ThreadRipperCanceled(message: String) : IOException(message)

/** 首字节超时、停止传输、总耗时超限。 */
internal class ThreadRipperTimeout(message: String) : ThreadRipperCanceled(message)

internal class ThreadRipperTransferError(message: String, val status: Int = 0) : IOException(message)

/** 上游 HTTP 的最小抽象：生产用 [HttpUrlFetcher]，单测换成假的。 */
internal interface ThreadRipperFetcher {
    fun newCall(url: String, start: Long, end: Long, headers: Map<String, String>, settings: ThreadRipperSettings): Call

    interface Call {
        /** 阻塞到响应头到达。 */
        fun execute(): Response

        /** 可从任意线程调用，打断连接或读取。 */
        fun cancel()
    }

    interface Response : Closeable {
        val status: Int
        val contentRange: String?
        val body: InputStream

        /** 响应体已完整读完：只关闭流，让连接回到 keep-alive 池。[close] 则直接断开。 */
        fun release() = close()
    }
}

internal object HttpUrlFetcher : ThreadRipperFetcher {
    private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    private const val MAX_REDIRECTS = 3
    private const val MAX_STALE_RETRIES = 2

    private fun isStaleConnection(error: IOException): Boolean {
        val message = error.message.orEmpty()
        return message.contains("unexpected end of stream") || message.contains("Connection reset") ||
            error is java.io.EOFException
    }

    override fun newCall(url: String, start: Long, end: Long, headers: Map<String, String>, settings: ThreadRipperSettings) =
        object : ThreadRipperFetcher.Call {
            @Volatile
            private var connection: HttpURLConnection? = null

            @Volatile
            private var cancelled = false

            override fun execute(): ThreadRipperFetcher.Response {
                // 部分节点（如 mirrorhw、mirror08c）先回 302 把请求转给边缘节点；浏览器的 fetch 会自动跟随，
                // 这里手动跟随，每一跳都校验目标仍是 B 站视频服务器，并保留 Range 头。
                var current = url
                var hops = 0
                var staleRetries = 0
                while (true) {
                    if (cancelled) throw ThreadRipperCanceled("已取消")
                    val opened = URL(current).openConnection() as HttpURLConnection
                    connection = opened
                    if (cancelled) {
                        opened.disconnect()
                        throw ThreadRipperCanceled("已取消")
                    }
                    opened.instanceFollowRedirects = false
                    opened.useCaches = false
                    opened.connectTimeout = settings.firstByteTimeoutMs.toInt()
                    opened.readTimeout = settings.stallTimeoutMs.toInt()
                    headers.forEach { (name, value) -> opened.setRequestProperty(name, value) }
                    opened.setRequestProperty("Range", "bytes=$start-$end")
                    opened.setRequestProperty("Accept-Encoding", "identity")
                    val status = try {
                        opened.responseCode
                    } catch (error: IOException) {
                        // 连接池里的空闲连接可能已被服务端关掉（keep-alive 超时），读响应头时才发现；
                        // 这不是节点的错，换一条新连接重试，不计入失败。
                        opened.disconnect()
                        if (cancelled || staleRetries >= MAX_STALE_RETRIES || !isStaleConnection(error)) throw error
                        staleRetries += 1
                        continue
                    }
                    if (status in REDIRECT_STATUSES && hops < MAX_REDIRECTS) {
                        val location = opened.getHeaderField("Location")
                        val next = ThreadRipperCdnPolicy.redirectTarget(current, location)
                        opened.disconnect()
                        if (next == null) {
                            // 只记目标主机名，便于判断该节点是不是在把请求转给我们不认识的服务器。
                            val target = runCatching { java.net.URI(current).resolve(location?.trim().orEmpty()) }.getOrNull()
                            throw ThreadRipperTransferError("redirect→${target?.host ?: "?"}${target?.port?.takeIf { it > 0 }?.let { ":$it" } ?: ""}", status)
                        }
                        current = next
                        hops += 1
                        continue
                    }
                    return object : ThreadRipperFetcher.Response {
                        override val status = status
                        override val contentRange: String? = opened.getHeaderField("Content-Range")
                        override val body: InputStream by lazy { if (status in 200..299) opened.inputStream else opened.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0)) }
                        override fun close() = opened.disconnect()

                        // disconnect() 会关掉 socket，连接池里永远没有可复用的连接；读完后只关流。
                        override fun release() {
                            runCatching { body.close() }.onFailure { opened.disconnect() }
                        }
                    }
                }
            }

            override fun cancel() {
                cancelled = true
                connection?.disconnect()
            }
        }
}

/** 一块已核对位置与长度的数据。 */
internal class ThreadRipperPiece(val index: Int, val start: Long, val end: Long) {
    val length: Long get() = end - start + 1
}

internal class ThreadRipperResult(val bytes: ByteArray, val total: Long?, val url: String)

/**
 * 按队列等级与优先级发放连接名额，对应原项目 `Semaphore`：启动探测与 init/index 最先，
 * 其次是启动块，最后是普通块；同等级内优先级高者先，再按先来后到。
 */
internal class ThreadRipperSlots(limit: Int) {
    private class Waiter(val queueClass: Int, val priority: Int, val sequence: Long) {
        var granted = false
    }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val queue = ArrayList<Waiter>()
    private var sequence = 0L

    @Volatile
    var limit: Int = limit.coerceIn(1, 512)
        private set

    @Volatile
    var active: Int = 0
        private set

    val queued: Int get() = lock.withLock { queue.size }

    /** 名额状态变化的观察者。总在**不持锁**时调用：它会进自动线程数的锁，而那边又会回调 [setLimit]。 */
    var onChange: ((active: Int, limit: Int, queued: Int) -> Unit)? = null

    fun setLimit(value: Int) {
        lock.withLock {
            limit = value.coerceIn(1, 512)
            drainLocked()
        }
        notifyChange()
    }

    private fun drainLocked() {
        while (active < limit && queue.isNotEmpty()) {
            val next = queue.minWith(compareBy<Waiter>({ it.queueClass }, { -it.priority }, { it.sequence }))
            queue.remove(next)
            next.granted = true
            active += 1
        }
        changed.signalAll()
    }

    private fun notifyChange() {
        val listener = onChange ?: return
        val (active, limit, queued) = lock.withLock { Triple(active, limit, queue.size) }
        runCatching { listener(active, limit, queued) }
    }

    /** @return 归还名额的函数，多次调用只生效一次 */
    fun acquire(cancel: ThreadRipperCancel?, queueClass: Int, priority: Int): () -> Unit {
        val waiter = lock.withLock {
            Waiter(queueClass, priority, sequence++).also {
                queue += it
                drainLocked()
            }
        }
        notifyChange()
        var abandoned: IOException? = null
        lock.withLock {
            while (!waiter.granted) {
                val reason = cancel?.reason
                if (reason != null) {
                    queue.remove(waiter)
                    drainLocked()
                    abandoned = reason
                    break
                }
                changed.await(50, TimeUnit.MILLISECONDS)
            }
        }
        abandoned?.let {
            notifyChange()
            throw it
        }
        var released = false
        return {
            val changedNow = lock.withLock {
                if (released) false else {
                    released = true
                    active = maxOf(0, active - 1)
                    drainLocked()
                    true
                }
            }
            if (changedNow) notifyChange()
        }
    }

    companion object {
        const val CRITICAL = 0
        const val STARTUP = 1
        const val ORDINARY = 2
    }
}

/**
 * 自动线程数，对应原项目 `createAutoConcurrency`：从 8 线程起步，沿 8→64 的梯子在"跟不上播放"时上调；
 * 每次上调都是试验，10 秒后吞吐没有增长就退回，并让该档休息一阵；服务器拒绝（412/429）也退一档，
 * 且一段时间内不再越过。每次起播（新视频、缓冲外的拖动）先用至少 16 线程，跟上后再降回。
 *
 * 网页端用播放器的缓冲长度判断"跟不上"；这里没有播放头，用代理写出端等下一块的时长（[starved]）代替。
 */
internal class ThreadRipperAutoConcurrency(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private class Rest(val until: Long, val hard: Boolean)
    private class Trial(val from: Int, val level: Int, val at: Long, val baseline: Double, var stalled: Boolean = false)
    private class Bucket(val at: Long, var bytes: Long)
    private class Span(val from: Long, val to: Long)

    private var level = 0
    private var changedAt = Long.MIN_VALUE / 2
    var reason: String = "起步"
        private set
    private var trial: Trial? = null
    private val resting = HashMap<Int, Rest>()
    private val buckets = ArrayDeque<Bucket>()
    private var saturated = false
    private var saturatedSince = 0L
    private val saturatedSpans = ArrayList<Span>()
    private var startup: Pair<Int, Long>? = null
    private val listeners = ArrayList<(Int) -> Unit>()

    @Synchronized
    fun threads(): Int = LADDER[level]

    @Synchronized
    fun subscribe(listener: (Int) -> Unit) {
        listeners += listener
    }

    private fun pruneBuckets(at: Long) {
        while (buckets.isNotEmpty() && at - buckets.first().at > WINDOW_MS) buckets.removeFirst()
    }

    private fun throughput(at: Long): Double {
        pruneBuckets(at)
        if (buckets.isEmpty()) return 0.0
        val bytes = buckets.sumOf { it.bytes }
        return bytes * 1000.0 / maxOf(1000L, buckets.last().at - buckets.first().at + BUCKET_MS)
    }

    private fun saturation(at: Long): Double {
        val from = at - WINDOW_MS
        saturatedSpans.removeAll { it.to <= from }
        var busy = saturatedSpans.sumOf { maxOf(0L, it.to - maxOf(it.from, from)) }
        if (saturated) busy += maxOf(0L, at - maxOf(saturatedSince, from))
        return minOf(1.0, busy.toDouble() / WINDOW_MS)
    }

    private fun resting(index: Int, at: Long): Rest? {
        val rest = resting[index] ?: return null
        if (rest.until <= at) {
            resting.remove(index)
            return null
        }
        return rest
    }

    private fun setLevel(next: Int, why: String, newTrial: Trial?) {
        level = next
        changedAt = now()
        reason = why
        trial = newTrial
        val threads = LADDER[level]
        listeners.forEach { runCatching { it(threads) } }
    }

    private fun stepUp(why: String, strong: Boolean): Boolean {
        val at = now()
        if (at - changedAt < STEP_COOLDOWN_MS) return false
        if (resting(level, at)?.hard == true) return false
        var next = level + 1
        while (next < LADDER.size) {
            val rest = resting(next, at) ?: break
            if (rest.hard || !strong) return false
            next += 1
        }
        if (next >= LADDER.size) return false
        setLevel(next, why, Trial(level, next, at, throughput(at)))
        startup = null
        return true
    }

    private fun stepDown(target: Int, restLevel: Int, why: String, restMs: Long, hard: Boolean): Boolean {
        resting[restLevel] = Rest(now() + restMs, hard)
        if (target >= level) return false
        setLevel(target, why, null)
        return true
    }

    private fun judgeTrial(at: Long) {
        val current = trial ?: return
        if (at - current.at < TRIAL_MS) return
        trial = null
        if (current.stalled || saturation(at) < 0.6 || current.baseline <= 0) return
        if (throughput(at) < current.baseline) {
            stepDown(current.from, current.level, "${LADDER[current.level]} 线程没有比 ${LADDER[current.from]} 线程更快", REST_MS, false)
        }
    }

    /** 一块完整到达。 */
    @Synchronized
    fun delivered(bytes: Long) {
        val at = now()
        val last = buckets.lastOrNull()
        if (last != null && at - last.at < BUCKET_MS) last.bytes += bytes else buckets.addLast(Bucket(at, bytes))
        pruneBuckets(at)
        judgeTrial(at)
        val start = startup
        if (start != null && at >= start.second) startup = null
    }

    /** 连接名额的占用状态变化。 */
    @Synchronized
    fun demand(active: Int, limit: Int, queued: Int) {
        val at = now()
        val isSaturated = active >= limit && queued > 0
        if (isSaturated == saturated) return
        if (saturated) saturatedSpans += Span(saturatedSince, at)
        saturated = isSaturated
        saturatedSince = at
        saturation(at)
    }

    /**
     * 播放器在等数据：代理写出端等下一块超过 1 秒记一次"跟不上"，超过 3 秒按卡顿处理（强信号）。
     * 起播阶段等得久说明起播线程也不够，同样上调并结束起播。
     */
    @Synchronized
    fun starved(waitedMs: Long): Boolean {
        if (waitedMs >= STALL_MS) {
            trial?.stalled = true
            return stepUp("播放卡了一下", true)
        }
        return if (waitedMs >= PRESSURE_MS) stepUp("缓冲跟不上播放", false) else false
    }

    /** 写出端已经领先足够多：起播阶段逐级降回原来的档位。 */
    @Synchronized
    fun comfortable() {
        val start = startup ?: return
        val at = now()
        if (at >= start.second) {
            startup = null
            return
        }
        if (at - changedAt < STEP_COOLDOWN_MS) return
        if (level > start.first) setLevel(level - 1, "开头已经跟上，线程数降到 ${LADDER[level - 1]}", null)
        if (level <= start.first) startup = null
    }

    /** 名额全满时某个连接首字节等太久。 */
    @Synchronized
    fun slow(): Boolean = if (saturation(now()) >= 0.6) stepUp("连接排队等太久", false) else false

    /** 服务器拒绝负载：退一档，并在一段时间内不再越过。 */
    @Synchronized
    fun pushback(status: Int): Boolean = stepDown(maxOf(0, level - 1), level, "服务器返回 $status", PUSHBACK_REST_MS, true)

    /** 新的一次起播。 */
    @Synchronized
    fun newSession() {
        val at = now()
        trial = null
        buckets.clear()
        saturatedSpans.clear()
        if (saturated) saturatedSince = at
        val base = startup?.first ?: level
        var target = level
        if (resting(level, at)?.hard != true) {
            while (target < STARTUP_LEVEL && resting(target + 1, at)?.hard != true) target += 1
        }
        if (target > level) setLevel(target, "开头先用 ${LADDER[target]} 线程", null)
        startup = if (level > base) base to at + STARTUP_MAX_MS else null
    }

    companion object {
        val LADDER = listOf(8, 12, 16, 24, 32, 48, 64)
        private const val STARTUP_LEVEL = 2
        private const val STARTUP_MAX_MS = 30_000L
        private const val STEP_COOLDOWN_MS = 2500L
        private const val TRIAL_MS = 10_000L
        private const val WINDOW_MS = 5000L
        private const val BUCKET_MS = 250L
        private const val REST_MS = 90_000L
        private const val PUSHBACK_REST_MS = 180_000L
        private const val PRESSURE_MS = 1000L
        private const val STALL_MS = 3000L
    }
}

internal class ThreadRipperDownloader(
    private val fetcher: ThreadRipperFetcher,
    private val settings: () -> ThreadRipperSettings,
    private val executor: ExecutorService = Executors.newCachedThreadPool(daemonThreads("BTR-io")),
    // 每次尝试都排两个计时器；取消后立刻出队，否则会带着连接对象在队列里待满 15 秒。
    private val timers: ScheduledExecutorService =
        ScheduledThreadPoolExecutor(1, daemonThreads("BTR-timer")).apply { removeOnCancelPolicy = true },
    val autoConcurrency: ThreadRipperAutoConcurrency = ThreadRipperAutoConcurrency(),
) {
    val slots = ThreadRipperSlots(effective().concurrency)

    /** 单条连接通常能下多快、一块通常要多久；块大小与 hedge 延迟跟随这两个测量值。 */
    private val meterLock = Any()
    private var connectionBps = 0.0
    private var pieceMs = 0.0
    private val assignTurn = AtomicInteger()
    private val trialWaited = java.util.WeakHashMap<ThreadRipperCdnResolver, IntArray>()

    /** 已完整到达的字节数，供设置页/诊断显示。 */
    val deliveredBytes = AtomicLong()

    /** 累计计数，供统计日志按差值汇总；只有数字，不含地址。 */
    class Counters {
        val attempts = AtomicLong()
        val hedges = AtomicLong()
        val failures = AtomicLong()
        val firstByteMsTotal = AtomicLong()
        val firstByteSamples = AtomicLong()

        fun snapshot() = longArrayOf(attempts.get(), hedges.get(), failures.get(), firstByteMsTotal.get(), firstByteSamples.get())

        /** 失败原因按"节点|原因"计数；[drainFailures] 取走并清零。 */
        private val reasons = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

        fun recordFailure(host: String, reason: String) {
            reasons.getOrPut("$host|$reason") { AtomicInteger() }.incrementAndGet()
        }

        fun drainFailures(): Map<String, Int> {
            val out = HashMap<String, Int>()
            for (key in reasons.keys.toList()) reasons.remove(key)?.get()?.takeIf { it > 0 }?.let { out[key] = it }
            return out
        }
    }

    val counters = Counters()

    init {
        slots.onChange = { active, limit, queued -> if (effective().autoConcurrency) autoConcurrency.demand(active, limit, queued) }
        autoConcurrency.subscribe { applySettings() }
    }

    /** 自动模式下线程数来自控制器，其余仍按用户设置。 */
    fun effective(): ThreadRipperSettings {
        val raw = settings()
        return if (raw.autoConcurrency) raw.copy(concurrency = autoConcurrency.threads()) else raw
    }

    fun applySettings() = slots.setLimit(effective().concurrency)

    private fun recordMeter(bytes: Long, elapsedMs: Long) {
        if (bytes < SPEED_SAMPLE_MIN_BYTES || elapsedMs <= 0) return
        val bps = bytes * 1000.0 / elapsedMs
        synchronized(meterLock) {
            connectionBps = if (connectionBps > 0) connectionBps * 0.7 + bps * 0.3 else bps
            pieceMs = if (pieceMs > 0) pieceMs * 0.7 + elapsedMs * 0.3 else elapsedMs.toDouble()
        }
    }

    /**
     * 一块应让连接忙上大半秒，否则高延迟线路上请求往返占了大头。速度未知时 64 KiB 为底，
     * 且一个区间至少按节点数切开：总带宽只靠分散到多个节点增长。
     */
    fun adaptiveMinChunk(rangeLength: Long, pieceLimit: Int, hostCount: Int): Long {
        val config = effective()
        val bps = synchronized(meterLock) { connectionBps }
        if (bps <= 0) return config.minChunkBytes
        val target = (bps * 0.6 / CHUNK_UNIT).toLong() * CHUNK_UNIT
        val spread = (rangeLength + maxOf(1, minOf(maxOf(4, hostCount), pieceLimit)) - 1) / maxOf(1, minOf(maxOf(4, hostCount), pieceLimit))
        return maxOf(config.minChunkBytes, minOf(1024L * 1024, target, spread))
    }

    /** 一块明显比平时慢时就发副本，不必总等满固定延迟。 */
    private fun hedgeDelayMs(): Long {
        val measured = synchronized(meterLock) { pieceMs }
        val fixed = effective().hedgeDelayMs
        return if (measured > 0) (measured * 1.5).toLong().coerceIn(250, fixed) else fixed
    }

    /** 一次尝试的进度记录：失败后保留已收到的部分，供续传或副本只请求缺的尾巴。 */
    private class Recorder {
        val chunks = ArrayList<ByteArray>()

        @Volatile
        var bytes = 0L

        @Synchronized
        fun add(chunk: ByteArray) {
            chunks += chunk
            bytes += chunk.size
        }

        @Synchronized
        fun snapshot(): List<ByteArray> = chunks.toList()
    }

    private class Prefix(val bytes: Long, val chunks: List<ByteArray>)

    private class AttemptContext(val base: Prefix?, val recorder: Recorder, val startedAt: Long, val length: Long) {
        @Volatile
        var seenBytes = 0L

        @Volatile
        var seenAt = startedAt
    }

    /**
     * 一次 Range 请求。拿到名额后才调用 [begin] 决定请求哪一段：排队中的副本从第一份副本届时
     * 已收到的位置续传，而不是排队那一刻的位置。
     */
    private fun attempt(
        piece: ThreadRipperPiece,
        url: String,
        cancel: ThreadRipperCancel,
        resolver: ThreadRipperCdnResolver,
        headers: Map<String, String>,
        priority: Int,
        queueClass: Int,
        onChunk: ((position: Long, bytes: ByteArray) -> Unit)? = null,
        begin: (() -> Pair<ThreadRipperPiece, Recorder>)? = null,
    ): ThreadRipperResult {
        val config = effective()
        val release = slots.acquire(cancel, queueClass, priority)
        counters.attempts.incrementAndGet()
        var part = piece
        var received = Recorder()
        val local = ThreadRipperCancel(cancel)
        var gotHeaders = false
        val startedAt = nowMs()
        try {
            if (begin != null) {
                val plan = begin()
                part = plan.first
                received = plan.second
            }
            val firstByte = timers.schedule({ if (!gotHeaders) local.cancel(ThreadRipperTimeout("CDN 首字节超时")) }, config.firstByteTimeoutMs, TimeUnit.MILLISECONDS)
            val total = timers.schedule({ local.cancel(ThreadRipperTimeout("CDN 子块总耗时超限")) }, config.attemptTimeoutMs, TimeUnit.MILLISECONDS)
            val call = fetcher.newCall(url, part.start, part.end, headers, config)
            var completed = false
            local.onCancel { if (!completed) call.cancel() }
            try {
                val response = try {
                    call.execute()
                } catch (error: IOException) {
                    throw local.reason ?: error
                }
                gotHeaders = true
                counters.firstByteMsTotal.addAndGet(nowMs() - startedAt)
                counters.firstByteSamples.incrementAndGet()
                try {
                    val contentRange = ThreadRipperCdnPolicy.parseContentRange(response.contentRange)
                    // 首块探测时还不知道文件总长，请求可能越过文件末尾；服务器按 RFC 把终点截到末尾，
                    // 只要截断处正好是文件末尾就接受，这一块随之变短。
                    val clampedAtEof = contentRange != null && contentRange.end < part.end && contentRange.total == contentRange.end + 1
                    if (response.status != 206 || contentRange == null || contentRange.start != part.start ||
                        (contentRange.end != part.end && !clampedAtEof)) {
                        // 状态码区分"签名地址被拒"（4xx）与"节点挂了"。
                        throw ThreadRipperTransferError("Range 校验失败：HTTP ${response.status}", response.status)
                    }
                    if (clampedAtEof) part = ThreadRipperPiece(part.index, part.start, contentRange.end)
                    val buffer = ByteArray(BUFFER_BYTES)
                    var read = 0L
                    try {
                        while (read < part.length) {
                            local.throwIfCancelled()
                            val count = response.body.read(buffer, 0, minOf(buffer.size.toLong(), part.length - read).toInt())
                            if (count < 0) break
                            val chunk = buffer.copyOf(count)
                            received.add(chunk)
                            // Content-Range 已核对过起点，到达的字节位置可信，可以先交给等着的播放器。
                            onChunk?.invoke(part.start + read, chunk)
                            read += count
                        }
                    } catch (error: IOException) {
                        throw local.reason ?: ThreadRipperTimeout(if (error is java.net.SocketTimeoutException) "CDN 子块停止传输" else error.message ?: "CDN 读取失败")
                    }
                    local.throwIfCancelled()
                    if (read != part.length) throw ThreadRipperTransferError("子块长度不符：$read/${part.length}")
                    val elapsed = maxOf(1L, nowMs() - startedAt)
                    recordMeter(read, elapsed)
                    resolver.success(url, if (read >= SPEED_SAMPLE_MIN_BYTES) read * 1000.0 / elapsed else 0.0)
                    val result = ThreadRipperResult(concat(received.snapshot(), part.length), contentRange.total, url)
                    completed = true
                    response.release()
                    return result
                } finally {
                    if (!completed) response.close()
                }
            } finally {
                firstByte.cancel(false)
                total.cancel(false)
            }
        } catch (error: IOException) {
            val canceled = error is ThreadRipperCanceled && error !is ThreadRipperTimeout
            // 输掉竞速的副本是被切断而非坏掉，它收到的字节就是该节点的一次测速。
            if (canceled && received.bytes >= SPEED_SAMPLE_MIN_BYTES) {
                resolver.sample(url, received.bytes * 1000.0 / maxOf(1L, nowMs() - startedAt))
            }
            val status = (error as? ThreadRipperTransferError)?.status ?: 0
            resolver.failure(url, status, received.bytes, canceled)
            if (!canceled) {
                counters.failures.incrementAndGet()
                val reason = when {
                    status > 0 && error.message?.startsWith("redirect") == true -> "http$status ${error.message}"
                    status > 0 -> "http$status"
                    error is ThreadRipperTimeout -> error.message ?: "timeout"
                    else -> error.javaClass.simpleName + ":" + (error.message ?: "").take(40)
                } + if (received.bytes > 0) "+${received.bytes / 1024}K" else ""
                counters.recordFailure(ThreadRipperCdnPolicy.hostOf(url).substringBefore('.'), reason)
            }
            if (config.autoConcurrency) {
                if (status == 412 || status == 429) autoConcurrency.pushback(status)
                else if (error is ThreadRipperTimeout && received.bytes == 0L) autoConcurrency.slow()
            }
            throw error
        } finally {
            local.cancel()
            local.detach()
            release()
        }
    }

    private fun pieceCandidates(piece: ThreadRipperPiece, resolver: ThreadRipperCdnResolver, preferred: List<String>, round: Int): List<String> {
        val offset = if (preferred.isEmpty()) 0 else round % preferred.size
        val rotated = preferred.drop(offset) + preferred.take(offset)
        val rescue = resolver.rescueCandidates().filter { it !in rotated }
        // 第一份之后的副本交给已知最快的节点：落在最慢节点上的 hedge 救不了任何东西。
        val rest = (rotated.drop(1) + rescue).sortedByDescending(resolver::speed)
        val candidates = (if (rotated.isNotEmpty()) listOf(rotated.first()) + rest else rest).toMutableList()
        for (url in resolver.ordered(piece.index)) if (url !in candidates) candidates += url
        return candidates
    }

    /**
     * 是否现在为第一份副本再发一份，对应原项目 `hedgeDue` 去掉截止时间的部分：
     * 还没数据或停了一整个延迟——发；副本节点已知明显更快——发；比平时的连接慢——发；
     * 跟得上平时速度且副本节点不比它快——不发，带宽留给后面的块；副本节点未测过——试一下。
     */
    private fun hedgeDue(first: AttemptContext, delayMs: Long, copyBps: Double): Boolean {
        val now = nowMs()
        val ran = now - first.startedAt
        val got = first.recorder.bytes
        if (got != first.seenBytes) {
            first.seenBytes = got
            first.seenAt = now
        }
        if (ran < delayMs) return false
        if (got == 0L || now - first.seenAt >= delayMs) return true
        val rate = got * 1000.0 / maxOf(1L, ran)
        if (copyBps > rate * HEDGE_FASTER) return true
        val usual = synchronized(meterLock) { connectionBps }
        if (usual <= 0 || rate < usual * HEDGE_PACE) return true
        return copyBps <= 0
    }

    private sealed class Outcome {
        class Win(val result: ThreadRipperResult) : Outcome()
        class Fail(val error: IOException) : Outcome()
    }

    /**
     * 下载一块，对应原项目 `downloadPiece`：最多三轮，每轮按候选顺序两两竞速（探测时一次全上），
     * 第一份失败或拖慢就立刻发副本；副本与重试只请求还缺的尾巴，再与已收到的前缀拼接。
     */
    fun downloadPiece(
        piece: ThreadRipperPiece,
        resolver: ThreadRipperCdnResolver,
        cancel: ThreadRipperCancel,
        headers: Map<String, String>,
        preferred: List<String>,
        startupMode: StartupMode = StartupMode.NONE,
        priority: Int = 0,
        /**
         * 是否允许为它发副本。网页端按播放截止时间决定；这里由调用方告诉我们播放器是否正在等这一块。
         * 不允许时，只有第一份完全停住（3 倍延迟没有新数据）才救援。null = 总是允许。
         */
        hedgeWhen: (() -> Boolean)? = null,
        /** 每段到达的数据（绝对位置），供调用方边下边交付；同一位置可能来自多个副本，内容相同。 */
        onChunk: ((position: Long, bytes: ByteArray) -> Unit)? = null,
    ): ThreadRipperResult {
        val startedAt = nowMs()
        var lastError: IOException? = null
        var prefix: Prefix? = null
        val prefixLock = Any()
        val probe = startupMode == StartupMode.PROBE
        val startup = startupMode != StartupMode.NONE

        fun keepProgress(base: Prefix?, recorder: Recorder) = synchronized(prefixLock) {
            val bytes = (base?.bytes ?: 0) + recorder.bytes
            if (bytes > (prefix?.bytes ?: 0) && bytes < piece.length) {
                prefix = Prefix(bytes, (base?.chunks ?: emptyList()) + recorder.snapshot())
            }
        }

        for (round in 0 until PIECE_ROUNDS) {
            if (round > 0) {
                if (nowMs() - startedAt > PIECE_RETRY_WINDOW_MS) break
                cancel.sleep(minOf(2000L, 500L shl (round - 1)))
            }
            val candidates = pieceCandidates(piece, resolver, preferred, round)
            val limit = minOf(8, candidates.size)
            // 首块探测原项目一次全上（最多 8 路）；手机上每路都是一次 TLS 握手，3 路已足够选出快节点。
            val batchWidth = if (probe) minOf(3, limit) else 2
            val tried = HashSet<String>()
            while (tried.size < limit) {
                cancel.throwIfCancelled()
                // 等待期间被封禁的节点跳过，除非只剩被封禁的。
                val untried = candidates.filter { it !in tried }
                val open = untried.filter(resolver::allows)
                val pair = open.ifEmpty { untried }.take(batchWidth)
                if (pair.isEmpty()) break
                tried += pair
                val race = ThreadRipperCancel(cancel)
                val outcomes = LinkedBlockingQueue<Outcome>()
                val contexts = arrayOfNulls<AttemptContext>(pair.size)
                val firstFailed = java.util.concurrent.atomic.AtomicBoolean(false)
                val controllers = pair.map { ThreadRipperCancel(race) }
                pair.forEachIndexed { pairIndex, url ->
                    executor.execute {
                        var base: Prefix? = null
                        val recorder = Recorder()
                        try {
                            if (pairIndex > 0) {
                                val delay = if (probe) 0 else if (startup) minOf(250L, hedgeDelayMs()) else hedgeDelayMs()
                                while (true) {
                                    controllers[pairIndex].throwIfCancelled()
                                    if (firstFailed.get()) break
                                    val first = contexts[0]
                                    // 第一份还在排队时副本也只会排队，等它拿到连接再判断。
                                    if (probe) break
                                    if (first != null && hedgeDue(first, delay, resolver.speed(url))) {
                                        val stuck = nowMs() - first.seenAt >= delay * 3
                                        if (hedgeWhen?.invoke() != false || stuck) break
                                    }
                                    Thread.sleep(HEDGE_POLL_MS)
                                }
                            }
                            if (pairIndex > 0) counters.hedges.incrementAndGet()
                            val result = attempt(
                                piece, url, controllers[pairIndex], resolver, headers,
                                priority + if (pairIndex > 0) 20 else 0,
                                when {
                                    probe -> ThreadRipperSlots.CRITICAL
                                    startup -> ThreadRipperSlots.STARTUP
                                    else -> ThreadRipperSlots.ORDINARY
                                },
                                onChunk,
                            ) {
                                base = synchronized(prefixLock) { prefix?.takeIf { it.bytes >= RESUME_MIN_BYTES } }
                                if (pairIndex > 0) {
                                    val live = contexts[0]?.let { context ->
                                        val chunks = context.recorder.snapshot()
                                        Prefix((context.base?.bytes ?: 0) + chunks.sumOf { it.size.toLong() }, (context.base?.chunks ?: emptyList()) + chunks)
                                    }
                                    if (live != null && live.bytes >= RESUME_MIN_BYTES && live.bytes > (base?.bytes ?: 0)) base = live
                                }
                                if ((base?.bytes ?: 0) >= piece.length) base = null
                                val from = base
                                contexts[pairIndex] = AttemptContext(from, recorder, nowMs(), piece.length - (from?.bytes ?: 0))
                                val part = if (from != null) ThreadRipperPiece(piece.index, piece.start + from.bytes, piece.end) else piece
                                part to recorder
                            }
                            val from = base
                            outcomes += Outcome.Win(
                                if (from != null) ThreadRipperResult(concat(from.chunks + listOf(result.bytes), piece.length), result.total, result.url) else result,
                            )
                        } catch (error: IOException) {
                            keepProgress(base, recorder)
                            if (pairIndex == 0) firstFailed.set(true)
                            outcomes += Outcome.Fail(error)
                        } catch (error: Throwable) {
                            if (pairIndex == 0) firstFailed.set(true)
                            outcomes += Outcome.Fail(IOException(error.message ?: error.javaClass.simpleName, error))
                        }
                    }
                }
                try {
                    var failures = 0
                    while (failures < pair.size) {
                        cancel.throwIfCancelled()
                        when (val outcome = outcomes.poll(100, TimeUnit.MILLISECONDS) ?: continue) {
                            is Outcome.Win -> {
                                race.cancel(ThreadRipperCanceled("并发副本已取消"))
                                if (effective().autoConcurrency) autoConcurrency.delivered(piece.length)
                                deliveredBytes.addAndGet(piece.length)
                                return outcome.result
                            }
                            is Outcome.Fail -> {
                                failures += 1
                                lastError = outcome.error
                            }
                        }
                    }
                } finally {
                    race.cancel(ThreadRipperCanceled("并发副本已取消"))
                    race.detach()
                }
            }
        }
        cancel.throwIfCancelled()
        throw lastError ?: ThreadRipperTransferError("没有可用 CDN")
    }

    enum class StartupMode { NONE, STARTUP, PROBE }

    /**
     * 每块先试哪个地址，对应原项目 `assignPrimaries`：最快的节点分到最多的块，测速不到最快 1/12 的节点
     * 完全不分；未测节点从区间末尾领一两块试用。按平滑加权轮询交错分发，而不是每个节点一整段。
     */
    fun assignPrimaries(source: List<String>, resolver: ThreadRipperCdnResolver, requested: Int): List<String> {
        if (source.isEmpty() || requested <= 0) return emptyList()
        if (source.size == 1) return List(requested) { source[0] }
        var urls = source
        var count = requested
        val turn = assignTurn.getAndUpdate { (it + 1) % 4096 }
        var known = urls.map { maxOf(0.0, resolver.speed(it)) }
        if (known.none { it > 0 }) return List(count) { urls[(it + turn) % urls.size] }
        val top = known.max()
        val eligible = urls.filterIndexed { index, _ -> known[index] == 0.0 || known[index] >= top / 12 }
        if (eligible.isNotEmpty() && eligible.size < urls.size) {
            urls = eligible
            known = urls.map { maxOf(0.0, resolver.speed(it)) }
        }
        val unknown = urls.filterIndexed { index, _ -> known[index] == 0.0 }
        var trials = minOf(unknown.size * 2, count / 4)
        val state = synchronized(trialWaited) { trialWaited.getOrPut(resolver) { IntArray(2) } }
        if (trials == 0 && unknown.isNotEmpty() && count >= 2) {
            state[0] += 1
            if (state[0] >= 4) trials = 1
        }
        if (trials > 0) state[0] = 0
        if (unknown.isNotEmpty()) {
            urls = urls.filterIndexed { index, _ -> known[index] > 0 }
            known = urls.map { maxOf(0.0, resolver.speed(it)) }
            count -= trials
        }
        val weights = known.map { maxOf(it, top * 0.05) }
        val totalWeight = weights.sum()
        val primaries = ArrayList<String>(requested)
        val credit = DoubleArray(urls.size)
        val order = urls.indices.map { (it + turn) % urls.size }
        repeat(count) {
            var best = order[0]
            for (index in order) {
                credit[index] += weights[index]
                if (credit[index] > credit[best]) best = index
            }
            credit[best] -= totalWeight
            primaries += urls[best]
        }
        repeat(trials) { primaries += unknown[(it + state[1]) % unknown.size] }
        state[1] = (state[1] + trials) % 4096
        return primaries
    }

    /** 首块探测：头部一小块在启动候选里错峰赛跑（0/120/300 ms），同时拿到文件总长。 */
    fun probe(resolver: ThreadRipperCdnResolver, start: Long, end: Long, cancel: ThreadRipperCancel, headers: Map<String, String>): ThreadRipperResult {
        applySettings()
        val piece = ThreadRipperPiece(0, start, end)
        var preferred = resolver.startupCandidates().take(3)
        if (preferred.isEmpty()) preferred = resolver.ordered(0).take(3)
        return downloadPiece(piece, resolver, cancel, headers, preferred, StartupMode.PROBE, 220)
    }

    fun submit(task: () -> Unit) = executor.execute(task)

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    companion object {
        private const val PIECE_ROUNDS = 3
        private const val PIECE_RETRY_WINDOW_MS = 25_000L
        private const val RESUME_MIN_BYTES = 32L * 1024
        private const val HEDGE_PACE = 0.6
        private const val HEDGE_FASTER = 1.5
        private const val HEDGE_POLL_MS = 50L
        private const val SPEED_SAMPLE_MIN_BYTES = 48L * 1024
        private const val CHUNK_UNIT = 64L * 1024
        private const val BUFFER_BYTES = 32 * 1024

        fun concat(chunks: List<ByteArray>, expected: Long): ByteArray {
            val output = ByteArray(expected.toInt())
            var offset = 0
            for (chunk in chunks) {
                if (offset + chunk.size > expected) throw ThreadRipperTransferError("子区间超出目标长度")
                System.arraycopy(chunk, 0, output, offset, chunk.size)
                offset += chunk.size
            }
            if (offset.toLong() != expected) throw ThreadRipperTransferError("子区间长度不符：$offset/$expected")
            return output
        }

        fun daemonThreads(prefix: String): ThreadFactory {
            val counter = AtomicInteger()
            return ThreadFactory { runnable ->
                Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }
}
