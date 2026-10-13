package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.net.URI

/**
 * 线程撕裂者（移植自 MrTangLuyao/Bilibili-thread-ripper，MIT）的 CDN 纯判定层。
 *
 * 对应原项目的 `range-core.js` 与 `cdn-resolver.js`：只处理地址、节点、Range 与测速记录，
 * 不接触 protobuf、反射、网络和 Hook 边界，便于在 JVM 单测里逐条对照原项目的行为。
 */
internal object ThreadRipperCdnPolicy {

    enum class Mode(val key: String) {
        MAINLAND("mainland"),
        OVERSEAS("overseas"),
        CUSTOM("custom");

        companion object {
            fun of(key: String?): Mode = entries.firstOrNull { it.key == key } ?: MAINLAND
        }
    }

    val MAINLAND_HOSTS: List<String> = listOf(
        "upos-sz-mirrorali.bilivideo.com",
        "upos-sz-mirrorhw.bilivideo.com",
        "upos-sz-mirrorbos.bilivideo.com",
        "upos-sz-mirror08c.bilivideo.com",
        "upos-sz-mirrorbd.bilivideo.com",
        "upos-sz-mirror14b.bilivideo.com",
        "upos-sz-estgoss.bilivideo.com",
        "upos-sz-mirrorcos.bilivideo.com",
    )

    val OVERSEAS_HOSTS: List<String> = listOf(
        "upos-sz-mirrorcosov.bilivideo.com",
        "upos-sz-mirroraliov.bilivideo.com",
        "cn-hk-eq-01-01.bilivideo.com",
        "cn-hk-eq-01-03.bilivideo.com",
    )

    /** 设置页"自定义"模式里可直接勾选的已知节点。 */
    val KNOWN_HOSTS: List<String> = OVERSEAS_HOSTS + MAINLAND_HOSTS

    /** 自定义节点数量上限，与原项目一致。 */
    const val MAX_CUSTOM_HOSTS = 32

    private val MEDIA_SUFFIX = Regex("""\.(?:m4s|mp4|flv)$""", RegexOption.IGNORE_CASE)
    private val MEDIA_HOST = Regex(
        """(?:^|\.)(?:bilivideo\.(?:com|cn|net)|akamaized\.net|szbdyd\.com|hdslb\.com|xycdn\.com|mountaintoys\.cn|nexusedgeio\.com|ahdohpiechei\.com|yirujs\.com)$""",
        RegexOption.IGNORE_CASE,
    )
    private val HOST_SYNTAX = Regex("""^[a-z\d](?:[a-z\d-]*[a-z\d])?(?:\.[a-z\d](?:[a-z\d-]*[a-z\d])?)+$""")

    data class ByteRange(val start: Long, val end: Long) {
        val length: Long get() = end - start + 1
    }

    /** `Content-Range: bytes s-e/total`；total 为 `*` 时记为 null。 */
    data class ContentRange(val start: Long, val end: Long, val total: Long?) {
        val length: Long get() = end - start + 1
    }

    /**
     * 播放器发来的 Range 头。与网页端不同，ffmpeg 习惯发开放区间 `bytes=N-`，
     * 所以 [end] 为 null 表示一直到文件末尾。
     */
    data class RequestedRange(val start: Long, val end: Long?)

    fun parseContentRange(value: String?): ContentRange? {
        val match = Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""", RegexOption.IGNORE_CASE)
            .matchEntire(value?.trim() ?: return null) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = if (match.groupValues[3] == "*") null else match.groupValues[3].toLongOrNull() ?: return null
        if (end < start || (total != null && total <= end)) return null
        return ContentRange(start, end, total)
    }

    fun parseRangeHeader(value: String?): RequestedRange? {
        val match = Regex("""^bytes=(\d+)-(\d*)$""", RegexOption.IGNORE_CASE)
            .matchEntire(value?.trim() ?: return null) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return null }
        if (end != null && end < start) return null
        return RequestedRange(start, end)
    }

    /** 均分 [start, end]：块数不超过 [concurrency]，每块不小于 [minChunkBytes]（下限 32 KiB）。 */
    fun splitRange(start: Long, end: Long, concurrency: Int, minChunkBytes: Long = 128L * 1024): List<ByteRange> {
        val length = end - start + 1
        val limit = concurrency.coerceIn(1, 512)
        val minimum = maxOf(32L * 1024, minChunkBytes)
        val count = maxOf(1L, minOf(limit.toLong(), (length + minimum - 1) / minimum)).toInt()
        val base = length / count
        val remainder = length % count
        val pieces = ArrayList<ByteRange>(count)
        var cursor = start
        for (index in 0 until count) {
            val size = base + if (index < remainder) 1 else 0
            pieces += ByteRange(cursor, cursor + size - 1)
            cursor += size
        }
        return pieces
    }

    /**
     * 原项目只接受 https；客户端拿到的 PCDN 地址可能是带端口的 http，所以这里放宽到 http/https，
     * 但节点白名单与文件后缀的限制保持不变：签名地址绝不能发给 B 站以外的服务器。
     */
    fun isBilibiliMediaUrl(value: String?): Boolean {
        val uri = parse(value) ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return false
        val host = uri.host ?: return false
        return MEDIA_SUFFIX.containsMatchIn(uri.rawPath ?: return false) && MEDIA_HOST.containsMatchIn(host)
    }

    /** 自定义节点只保留主机名，且必须是 [isBilibiliMediaUrl] 接受的 B 站视频服务器。 */
    fun normalizeCdnHost(value: String?): String {
        val text = value?.trim()?.lowercase().orEmpty()
        if (text.isEmpty() || text.length > 253) return ""
        val withScheme = if (Regex("""^[a-z][a-z\d+.-]*://""").containsMatchIn(text)) text else "https://$text"
        val host = parse(withScheme)?.host?.lowercase() ?: return ""
        return if (HOST_SYNTAX.matches(host) && MEDIA_HOST.containsMatchIn(host)) host else ""
    }

    /** 设置里保存的自定义节点：规范化、去重、限量。 */
    fun normalizeCustomHosts(values: Iterable<String?>): List<String> =
        values.map(::normalizeCdnHost).filter { it.isNotEmpty() }.distinct().take(MAX_CUSTOM_HOSTS)

    fun hostOf(value: String?): String = parse(value)?.host?.lowercase().orEmpty()

    /**
     * 节点回 30x 时的跳转目标（GSLB 把请求转给具体边缘节点）。只接受 B 站视频服务器，
     * 签名地址绝不能被重定向带到别处；不合格时返回 null，由调用方按失败处理。
     */
    fun redirectTarget(current: String, location: String?): String? {
        val text = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val resolved = runCatching { URI(current).resolve(text).toString() }.getOrNull() ?: return null
        return resolved.takeIf(::isBilibiliMediaUrl)
    }

    /** 去掉节点后的签名地址：同一个地址可以向任意节点请求。 */
    fun addressOf(value: String?): String {
        val uri = parse(value) ?: return ""
        return (uri.rawPath ?: "") + (uri.rawQuery?.let { "?$it" } ?: "")
    }

    fun isAkamaiUrl(value: String?): Boolean = hostOf(value).endsWith(".akamaized.net")

    /**
     * 把签名地址换到 [targetHost]。非标准端口（如 PCDN 的 :4483）不跟随，协议统一为 https。
     * akamaized.net 地址默认不作为来源，除非 [allowAkamai]。
     */
    fun swapOrdinaryHost(rawUrl: String, targetHost: String, allowAkamai: Boolean = false): String? {
        if (!allowAkamai && isAkamaiUrl(rawUrl)) return null
        val host = targetHost.lowercase()
        if (normalizeCdnHost(host) != host) return null
        val uri = parse(rawUrl) ?: return null
        // 用原始（已编码）的 path/query 拼接，签名参数一个字节都不能变。
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: return null
        return "https://$host$path" + (uri.rawQuery?.let { "?$it" } ?: "")
    }

    private fun safeMediaUrl(value: String?): String? = value?.takeIf(::isBilibiliMediaUrl)

    private fun customServers(mode: Mode, customHosts: List<String>): List<String> =
        if (mode == Mode.CUSTOM) customHosts.map(::normalizeCdnHost).filter { it.isNotEmpty() } else emptyList()

    /**
     * 一路流（DASH 的一个 representation）可用的全部地址，对应原项目 `representationUrls`。
     *
     * - 先放模式允许的原始地址，再放把原始地址换到各节点后的合成地址。
     * - 有普通地址时只用第一个普通地址做来源；只拿到 akamaized.net 地址时（部分海外账号），
     *   这些地址都做来源，节点优先排列，让最早的几个请求覆盖到每个地址。
     */
    fun representationUrls(primary: String?, backups: List<String?>, mode: Mode, customHosts: List<String> = emptyList()): List<String> {
        val originals = (listOf(primary) + backups).mapNotNull(::safeMediaUrl).distinct()
        val custom = customServers(mode, customHosts)
        val hosts = custom.ifEmpty { if (mode == Mode.OVERSEAS) OVERSEAS_HOSTS else MAINLAND_HOSTS }
        val donor = originals.firstOrNull { !isAkamaiUrl(it) }
        val synthetic = if (donor != null) {
            hosts.mapNotNull { swapOrdinaryHost(donor, it) }
        } else {
            hosts.flatMap { host -> originals.mapNotNull { swapOrdinaryHost(it, host, allowAkamai = true) } }
        }.mapNotNull(::safeMediaUrl)
        val allowedOriginals = when {
            custom.isNotEmpty() -> originals.filter { hostOf(it) in custom }
            mode == Mode.OVERSEAS -> originals.filter { hostOf(it) !in MAINLAND_HOSTS }
            else -> originals.filter { hostOf(it) in MAINLAND_HOSTS }
        }
        return (allowedOriginals + synthetic).distinct()
    }

    /** 第一个请求也和 B 站下发的原始地址赛跑；自定义模式只用勾选的节点。 */
    fun startupOriginals(primary: String?, backups: List<String?>, mode: Mode, customHosts: List<String>): List<String> =
        if (customServers(mode, customHosts).isNotEmpty()) emptyList()
        else (listOf(primary) + backups).mapNotNull(::safeMediaUrl)

    private fun parse(value: String?): URI? = runCatching { URI(value ?: return null) }.getOrNull()
}

/**
 * 节点/地址封禁表，对应原项目 `createBanList`。整个视频（同一次 playurl 回包的所有流）共用一份。
 *
 * 两次一个字节都没收到就失败的节点，在当前视频剩下的时间里不再使用。HTTP 4xx 说明节点应答了但拒绝了签名地址，
 * 两边都可能有错，由"谁交付过数据"来判：拒绝某地址的节点若能服务别的地址，丢弃该地址；
 * 该地址在别的节点上能下载，就丢弃该节点；两者都未知时不归咎任何一方。
 */
internal class ThreadRipperBanList(
    limit: Int = 2,
    private val onBan: ((host: String, strikes: Int, kind: String) -> Unit)? = null,
) {
    private val limit = limit.coerceAtLeast(1)
    private val emptyReplies = LinkedHashMap<Triple<String, String, Boolean>, Int>()
    private val goodNodes = HashSet<String>()
    private val goodAddresses = HashSet<String>()
    private val reported = HashSet<String>()

    @Volatile
    private var banned: Set<String> = emptySet()

    @Synchronized
    private fun judge(url: String): Boolean {
        val strikes = LinkedHashMap<String, Int>()
        for ((key, count) in emptyReplies) {
            val (node, address, refused) = key
            val blamed = when {
                !refused -> "node:$node"
                node in goodNodes -> if (address in goodAddresses) "pair:$node $address" else "address:$address"
                address in goodAddresses -> "node:$node"
                else -> ""
            }
            if (blamed.isNotEmpty()) strikes[blamed] = (strikes[blamed] ?: 0) + count
        }
        banned = strikes.filterValues { it >= limit }.keys.toSet()
        var added = false
        for (key in banned) {
            if (!reported.add(key)) continue
            added = true
            val isNode = key.startsWith("node:")
            runCatching {
                onBan?.invoke(if (isNode) key.removePrefix("node:") else ThreadRipperCdnPolicy.hostOf(url), strikes[key] ?: 0, if (isNode) "node" else "address")
            }
        }
        return added
    }

    /**
     * 记录一次失败。取消（输掉竞速的副本）和已经收到数据的失败都不算。
     * @return 是否因此新增了封禁
     */
    @Synchronized
    fun record(url: String, receivedBytes: Long, status: Int, canceled: Boolean): Boolean {
        if (canceled || receivedBytes > 0) return false
        val node = ThreadRipperCdnPolicy.hostOf(url).ifEmpty { return false }
        val key = Triple(node, ThreadRipperCdnPolicy.addressOf(url), status in 400..499)
        emptyReplies[key] = (emptyReplies[key] ?: 0) + 1
        return judge(url)
    }

    @Synchronized
    fun success(url: String) {
        val node = ThreadRipperCdnPolicy.hostOf(url).ifEmpty { return }
        val address = ThreadRipperCdnPolicy.addressOf(url)
        if (node in goodNodes && address in goodAddresses) return
        goodNodes += node
        goodAddresses += address
        judge(url)
    }

    fun allows(url: String): Boolean = allowsNode(url) && allowsAddress(url) &&
        "pair:${ThreadRipperCdnPolicy.hostOf(url)} ${ThreadRipperCdnPolicy.addressOf(url)}" !in banned

    fun allowsNode(url: String): Boolean = "node:${ThreadRipperCdnPolicy.hostOf(url)}" !in banned

    fun allowsAddress(url: String): Boolean = "address:${ThreadRipperCdnPolicy.addressOf(url)}" !in banned

    fun hosts(): List<String> = banned.filter { it.startsWith("node:") }.map { it.removePrefix("node:") }

    @Synchronized
    fun reset() {
        emptyReplies.clear()
        goodNodes.clear()
        goodAddresses.clear()
        reported.clear()
        banned = emptySet()
    }
}

/**
 * 一路流的节点选择与测速，对应原项目 `createResolver`。
 *
 * 速度按"节点 + 文件路径"记录（不含签名参数），换签名的同一路线沿用前一次测速；
 * 测速 90 秒后过期，被冷落的慢节点会通过探索名额重新获得机会。
 */
internal class ThreadRipperCdnResolver(
    private val primary: String?,
    private val backups: List<String?>,
    private val mode: () -> ThreadRipperCdnPolicy.Mode,
    private val bans: ThreadRipperBanList? = null,
    private val customHosts: () -> List<String> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
    /** 跨文件共享的节点测速：新文件（换清晰度、换分P）还没有自己的测速时先用它排序。 */
    private val hostSpeeds: ThreadRipperHostSpeeds? = null,
) {
    private class Health(val failures: Int, val blockedUntil: Long, val lastSuccessAt: Long)
    internal class Route(val lastSuccessAt: Long, val lastMeasuredAt: Long, val bps: Double)

    data class NodeStatus(val host: String, val state: String, val bps: Double)

    private val health = HashMap<String, Health>()
    private val routes = HashMap<String, Route>()
    private var cursor = 0
    private var mediaRangeCount = 0
    private var rangeCursor = 0

    private fun routeOf(url: String): String =
        ThreadRipperCdnPolicy.hostOf(url) + ThreadRipperCdnPolicy.addressOf(url).substringBefore('?')

    private fun measurement(url: String): Route? = routes[routeOf(url)] ?: hostSpeeds?.get(ThreadRipperCdnPolicy.hostOf(url))

    private fun measuredNow(url: String, now: Long): Boolean {
        val item = measurement(url) ?: return false
        return item.lastMeasuredAt > 0 && now - item.lastMeasuredAt < MEASUREMENT_TTL_MS
    }

    private val bySpeed = Comparator<String> { a, b ->
        val am = measurement(a)
        val bm = measurement(b)
        val success = (if ((bm?.lastSuccessAt ?: 0) > 0) 1 else 0) - (if ((am?.lastSuccessAt ?: 0) > 0) 1 else 0)
        if (success != 0) success else (bm?.bps ?: 0.0).compareTo(am?.bps ?: 0.0)
    }

    private fun blockedUntil(url: String): Long = health[url]?.blockedUntil ?: 0

    fun allUrls(): List<String> =
        ThreadRipperCdnPolicy.representationUrls(primary, backups, mode(), customHosts())

    /** 被封禁的节点不用；全部被封时宁可继续用，也不让视频没有地址。 */
    private fun unbanned(list: List<String>): List<String> {
        bans ?: return list
        return list.filter(bans::allows).ifEmpty { list }
    }

    @Synchronized
    fun urls(): List<String> = unbanned(allUrls())

    @Synchronized
    fun ordered(pieceIndex: Int = 0, exclude: Set<String> = emptySet()): List<String> {
        val now = clock()
        val candidates = urls().filter { it !in exclude }
        val pool = candidates.filter { blockedUntil(it) <= now }.ifEmpty { candidates }
        if (pool.isEmpty()) return emptyList()
        val offset = Math.floorMod(cursor + pieceIndex, pool.size)
        cursor = (cursor + 1) % pool.size
        return pool.drop(offset) + pool.take(offset)
    }

    /**
     * 一个区间的候选节点。预热阶段（大陆模式 1 段，其余 4 段）取前几个；之后按测速排序，
     * 每段带一个未测或测速过期的节点做探索。
     */
    @Synchronized
    fun rangeCandidates(): List<String> {
        val now = clock()
        val pool = urls().filter { blockedUntil(it) <= now }.sortedWith(bySpeed)
        if (pool.isEmpty()) return urls()
        val width = minOf(if (mediaRangeCount == 0) pool.size else 6, pool.size)
        val warmupRanges = if (mode() == ThreadRipperCdnPolicy.Mode.MAINLAND) 1 else 4
        val selected: MutableList<String>
        if (mediaRangeCount < warmupRanges) {
            selected = pool.take(width).toMutableList()
            rangeCursor = width % pool.size
        } else {
            val measured = pool.filter { measuredNow(it, now) }
            val rest = pool.filterNot { measuredNow(it, now) }
            val places = minOf(rest.size, maxOf(1, width - measured.size))
            val explore = List(places) { rest[(rangeCursor + it) % rest.size] }
            rangeCursor = (rangeCursor + places) % maxOf(1, pool.size)
            selected = (measured.take(width - explore.size) + explore).toMutableList()
            for (url in pool) {
                if (selected.size >= minOf(3, pool.size)) break
                if (url !in selected) selected += url
            }
        }
        mediaRangeCount += 1
        return selected
    }

    @Synchronized
    fun startupCandidates(): List<String> {
        val now = clock()
        val originals = ThreadRipperCdnPolicy.startupOriginals(primary, backups, mode(), customHosts())
        return unbanned((originals + allUrls()).distinct()).filter { blockedUntil(it) <= now }.take(8)
    }

    @Synchronized
    fun rescueCandidates(): List<String> {
        val now = clock()
        return urls().filter { blockedUntil(it) <= now }.sortedWith(bySpeed)
    }

    /** 一次完整成功；[bps] 为 0 表示传输太短，不足以测速，沿用旧速度及其时间。 */
    @Synchronized
    fun success(url: String, bps: Double) {
        bans?.success(url)
        val old = measurement(url)
        val now = clock()
        val measured = bps > 0
        health[url] = Health(0, 0, now)
        routes[routeOf(url)] = Route(
            lastSuccessAt = now,
            lastMeasuredAt = if (measured) now else old?.lastMeasuredAt ?: 0,
            bps = when {
                !measured -> old?.bps ?: 0.0
                old != null && old.bps > 0 -> old.bps * 0.65 + bps * 0.35
                else -> bps
            },
        ).also { hostSpeeds?.put(ThreadRipperCdnPolicy.hostOf(url), it) }
    }

    /** 中途被取消的传输：只说明节点有多快，不算证明地址可用，也不清除之前的失败。 */
    @Synchronized
    fun sample(url: String, bps: Double) {
        if (bps <= 0) return
        val old = measurement(url)
        routes[routeOf(url)] = Route(
            lastSuccessAt = old?.lastSuccessAt ?: 0,
            lastMeasuredAt = clock(),
            bps = if (old != null && old.bps > 0) old.bps * 0.65 + bps * 0.35 else bps,
        ).also { hostSpeeds?.put(ThreadRipperCdnPolicy.hostOf(url), it) }
    }

    @Synchronized
    fun failure(url: String, status: Int, receivedBytes: Long, canceled: Boolean) {
        if (canceled) return
        bans?.record(url, receivedBytes, status, canceled = false)
        val old = health[url]
        val failures = (old?.failures ?: 0) + 1
        health[url] = Health(failures, clock() + minOf(60_000L, 3000L shl minOf(failures, 4)), old?.lastSuccessAt ?: 0)
    }

    fun allows(url: String): Boolean = bans?.allows(url) ?: true

    /** 测得的下载速度，用于给分片分配加权；没有或已过期时为 0。 */
    @Synchronized
    fun speed(url: String): Double = if (measuredNow(url, clock())) measurement(url)?.bps ?: 0.0 else 0.0

    @Synchronized
    fun status(): List<NodeStatus> {
        val now = clock()
        val all = allUrls()
        val usable = bans?.let { list -> all.filter(list::allowsAddress) }.orEmpty().ifEmpty { all }
        return usable.map { url ->
            val item = health[url]
            val state = when {
                bans != null && !bans.allowsNode(url) -> "banned"
                (item?.blockedUntil ?: 0) > now -> "blocked"
                (measurement(url)?.lastSuccessAt ?: 0) > 0 -> "healthy"
                else -> "untested"
            }
            NodeStatus(ThreadRipperCdnPolicy.hostOf(url), state, measurement(url)?.bps ?: 0.0)
        }
    }

    companion object {
        const val MEASUREMENT_TTL_MS = 90_000L
    }
}

/** 进程内共享的节点测速（按主机名），只作为还没有本文件测速时的先验。 */
internal class ThreadRipperHostSpeeds {
    private val speeds = HashMap<String, ThreadRipperCdnResolver.Route>()

    @Synchronized
    fun get(host: String): ThreadRipperCdnResolver.Route? = speeds[host]

    @Synchronized
    fun put(host: String, route: ThreadRipperCdnResolver.Route) {
        if (host.isNotEmpty()) speeds[host] = route
    }
}
