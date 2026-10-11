package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.ThreadRipperCdnPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/** 对照原项目 `dev/shared-core-test.js` 的 CDN 与封禁表用例。 */
class ThreadRipperCdnPolicyTest {
    private fun mediaUrl(host: String) = "https://$host/upgcxcode/00/00/1/1-1-30080.m4s?deadline=1&os=x"
    private fun akamaiUrl(os: String) = "https://$AKAMAI/upgcxcode/00/00/1/1-1-30080.m4s?deadline=1&os=$os"
    private fun at(host: String, os: String) = akamaiUrl(os).replace(AKAMAI, host)
    private fun hostsOf(list: List<String>) = list.map { URI(it).host }
    private fun osOf(url: String) = URI(url).rawQuery.substringAfter("os=")

    @Test fun `ranges and settings follow the original parsing rules`() {
        assertEquals(ThreadRipperCdnPolicy.ContentRange(10, 19, 100), ThreadRipperCdnPolicy.parseContentRange("bytes 10-19/100"))
        assertEquals(ThreadRipperCdnPolicy.ContentRange(10, 19, null), ThreadRipperCdnPolicy.parseContentRange("bytes 10-19/*"))
        assertNull(ThreadRipperCdnPolicy.parseContentRange("bytes 10-19/19"))
        assertNull(ThreadRipperCdnPolicy.parseContentRange("bytes 20-19/100"))
        assertEquals(ThreadRipperCdnPolicy.RequestedRange(5, null), ThreadRipperCdnPolicy.parseRangeHeader("bytes=5-"))
        assertEquals(ThreadRipperCdnPolicy.RequestedRange(5, 9), ThreadRipperCdnPolicy.parseRangeHeader("bytes=5-9"))
        assertNull(ThreadRipperCdnPolicy.parseRangeHeader("bytes=9-5"))
        assertNull(ThreadRipperCdnPolicy.parseRangeHeader("bytes=-5"))
        val pieces = ThreadRipperCdnPolicy.splitRange(0, 1_000_000 - 1, 8, 64L * 1024)
        assertEquals(8, pieces.size)
        assertEquals(1_000_000L, pieces.sumOf { it.length })
        pieces.zipWithNext { a, b -> assertEquals(a.end + 1, b.start) }
        assertEquals(1, ThreadRipperCdnPolicy.splitRange(0, 999, 8).size)
        assertEquals(Mode.MAINLAND, ThreadRipperConfig.settings(null, null, 7, false).mode)
        assertEquals(8, ThreadRipperConfig.settings("overseas", "", 7, false).concurrency)
        assertEquals(32, ThreadRipperConfig.settings("overseas", "", 32, true).concurrency)
        assertEquals(Mode.MAINLAND, Mode.of("somewhere"))
    }

    @Test fun `only Bilibili media servers are accepted, the scheme of a PCDN address included`() {
        assertTrue(ThreadRipperCdnPolicy.isBilibiliMediaUrl(mediaUrl("upos-sz-mirrorali.bilivideo.com")))
        assertTrue(ThreadRipperCdnPolicy.isBilibiliMediaUrl("http://xy1x2x3x4xy.mcdn.bilivideo.cn:4483/upgcxcode/a/b.m4s?e=1"))
        assertFalse(ThreadRipperCdnPolicy.isBilibiliMediaUrl("https://example.com/upgcxcode/a/b.m4s"))
        assertFalse(ThreadRipperCdnPolicy.isBilibiliMediaUrl("https://upos-sz-mirrorali.bilivideo.com/a/b.json"))
        assertFalse(ThreadRipperCdnPolicy.isBilibiliMediaUrl("ftp://upos-sz-mirrorali.bilivideo.com/a/b.m4s"))
    }

    @Test fun `a node is banned after two failures that delivered 0 bytes, and only for the current video`() {
        val banned = ArrayList<String>()
        val bans = ThreadRipperBanList(onBan = { host, _, _ -> banned += host })
        val hw = mediaUrl("upos-sz-mirrorhw.bilivideo.com")
        assertFalse("a cancelled hedge copy is not a failure", bans.record(hw, 0, 0, canceled = true))
        assertFalse("a transfer that sent data before stalling is not a dead node", bans.record(hw, 4096, 503, false))
        assertFalse(bans.record(hw, 0, 503, false))
        assertTrue(bans.allows(hw))
        // 同一节点上的另一个签名地址也计入该节点。
        assertTrue(bans.record(hw.replace("30080", "30280"), 0, 503, false))
        assertFalse(bans.allows(hw))
        assertEquals(listOf("upos-sz-mirrorhw.bilivideo.com"), banned)
        assertFalse("a banned node is reported once", bans.record(hw, 0, 503, false))
        assertEquals(listOf("upos-sz-mirrorhw.bilivideo.com"), bans.hosts())
        bans.reset()
        assertTrue(bans.allows(hw))
        assertTrue(bans.hosts().isEmpty())
        assertFalse("strikes start again for the next video", bans.record(hw, 0, 503, false))
    }

    @Test fun `resolvers skip banned nodes but never end up with no address`() {
        val bans = ThreadRipperBanList()
        val resolver = ThreadRipperCdnResolver(mediaUrl("upos-sz-mirrorali.bilivideo.com"), emptyList(), { Mode.MAINLAND }, bans)
        val hw = mediaUrl("upos-sz-mirrorhw.bilivideo.com")
        assertEquals(8, resolver.urls().size)
        resolver.failure(hw, 503, 0, canceled = false)
        assertEquals("one strike does not ban", 8, resolver.urls().size)
        resolver.failure(hw, 503, 0, canceled = false)
        for (list in listOf(resolver.urls(), resolver.ordered(0), resolver.rangeCandidates(), resolver.rescueCandidates(), resolver.startupCandidates())) {
            assertFalse("upos-sz-mirrorhw.bilivideo.com" in hostsOf(list))
            assertTrue(list.isNotEmpty())
        }
        assertFalse(resolver.allows(hw))
        assertEquals("banned", resolver.status().first { it.host == "upos-sz-mirrorhw.bilivideo.com" }.state)
        // 同一视频的另一路（音频、其他清晰度）共用这份封禁表。
        val audio = ThreadRipperCdnResolver(mediaUrl("upos-sz-mirrorbos.bilivideo.com"), emptyList(), { Mode.MAINLAND }, bans)
        assertFalse("upos-sz-mirrorhw.bilivideo.com" in hostsOf(audio.urls()))
        for (host in ThreadRipperCdnPolicy.MAINLAND_HOSTS) repeat(2) { bans.record(mediaUrl(host), 0, 503, false) }
        assertEquals("with every node banned the original list is used again", 8, resolver.urls().size)
        assertTrue(resolver.startupCandidates().isNotEmpty())
        val plain = ThreadRipperCdnResolver(mediaUrl("upos-sz-mirrorali.bilivideo.com"), emptyList(), { Mode.MAINLAND })
        repeat(2) { plain.failure(hw, 503, 0, canceled = false) }
        assertTrue(plain.allows(hw))
    }

    @Test fun `akamaized net addresses are host-swapped only when Bilibili hands out nothing else`() {
        val mainland = ThreadRipperCdnPolicy.representationUrls(akamaiUrl("cosovbv"), listOf(akamaiUrl("akam")), Mode.MAINLAND)
        assertEquals("two addresses on each of the eight mainland nodes", 16, mainland.size)
        assertEquals(ThreadRipperCdnPolicy.MAINLAND_HOSTS, hostsOf(mainland).distinct())
        assertEquals("node-major order covers both addresses first", listOf("cosovbv", "akam"), mainland.take(2).map(::osOf))
        val overseas = ThreadRipperCdnPolicy.representationUrls(akamaiUrl("cosovbv"), listOf(akamaiUrl("akam")), Mode.OVERSEAS)
        assertEquals("the originals stay first in overseas mode", listOf(akamaiUrl("cosovbv"), akamaiUrl("akam")), overseas.take(2))
        assertEquals(listOf(AKAMAI) + ThreadRipperCdnPolicy.OVERSEAS_HOSTS, hostsOf(overseas).distinct())
        val mixed = ThreadRipperCdnPolicy.representationUrls(akamaiUrl("akam"), listOf(mediaUrl("upos-sz-mirrorcosov.bilivideo.com")), Mode.MAINLAND)
        assertEquals(8, mixed.size)
        assertTrue(mixed.all { osOf(it) == "x" })
        assertNull(ThreadRipperCdnPolicy.swapOrdinaryHost(akamaiUrl("akam"), "upos-sz-mirrorali.bilivideo.com"))
        // PCDN 的端口不能跟着地址去别的节点；签名参数逐字保留。
        val swapped = ThreadRipperCdnPolicy.swapOrdinaryHost("http://xy1x2x3x4xy.mcdn.bilivideo.cn:4483/upgcxcode/a/b.m4s?e=1%2B2&os=mcdn", "upos-sz-mirrorali.bilivideo.com")
        assertEquals("https://upos-sz-mirrorali.bilivideo.com/upgcxcode/a/b.m4s?e=1%2B2&os=mcdn", swapped)
    }

    @Test fun `a refused address is dropped instead of the nodes it was tried on`() {
        val kinds = ArrayList<String>()
        val bans = ThreadRipperBanList(onBan = { _, _, kind -> kinds += kind })
        repeat(2) { bans.record(akamaiUrl("cosovbv"), 0, 403, false) }
        assertTrue("refused before anything delivered: nobody is blamed yet", bans.allows(akamaiUrl("cosovbv")))
        assertTrue(kinds.isEmpty())
        bans.success(akamaiUrl("akam"))
        assertFalse(bans.allowsAddress(akamaiUrl("cosovbv")))
        assertFalse("the address is dropped on every node", bans.allows(at("upos-sz-mirrorali.bilivideo.com", "cosovbv")))
        assertTrue(bans.allows(akamaiUrl("akam")))
        assertTrue(bans.hosts().isEmpty())
        assertEquals(listOf("address"), kinds)
        val lacking = "upos-sz-mirrorbos.bilivideo.com"
        bans.record(at(lacking, "akam"), 0, 403, false)
        assertTrue(bans.allows(at(lacking, "akam")))
        bans.record(at(lacking, "akam"), 0, 403, false)
        assertFalse(bans.allowsNode(at(lacking, "akam")))
        assertTrue(bans.allowsAddress(at(lacking, "akam")))
        assertEquals(listOf(lacking), bans.hosts())
        assertEquals(listOf("address", "node"), kinds)
        bans.reset()
        assertTrue(bans.allows(akamaiUrl("cosovbv")))
        assertTrue(bans.allows(at(lacking, "akam")))
    }

    @Test fun `a node that delivers one address keeps being used after refusing another that other nodes serve`() {
        val kinds = ArrayList<String>()
        val bans = ThreadRipperBanList(onBan = { _, _, kind -> kinds += kind })
        val mirror = "upos-sz-mirroraliov.bilivideo.com"
        bans.success(at(mirror, "cosovbv"))
        bans.success(at(mirror, "akam"))
        bans.success(akamaiUrl("akam"))
        repeat(2) { bans.record(akamaiUrl("cosovbv"), 0, 403, false) }
        assertFalse("that address is not asked of that node again", bans.allows(akamaiUrl("cosovbv")))
        assertTrue("the node keeps its working address", bans.allows(akamaiUrl("akam")))
        assertTrue("the address stays in use where it works", bans.allows(at(mirror, "cosovbv")))
        assertTrue(bans.hosts().isEmpty())
        assertEquals(listOf("address"), kinds)
        val resolver = ThreadRipperCdnResolver(akamaiUrl("cosovbv"), listOf(akamaiUrl("akam")), { Mode.OVERSEAS }, bans)
        assertTrue(akamaiUrl("akam") in resolver.urls())
        assertFalse(akamaiUrl("cosovbv") in resolver.urls())
        assertTrue(resolver.status().none { it.state == "banned" })
    }

    @Test fun `the custom CDN mode uses only the servers picked in the settings, and only Bilibili's`() {
        assertEquals("cn-gdfs-ct-01-01.bilivideo.com", ThreadRipperCdnPolicy.normalizeCdnHost(" https://CN-GDFS-CT-01-01.bilivideo.com:4483/x?y=1 "))
        for (bad in listOf("example.com", "bilivideo.com.example.net", "https://1.2.3.4/", "upos sz.bilivideo.com", "")) {
            assertEquals(bad, "", ThreadRipperCdnPolicy.normalizeCdnHost(bad))
        }
        val stored = ThreadRipperConfig.encodeCustomHosts(listOf("upos-sz-mirrorcos.bilivideo.com", "example.com", "UPOS-SZ-MIRRORCOS.bilivideo.com", "cn-gdfs-ct-01-01.bilivideo.com"))
        assertEquals("upos-sz-mirrorcos.bilivideo.com\ncn-gdfs-ct-01-01.bilivideo.com", stored)
        assertEquals(listOf("upos-sz-mirrorcos.bilivideo.com", "cn-gdfs-ct-01-01.bilivideo.com"), ThreadRipperConfig.decodeCustomHosts("upos-sz-mirrorcos.bilivideo.com，cn-gdfs-ct-01-01.bilivideo.com; example.com"))
        var hosts = ThreadRipperConfig.decodeCustomHosts(stored)
        val resolver = ThreadRipperCdnResolver(mediaUrl("upos-sz-mirrorali.bilivideo.com"), emptyList(), { Mode.CUSTOM }, null, { hosts })
        assertEquals(listOf("upos-sz-mirrorcos.bilivideo.com", "cn-gdfs-ct-01-01.bilivideo.com"), hostsOf(resolver.urls()))
        assertEquals("the first request does not race Bilibili's own address either",
            listOf("upos-sz-mirrorcos.bilivideo.com", "cn-gdfs-ct-01-01.bilivideo.com"), hostsOf(resolver.startupCandidates()))
        val own = ThreadRipperCdnResolver(mediaUrl("upos-sz-mirrorcos.bilivideo.com"), emptyList(), { Mode.CUSTOM }, null, { hosts })
        assertEquals(listOf("upos-sz-mirrorcos.bilivideo.com", "cn-gdfs-ct-01-01.bilivideo.com"), hostsOf(own.urls()))
        hosts = emptyList()
        assertEquals(ThreadRipperCdnPolicy.MAINLAND_HOSTS, hostsOf(resolver.urls()))
        assertEquals("upos-sz-mirrorali.bilivideo.com", hostsOf(resolver.startupCandidates()).first())
        hosts = listOf("upos-sz-mirrorbos.bilivideo.com")
        assertEquals(listOf("upos-sz-mirrorbos.bilivideo.com"), hostsOf(resolver.urls()))
    }

    @Test fun `measured nodes carry the ranges after the warm-up and stale measurements are explored again`() {
        var now = 1_000_000L
        val resolver = ThreadRipperCdnResolver(mediaUrl("upos-sz-mirrorali.bilivideo.com"), emptyList(), { Mode.MAINLAND }, clock = { now })
        val urls = resolver.urls()
        resolver.success(urls[3], 8_000_000.0)
        resolver.success(urls[5], 2_000_000.0)
        assertEquals(8, resolver.rangeCandidates().size)
        val next = resolver.rangeCandidates()
        assertEquals(listOf(urls[3], urls[5]), next.take(2))
        assertTrue("one unmeasured node rides along", next.size >= 3)
        assertEquals(8_000_000.0, resolver.speed(urls[3]), 0.1)
        now += ThreadRipperCdnResolver.MEASUREMENT_TTL_MS
        assertEquals(0.0, resolver.speed(urls[3]), 0.0)
        resolver.sample(urls[3], 4_000_000.0)
        assertEquals("a sample blends into the old speed", 8_000_000.0 * 0.65 + 4_000_000.0 * 0.35, resolver.speed(urls[3]), 0.1)
    }

    private companion object {
        const val AKAMAI = "upos-hz-mirrorakam.akamaized.net"
    }
}
