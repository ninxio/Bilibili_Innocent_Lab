package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ProtobufBuilderPlan
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Method

/**
 * 播放响应里 DASH 地址的读写边界（线程撕裂者用）。
 *
 * 所有反射在安装期完成；子消息类型一律从 getter 的返回类型推出，不写死包名，
 * 因此 `playershared` 与混淆包名变体（如 `p4308playershared`）走同一条路径。
 * protobuf 只做 builder 副本，全部副本准备好后才写回顶层字段，绝不修改默认实例。
 */
internal class ThreadRipperPlayUrlAccess private constructor(
    val replyClass: Class<*>,
    private val defaultReply: Any,
    private val hasInfo: Method,
    private val readInfo: Method,
    private val writeInfo: Method,
    private val infoPlan: ProtobufBuilderPlan,
    private val streams: Repeated?,
    private val streamDash: Nested?,
    private val dashAudio: Repeated?,
    private val dolby: Nested?,
    private val dolbyAudio: Repeated?,
    private val lossless: Nested?,
    private val losslessAudio: Nested?,
    private val videoUrls: UrlAccess?,
    private val audioUrls: UrlAccess?,
) {
    /** 地址改写函数：返回 null 表示这一路不改。 */
    fun interface Rewriter {
        /** @param size 响应里下发的文件字节数；没有该字段或为 0 时为 0 */
        fun rewrite(primary: String, backups: List<String>, audio: Boolean, size: Long): Pair<String, List<String>>?
    }

    /** `getXxx(int)` / `getXxxCount()` 与 builder 的 `setXxx(int, T)`。 */
    private class Repeated(val get: Method, val count: Method, val set: Method, val plan: ProtobufBuilderPlan?) {
        val elementType: Class<*> get() = get.returnType
    }

    /** `hasXxx()` / `getXxx()` 与 builder 的 `setXxx(T)`。 */
    private class Nested(val has: Method?, val get: Method, val set: Method, val plan: ProtobufBuilderPlan) {
        fun present(owner: Any): Boolean = has?.invoke(owner) != false
    }

    private class UrlAccess(
        val plan: ProtobufBuilderPlan,
        val baseUrl: Method,
        val backupUrls: Method,
        val setBaseUrl: Method,
        val clearBackupUrl: Method,
        val addAllBackupUrl: Method,
        val size: Method?,
    ) {
        /** @return 改写后的新消息；无变化时为 null */
        fun rewrite(message: Any, audio: Boolean, rewriter: Rewriter): Any? {
            val primary = baseUrl.invoke(message) as? String ?: return null
            if (primary.isEmpty()) return null
            val backups = (backupUrls.invoke(message) as? List<*>).orEmpty().filterIsInstance<String>()
            val bytes = (size?.invoke(message) as? Number)?.toLong()?.takeIf { it > 0 } ?: 0L
            val (nextPrimary, nextBackups) = rewriter.rewrite(primary, backups, audio, bytes) ?: return null
            if (nextPrimary == primary && nextBackups == backups) return null
            return plan.edit(message) { builder ->
                setBaseUrl.invoke(builder, nextPrimary)
                clearBackupUrl.invoke(builder)
                addAllBackupUrl.invoke(builder, nextBackups)
            }
        }

        companion object {
            fun resolve(owner: Class<*>): UrlAccess? {
                val plan = ProtobufBuilderPlan.resolve(owner) ?: return null
                return UrlAccess(
                    plan,
                    method(owner, "getBaseUrl")?.takeIf { it.returnType == classOf<String>() } ?: return null,
                    method(owner, "getBackupUrlList")?.takeIf { List::class.java.isAssignableFrom(it.returnType) } ?: return null,
                    plan.method("setBaseUrl", classOf<String>()) ?: return null,
                    plan.method("clearBackupUrl") ?: return null,
                    plan.method("addAllBackupUrl", Iterable::class.java) ?: return null,
                    method(owner, "getSize")?.takeIf { it.returnType == classOf<Long>() || it.returnType == classOf<Int>() },
                )
            }
        }
    }

    /** 本路线可改写的部位数量，用于覆盖率诊断。 */
    val slotCount: Int get() = listOfNotNull(streamDash, dashAudio, dolbyAudio, losslessAudio).size

    /**
     * 原地改写一个响应。
     * @return 实际改写并读回一致的流数量；0 包括默认响应、无视频信息、没有可改地址
     */
    fun apply(reply: Any, rewriter: Rewriter): Int {
        if (!replyClass.isInstance(reply) || reply === defaultReply || hasInfo.invoke(reply) != true) return 0
        val info = readInfo.invoke(reply) ?: return 0
        val edits = ArrayList<(Any) -> Unit>()
        var changed = 0

        if (streams != null && streamDash != null && videoUrls != null && streams.plan != null) {
            val streamPlan = streams.plan
            for (index in 0 until (streams.count.invoke(info) as Int)) {
                val stream = streams.get.invoke(info, index) ?: continue
                if (!streamDash.present(stream)) continue
                val dash = streamDash.get.invoke(stream) ?: continue
                val updated = videoUrls.rewrite(dash, audio = false, rewriter) ?: continue
                val nextStream = streamPlan.edit(stream) { streamDash.set.invoke(it, updated) }
                edits += { builder -> streams.set.invoke(builder, index, nextStream) }
                changed++
            }
        }
        if (dashAudio != null && audioUrls != null) {
            for (index in 0 until (dashAudio.count.invoke(info) as Int)) {
                val item = dashAudio.get.invoke(info, index) ?: continue
                val updated = audioUrls.rewrite(item, audio = true, rewriter) ?: continue
                edits += { builder -> dashAudio.set.invoke(builder, index, updated) }
                changed++
            }
        }
        if (dolby != null && dolbyAudio != null && audioUrls != null && dolby.present(info)) {
            val item = dolby.get.invoke(info)
            if (item != null) {
                val sets = ArrayList<(Any) -> Unit>()
                for (index in 0 until (dolbyAudio.count.invoke(item) as Int)) {
                    val audio = dolbyAudio.get.invoke(item, index) ?: continue
                    val updated = audioUrls.rewrite(audio, audio = true, rewriter) ?: continue
                    sets += { builder -> dolbyAudio.set.invoke(builder, index, updated) }
                    changed++
                }
                if (sets.isNotEmpty()) {
                    val nextDolby = dolby.plan.edit(item) { builder -> sets.forEach { it(builder) } }
                    edits += { builder -> dolby.set.invoke(builder, nextDolby) }
                }
            }
        }
        if (lossless != null && losslessAudio != null && audioUrls != null && lossless.present(info)) {
            val item = lossless.get.invoke(info)
            if (item != null && losslessAudio.present(item)) {
                val audio = losslessAudio.get.invoke(item)
                val updated = audio?.let { audioUrls.rewrite(it, audio = true, rewriter) }
                if (updated != null) {
                    val nextItem = lossless.plan.edit(item) { losslessAudio.set.invoke(it, updated) }
                    edits += { builder -> lossless.set.invoke(builder, nextItem) }
                    changed++
                }
            }
        }
        if (edits.isEmpty()) return 0
        val nextInfo = infoPlan.edit(info) { builder -> edits.forEach { it(builder) } }
        writeInfo.invoke(reply, nextInfo)
        check(readInfo.invoke(reply) === nextInfo)
        return changed
    }

    companion object {
        /**
         * @param infoName 响应里视频信息字段的驼峰名：新链路 `VodInfo`，旧链路 `VideoInfo`
         */
        fun resolve(loader: ClassLoader, replyName: String, infoName: String): ThreadRipperPlayUrlAccess? = runCatching {
            val reply = KavaMemberLookup.classOrNull(loader, replyName) ?: return null
            val readInfo = method(reply, "get$infoName") ?: return null
            val info = readInfo.returnType
            val writeInfo = method(reply, "set$infoName", info)?.takeIf { it.returnType == Void.TYPE } ?: return null
            val hasInfo = method(reply, "has$infoName")?.takeIf { it.returnType == classOf<Boolean>() } ?: return null
            val infoPlan = ProtobufBuilderPlan.resolve(info) ?: return null

            val streams = repeated(info, infoPlan, "StreamList")
            val streamDash = streams?.let { nested(it.elementType, "DashVideo") }
            val dashAudio = repeated(info, infoPlan, "DashAudio")
            val dolby = nested(info, "Dolby", infoPlan)
            val dolbyAudio = dolby?.let { repeated(it.get.returnType, it.plan, "Audio") }
            val lossless = nested(info, "LossLessItem", infoPlan)
            val losslessAudio = lossless?.let { nested(it.get.returnType, "Audio", it.plan) }
            val videoUrls = streamDash?.let { UrlAccess.resolve(it.get.returnType) }
            val audioType = dashAudio?.elementType ?: dolbyAudio?.elementType ?: losslessAudio?.get?.returnType
            val audioUrls = audioType?.let(UrlAccess::resolve)
            if (videoUrls == null && audioUrls == null) return null
            val defaultReply = KavaMemberLookup.methodOrNull(reply, "getDefaultInstance")
                ?.takeIf { it.isStatic && it.returnType == reply }?.invoke(null) ?: return null
            ThreadRipperPlayUrlAccess(
                reply, defaultReply, hasInfo, readInfo, writeInfo, infoPlan,
                streams, streamDash, dashAudio, dolby, dolbyAudio, lossless, losslessAudio, videoUrls, audioUrls,
            )
        }.getOrNull()

        private fun repeated(owner: Class<*>, ownerPlan: ProtobufBuilderPlan, field: String): Repeated? {
            val get = method(owner, "get$field", classOf<Int>())?.takeIf { !it.returnType.isPrimitive } ?: return null
            val count = method(owner, "get${field}Count")?.takeIf { it.returnType == classOf<Int>() } ?: return null
            val set = ownerPlan.method("set$field", classOf<Int>(), get.returnType) ?: return null
            return Repeated(get, count, set, ProtobufBuilderPlan.resolve(get.returnType))
        }

        private fun nested(owner: Class<*>, field: String, ownerPlan: ProtobufBuilderPlan? = null): Nested? {
            val get = method(owner, "get$field")?.takeIf { !it.returnType.isPrimitive && it.returnType != classOf<String>() } ?: return null
            val has = method(owner, "has$field")?.takeIf { it.returnType == classOf<Boolean>() }
            val plan = ProtobufBuilderPlan.resolve(get.returnType) ?: return null
            val setterOwner = ownerPlan ?: ProtobufBuilderPlan.resolve(owner) ?: return null
            val set = setterOwner.method("set$field", get.returnType) ?: return null
            return Nested(has, get, set, plan)
        }

        private fun method(owner: Class<*>, name: String, vararg parameters: Class<*>): Method? =
            KavaMemberLookup.methodOrNull(owner, name, *parameters)?.takeIf { !it.isStatic }
    }
}
