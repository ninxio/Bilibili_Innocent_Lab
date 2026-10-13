package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.ThreadRipperPlayUrlAccess
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Method

/**
 * 线程撕裂者（移植自 MrTangLuyao/Bilibili-thread-ripper）：改写播放响应里的 DASH 地址。
 *
 * 只改地址，不创建播放权限、不改清晰度与会员限制；签名地址只会发往 B 站视频服务器。
 * 离线缓存请求（`download > 0`）保持原样：本地代理地址离开当前进程就失效。
 */
internal class ThreadRipperFeatureInstaller(
    private val enabled: Boolean,
    private val multithread: Boolean,
    private val settings: ThreadRipperSettings,
    /** 统计日志输出；null 表示关闭。由 HookEntry 按模块日志设置决定。 */
    private val stats: ((String) -> Unit)? = null,
    private val runtimeFactory: (log: (String) -> Unit) -> ThreadRipperRuntime = { log ->
        ThreadRipperRuntime(settings, multithread, log, stats)
    },
) : FeatureInstaller {
    override val id = ID
    override val capabilityIds: List<String> = emptyList()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-class-loader")
        val handler = KavaMemberLookup.classOrNull(loader, MOSS_HANDLER)?.takeIf { it.isInterface }
        val runtime = runtimeFactory { message -> environment.logInfo("thread_ripper_runtime", "[BIL] 线程撕裂者：$message") }
        var hooks = 0
        for (family in FAMILIES) {
            val access = ThreadRipperPlayUrlAccess.resolve(loader, family.reply, family.info)
            val owner = KavaMemberLookup.classOrNull(loader, family.moss)
            val request = KavaMemberLookup.classOrNull(loader, family.request)
            if (access == null || owner == null || request == null) {
                environment.logError("thread_ripper_missing_${family.id}",
                    "[BIL] 线程撕裂者缺少播放响应结构(${family.id})，保留该链路原行为")
                continue
            }
            val download = DownloadFlag.resolve(request, family.downloadPath)
            fun apply(requestValue: Any?, reply: Any) {
                if (!access.replyClass.isInstance(reply)) return
                if (requestValue != null && download?.isDownload(requestValue) == true) {
                    stats?.invoke("[BTR] reply ${family.id} skipped=download")
                    return
                }
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                runCatching { access.apply(reply, runtime.rewriterForReply()) }.onSuccess { changed ->
                    stats?.invoke("[BTR] reply ${family.id} rewritten=$changed")
                    if (changed > 0) environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED, changed)
                }.onFailure {
                    environment.logError("thread_ripper_runtime_${family.id}",
                        "[BIL] 线程撕裂者改写失败(${family.id}): ${it.javaClass.simpleName}")
                }
            }
            val sync = listOf("execute${family.method.replaceFirstChar(Char::uppercaseChar)}", family.method)
                .firstNotNullOfOrNull { name ->
                    KavaMemberLookup.methodOrNull(owner, name, request)?.takeIf {
                        !it.isStatic && it.returnType == access.replyClass
                    }
                }
            if (sync != null && runCatching {
                    environment.registrar.exact("thread_ripper.${family.id}.sync", owner, sync.name, *sync.parameterTypes) {
                        after { if (!hasThrowable) result?.let { apply(argOrNull(0), it) } }
                    }
                }.isSuccess) hooks++
            val async = handler?.let {
                KavaMemberLookup.methodOrNull(owner, family.method, request, it)?.takeIf { method ->
                    !method.isStatic && method.returnType == Void.TYPE
                }
            }
            if (async != null && runCatching {
                    environment.registrar.exact("thread_ripper.${family.id}.async", owner, async.name, *async.parameterTypes) {
                        before {
                            val requestValue = argOrNull(0)
                            val delegate = argOrNull(1) ?: return@before
                            val wrapped = MossResponseHandlerProxy.wrap(handler, delegate) { apply(requestValue, it) } ?: return@before
                            args[1] = wrapped
                        }
                    }
                }.isSuccess) hooks++
        }
        val expected = FAMILIES.size * 2
        val status = if (hooks == expected) "success" else "partial:$hooks/$expected"
        environment.reportStatus(CHANNEL, status)
        if (hooks == 0) return FeatureInstallResult.Skipped("missing-thread-ripper-points")
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        stats?.invoke("[BTR] installed $status hooks=$hooks multithread=$multithread mode=${settings.mode.key}")
        environment.logInfo("thread_ripper_installed",
            "[BIL] 线程撕裂者已安装($status, hooks=$hooks, multithread=$multithread)")
        return FeatureInstallResult.Installed(hooks, complete = hooks == expected)
    }

    /** 请求里的 `download` 字段；路径上任何一环缺失都视为"不是下载"。 */
    private class DownloadFlag(private val path: List<Method>) {
        fun isDownload(request: Any): Boolean = runCatching {
            var value: Any? = request
            for (method in path) value = value?.let(method::invoke)
            (value as? Number)?.toLong()?.let { it > 0 } ?: false
        }.getOrDefault(false)

        companion object {
            fun resolve(request: Class<*>, names: List<String>): DownloadFlag? {
                val path = ArrayList<Method>()
                var owner = request
                for (name in names) {
                    val method = KavaMemberLookup.methodOrNull(owner, name)?.takeIf { !it.isStatic } ?: return null
                    path += method
                    owner = method.returnType
                }
                return DownloadFlag(path)
            }
        }
    }

    private data class Family(
        val id: String,
        val moss: String,
        val request: String,
        val reply: String,
        val info: String,
        val method: String,
        val downloadPath: List<String>,
    )

    companion object {
        const val ID = "thread_ripper"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL = "thread_ripper_status"
        private const val MOSS_HANDLER = "com.bilibili.lib.moss.api.MossResponseHandler"
        private val FAMILIES = listOf(
            Family("unite", "com.bapis.bilibili.app.playerunite.v1.PlayerMoss",
                "com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReq",
                "com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReply", "VodInfo", "playViewUnite",
                listOf("getVod", "getDownload")),
            Family("legacy", "com.bapis.bilibili.app.playurl.v1.PlayURLMoss",
                "com.bapis.bilibili.app.playurl.v1.PlayViewReq",
                "com.bapis.bilibili.app.playurl.v1.PlayViewReply", "VideoInfo", "playView",
                listOf("getDownload")),
        )
    }
}
