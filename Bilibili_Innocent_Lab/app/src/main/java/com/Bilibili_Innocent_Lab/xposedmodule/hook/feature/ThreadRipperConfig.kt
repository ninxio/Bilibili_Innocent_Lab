package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 线程撕裂者的配置边界：设置页与宿主侧共用的取值、默认值与自定义节点编解码。
 *
 * 默认值与原项目一致：大陆 CDN、8 线程、自动线程数关闭。多线程下载默认开启——它就是原项目的核心；
 * 关掉后只做 CDN 节点替换，由宿主播放器自己单连接下载。
 */
internal object ThreadRipperConfig {
    const val DEFAULT_MULTITHREAD = true
    val DEFAULT_MODE = ThreadRipperCdnPolicy.Mode.MAINLAND
    val MODE_KEYS: Set<String> = ThreadRipperCdnPolicy.Mode.entries.map { it.key }.toSet()
    val CONCURRENCY_CHOICES: List<Int> = ThreadRipperSettings.CONCURRENCY_CHOICES
    const val DEFAULT_CONCURRENCY = ThreadRipperSettings.DEFAULT_CONCURRENCY

    /** 32 个主机名、每个最长 253 字符，加分隔符。 */
    const val MAX_CUSTOM_HOSTS_LENGTH = ThreadRipperCdnPolicy.MAX_CUSTOM_HOSTS * 254

    private val SEPARATORS = Regex("""[\s,;，；]+""")

    fun decodeCustomHosts(text: String?): List<String> =
        ThreadRipperCdnPolicy.normalizeCustomHosts(text.orEmpty().split(SEPARATORS))

    fun encodeCustomHosts(hosts: Iterable<String?>): String =
        ThreadRipperCdnPolicy.normalizeCustomHosts(hosts).joinToString("\n")

    fun settings(mode: String?, customHosts: String?, concurrency: Int, autoConcurrency: Boolean) = ThreadRipperSettings(
        mode = ThreadRipperCdnPolicy.Mode.of(mode),
        customHosts = decodeCustomHosts(customHosts),
        concurrency = ThreadRipperSettings.normalizeConcurrency(concurrency),
        autoConcurrency = autoConcurrency,
    )
}
