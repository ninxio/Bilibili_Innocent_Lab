package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.ThreadRipperPlayUrlAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ThreadRipperCdnPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import threadripperstub.DashItem
import threadripperstub.DashVideo
import threadripperstub.DolbyItem
import threadripperstub.LossLessItem
import threadripperstub.PlayViewUniteReply
import threadripperstub.Stream
import threadripperstub.VodInfo
import java.net.URI

class ThreadRipperPlayUrlAccessTest {
    private fun mediaUrl(host: String, id: Int) = "https://$host/upgcxcode/00/00/1/1-1-$id.m4s?deadline=1&os=x"
    private val ali = "upos-sz-mirrorali.bilivideo.com"
    private val cosov = "upos-sz-mirrorcosov.bilivideo.com"

    private fun access() = requireNotNull(
        ThreadRipperPlayUrlAccess.resolve(javaClass.classLoader!!, PlayViewUniteReply::class.java.name, "VodInfo")
    )

    private fun reply(): PlayViewUniteReply = PlayViewUniteReply(VodInfo(
        streams = listOf(Stream(DashVideo(mediaUrl(cosov, 30080), listOf(mediaUrl(ali, 30080)), size = 4321)), Stream(null, 16)),
        audios = listOf(DashItem(mediaUrl(cosov, 30280), emptyList(), 30280)),
        dolby = DolbyItem(listOf(DashItem(mediaUrl(cosov, 30250), emptyList(), 30250))),
        lossless = LossLessItem(DashItem(mediaUrl(cosov, 30251), emptyList(), 30251)),
    ))

    @Test fun `every DASH stream is rewritten on copies and unrelated fields survive`() {
        val reply = reply()
        val original = reply.getVodInfo()
        val originalDash = original.getStreamList(0).getDashVideo()
        val seen = ArrayList<Pair<String, Boolean>>()
        val sizes = ArrayList<Long>()
        val changed = access().apply(reply) { primary, backups, audio, size ->
            seen += primary to audio
            sizes += size
            "LOCAL-$primary" to listOf(primary) + backups
        }
        assertEquals("video + audio + dolby + lossless", 4, changed)
        assertEquals(listOf(false, true, true, true), seen.map { it.second })
        assertEquals("the declared DASH size reaches the rewriter", listOf(4321L, 0L, 0L, 0L), sizes)
        val info = reply.getVodInfo()
        assertNotSame(original, info)
        val dash = info.getStreamList(0).getDashVideo()
        assertEquals("LOCAL-${mediaUrl(cosov, 30080)}", dash.getBaseUrl())
        assertEquals(listOf(mediaUrl(cosov, 30080), mediaUrl(ali, 30080)), dash.getBackupUrlList())
        assertEquals("keep", dash.extra)
        assertSame("a segment-only stream is left alone", original.getStreamList(1), info.getStreamList(1))
        assertEquals(30280, info.getDashAudio(0).id)
        assertTrue(info.getDashAudio(0).getBaseUrl().startsWith("LOCAL-"))
        assertTrue(info.getDolby().getAudio(0).getBaseUrl().startsWith("LOCAL-"))
        assertTrue(info.getLossLessItem().getAudio().getBaseUrl().startsWith("LOCAL-"))
        // 原消息不被修改。
        assertEquals(mediaUrl(cosov, 30080), originalDash.getBaseUrl())
    }

    @Test fun `default and video-less replies, and a rewriter that changes nothing, leave the reply as it is`() {
        val access = access()
        val rewrite = ThreadRipperPlayUrlAccess.Rewriter { primary, backups, _, _ -> "x$primary" to backups }
        assertEquals(0, access.apply(PlayViewUniteReply.getDefaultInstance(), rewrite))
        assertEquals(0, access.apply(PlayViewUniteReply(), rewrite))
        val reply = reply()
        val before = reply.getVodInfo()
        assertEquals(0, access.apply(reply) { _, _, _, _ -> null })
        assertSame(before, reply.getVodInfo())
        assertEquals(0, access.apply(Any(), rewrite))
    }

    @Test fun `the runtime sends playback through the proxy with CDN and original addresses as backups`() {
        val proxy = ThreadRipperProxyServer(ThreadRipperDownloader(HttpUrlFetcher, { ThreadRipperSettings() }))
        val runtime = ThreadRipperRuntime(ThreadRipperSettings(mode = Mode.MAINLAND), multithread = true, proxyFactory = { proxy })
        try {
            val reply = reply()
            assertEquals(4, access().apply(reply, runtime.rewriterForReply()))
            val dash = reply.getVodInfo().getStreamList(0).getDashVideo()
            assertTrue(dash.getBaseUrl().startsWith("http://127.0.0.1:${proxy.port}/btr/"))
            val backups = dash.getBackupUrlList()
            assertEquals(ali, URI(backups[0]).host)
            assertTrue(mediaUrl(cosov, 30080) in backups)
            assertTrue(backups.size <= ThreadRipperRuntime.MAX_BACKUPS)
            // 已经是代理地址的不再改写。
            assertEquals(0, access().apply(reply, runtime.rewriterForReply()))
        } finally {
            proxy.stop()
        }
    }

    @Test fun `without multithreading only the CDN node changes and the original address stays last`() {
        val runtime = ThreadRipperRuntime(ThreadRipperSettings(mode = Mode.MAINLAND), multithread = false)
        val bans = ThreadRipperBanList()
        val (primary, backups) = assertNotNullPair(runtime.rewrite(mediaUrl(cosov, 30080), emptyList(), false, bans))
        assertTrue(URI(primary).host in ThreadRipperCdnPolicy.MAINLAND_HOSTS)
        assertEquals(mediaUrl(cosov, 30080), backups.last())
        assertTrue(backups.size <= ThreadRipperRuntime.MAX_BACKUPS)
        assertEquals("non-Bilibili addresses are never touched", null,
            runtime.rewrite("https://example.com/a.m4s", emptyList(), false, bans))
    }

    @Test fun `a proxy that cannot start falls back to switching CDN nodes`() {
        val runtime = ThreadRipperRuntime(ThreadRipperSettings(), multithread = true, proxyFactory = { error("bind failed") })
        val (primary, _) = assertNotNullPair(runtime.rewrite(mediaUrl(cosov, 30080), emptyList(), false, ThreadRipperBanList()))
        assertTrue(primary.startsWith("https://"))
    }

    private fun <A, B> assertNotNullPair(value: Pair<A, B>?): Pair<A, B> {
        assertNotNull(value)
        return value!!
    }
}
