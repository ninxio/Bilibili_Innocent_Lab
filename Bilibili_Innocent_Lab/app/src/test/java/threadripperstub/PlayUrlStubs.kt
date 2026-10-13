package threadripperstub

/**
 * 形状对齐 protobuf-lite 生成代码的最小播放响应：静态 `newBuilder(source)`、builder 的 `build()`、
 * 消息自身的私有 setter 与 `getDefaultInstance()`。包名故意不同于宿主，验证访问层只靠返回类型推导。
 */
class DashVideo(private val baseUrl: String = "", private val backupUrl: List<String> = emptyList(), val extra: String = "keep", private val size: Long = 0) {
    fun getBaseUrl() = baseUrl
    fun getSize() = size
    fun getBackupUrlList() = backupUrl

    class Builder(source: DashVideo) {
        private var base = source.baseUrl
        private val backups = source.backupUrl.toMutableList()
        private val extra = source.extra
        private val size = source.size
        fun setBaseUrl(value: String) = apply { base = value }
        fun clearBackupUrl() = apply { backups.clear() }
        fun addAllBackupUrl(values: Iterable<String>) = apply { backups.addAll(values) }
        fun build() = DashVideo(base, backups.toList(), extra, size)
    }

    companion object {
        @JvmStatic fun newBuilder(source: DashVideo) = Builder(source)
    }
}

class DashItem(private val baseUrl: String = "", private val backupUrl: List<String> = emptyList(), val id: Int = 0) {
    fun getBaseUrl() = baseUrl
    fun getBackupUrlList() = backupUrl

    class Builder(source: DashItem) {
        private var base = source.baseUrl
        private val backups = source.backupUrl.toMutableList()
        private val id = source.id
        fun setBaseUrl(value: String) = apply { base = value }
        fun clearBackupUrl() = apply { backups.clear() }
        fun addAllBackupUrl(values: Iterable<String>) = apply { backups.addAll(values) }
        fun build() = DashItem(base, backups.toList(), id)
    }

    companion object {
        @JvmStatic fun newBuilder(source: DashItem) = Builder(source)
    }
}

class Stream(private val dash: DashVideo? = null, val quality: Int = 80) {
    fun hasDashVideo() = dash != null
    fun getDashVideo() = dash ?: DashVideo()

    class Builder(source: Stream) {
        private var dash = source.dash
        private val quality = source.quality
        fun setDashVideo(value: DashVideo) = apply { dash = value }
        fun build() = Stream(dash, quality)
    }

    companion object {
        @JvmStatic fun newBuilder(source: Stream) = Builder(source)
    }
}

class DolbyItem(private val audio: List<DashItem> = emptyList()) {
    fun getAudio(index: Int) = audio[index]
    fun getAudioCount() = audio.size
    fun audioList() = audio

    class Builder(source: DolbyItem) {
        private val audio = source.audio.toMutableList()
        fun setAudio(index: Int, value: DashItem) = apply { audio[index] = value }
        fun build() = DolbyItem(audio.toList())
    }

    companion object {
        @JvmStatic fun newBuilder(source: DolbyItem) = Builder(source)
    }
}

class LossLessItem(private val audio: DashItem? = null) {
    fun hasAudio() = audio != null
    fun getAudio() = audio ?: DashItem()

    class Builder(source: LossLessItem) {
        private var audio = source.audio
        fun setAudio(value: DashItem) = apply { audio = value }
        fun build() = LossLessItem(audio)
    }

    companion object {
        @JvmStatic fun newBuilder(source: LossLessItem) = Builder(source)
    }
}

class VodInfo(
    private val streams: List<Stream> = emptyList(),
    private val audios: List<DashItem> = emptyList(),
    private val dolby: DolbyItem? = null,
    private val lossless: LossLessItem? = null,
) {
    fun getStreamList(index: Int) = streams[index]
    fun getStreamListCount() = streams.size
    fun getDashAudio(index: Int) = audios[index]
    fun getDashAudioCount() = audios.size
    fun hasDolby() = dolby != null
    fun getDolby() = dolby ?: DolbyItem()
    fun hasLossLessItem() = lossless != null
    fun getLossLessItem() = lossless ?: LossLessItem()
    fun streamList() = streams
    fun audioList() = audios

    class Builder(source: VodInfo) {
        private val streams = source.streams.toMutableList()
        private val audios = source.audios.toMutableList()
        private var dolby = source.dolby
        private var lossless = source.lossless
        fun setStreamList(index: Int, value: Stream) = apply { streams[index] = value }
        fun setDashAudio(index: Int, value: DashItem) = apply { audios[index] = value }
        fun setDolby(value: DolbyItem) = apply { dolby = value }
        fun setLossLessItem(value: LossLessItem) = apply { lossless = value }
        fun build() = VodInfo(streams.toList(), audios.toList(), dolby, lossless)
    }

    companion object {
        @JvmStatic fun newBuilder(source: VodInfo) = Builder(source)
    }
}

class PlayViewUniteReply(private var vod: VodInfo? = null, val payload: Any = Any()) {
    fun hasVodInfo() = vod != null
    fun getVodInfo() = vod ?: VodInfo()
    private fun setVodInfo(value: VodInfo) {
        vod = value
    }

    companion object {
        private val DEFAULT = PlayViewUniteReply()
        @JvmStatic fun getDefaultInstance() = DEFAULT
    }
}
