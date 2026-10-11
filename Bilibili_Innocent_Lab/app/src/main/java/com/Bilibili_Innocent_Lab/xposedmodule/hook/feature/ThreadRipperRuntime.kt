package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.ThreadRipperPlayUrlAccess

/**
 * 宿主进程内的线程撕裂者运行时：决定每一路 DASH 流换成什么地址。
 *
 * - 多线程模式：主地址换成本地代理，备用地址依次是首选 CDN 节点与 B 站下发的原始地址——
 *   代理起不来或首块失败（502）时，宿主播放器自己的备用地址逻辑会接手，不至于黑屏。
 * - 仅换节点模式：按原项目的节点策略生成候选地址，主地址取第一个，原始地址垫底。
 *
 * 代理与下载器在第一次真正需要时才创建；同一次播放响应里的所有流共用一份封禁表。
 */
internal class ThreadRipperRuntime(
    private val settings: ThreadRipperSettings,
    private val multithread: Boolean,
    private val log: (String) -> Unit = {},
    private val proxyFactory: (ThreadRipperDownloader) -> ThreadRipperProxyServer = { ThreadRipperProxyServer(it, log) },
    private val fetcher: ThreadRipperFetcher = HttpUrlFetcher,
) {
    private val proxyLock = Any()

    @Volatile
    private var proxy: ThreadRipperProxyServer? = null

    @Volatile
    private var proxyFailed = false

    private fun proxyOrNull(): ThreadRipperProxyServer? {
        if (!multithread || proxyFailed) return null
        proxy?.let { return it }
        return synchronized(proxyLock) {
            proxy ?: runCatching {
                proxyFactory(ThreadRipperDownloader(fetcher, { settings })).also { it.start() }
            }.onFailure {
                proxyFailed = true
                log("代理启动失败，改为仅替换节点：${it.javaClass.simpleName}")
            }.getOrNull()?.also { proxy = it }
        }
    }

    /** 一次播放响应用一个改写器：其中所有流共用一份封禁表。 */
    fun rewriterForReply(): ThreadRipperPlayUrlAccess.Rewriter {
        val bans = ThreadRipperBanList(onBan = { host, strikes, kind -> log("停用${if (kind == "node") "节点" else "地址"} $host（$strikes 次无响应）") })
        return ThreadRipperPlayUrlAccess.Rewriter { primary, backups, audio -> rewrite(primary, backups, audio, bans) }
    }

    fun rewrite(primary: String, backups: List<String>, audio: Boolean, bans: ThreadRipperBanList): Pair<String, List<String>>? {
        if (primary.startsWith(LOCAL_PREFIX)) return null
        val originals = (listOf(primary) + backups).filter(ThreadRipperCdnPolicy::isBilibiliMediaUrl)
        if (originals.isEmpty()) return null
        val candidates = ThreadRipperCdnPolicy.representationUrls(primary, backups, settings.mode, settings.customHosts)
        if (candidates.isEmpty()) return null
        val server = proxyOrNull()
        if (server != null) {
            val local = runCatching {
                server.register(ThreadRipperStreamSource(primary, backups, audio, bans) { settings })
            }.getOrNull()
            if (local != null) return local to (listOf(candidates.first()) + originals).distinct().take(MAX_BACKUPS)
        }
        val next = candidates.first()
        return next to (candidates.drop(1).take(MAX_BACKUPS - 1) + originals).distinct().filter { it != next }.take(MAX_BACKUPS)
    }

    companion object {
        /** 宿主播放器逐个尝试备用地址；太长只会拖慢真正失败时的报错。 */
        const val MAX_BACKUPS = 4
        private const val LOCAL_PREFIX = "http://127.0.0.1:"
    }
}
