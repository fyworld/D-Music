package com.solara.music.player

import android.media.MediaCodecList
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DtsUtil
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import java.io.IOException

/**
 * v1.4.58：裸流 DTS Extractor（自研）。
 *
 * 背景：media3 1.3.1 的 DefaultExtractorsFactory 不含裸 .dts 文件的
 * Extractor（DtsReader 只处理 TS 容器内嵌流），.dts 文件无法播放。
 * 本类实现 Extractor 接口，用公开的 DtsUtil 解析帧头，逐帧输出给
 * TrackOutput。设计参考官方 Ac3Extractor（同为裸流帧格式）。
 *
 * - sniff：peek 滑窗找 DTS 同步字（0x7FFE8001 大端 / 0x1FFFE800 14bit 变体）
 * - read：逐帧读取；首帧解析 Format；每帧一个 sample（KEY_FRAME）
 * - 时长：UnseekableSeekMap（不做精确 seek，进度条按已播时长走）
 *
 * 限制：只解析 DTS core 帧（含 14bit 变体）；DTS-HD 扩展子流跳过。
 *
 * v1.4.58 第五轮（"点击后歌曲一直循环、无提示"根因修复）：
 * ExoPlayer 对「无渲染器支持的轨道」不报错——轨道选择失败后所有渲染器
 * 禁用，renderersEnded 恒 true + Unseekable(TIME_UNSET) 时长未知 →
 * doSomeWork 直接 setState(STATE_ENDED)（静默秒结束）→ onEnded 自动
 * 切歌 → 无限循环且无任何提示。触发条件是设备没有匹配 MIME 的 DTS
 * 解码器。ExoPlayer 按 format.sampleMimeType 精确匹配 MediaCodecList，
 * 不做 MIME 别名映射——各厂商注册名不一（audio/vnd.dts / audio/dts /
 * audio/x-dts），写死任何一个都可能匹配不上。修复：首帧 format 时
 * 运行时探测 MediaCodecList 里 DTS 解码器实际注册的 MIME，用注册名
 * 直配；无解码器回退标准 MIME（此时由 PlayerManager 的零轨道选中
 * 检测兜底提示，不再静默循环）。
 *
 * v1.4.58 第八轮（FFmpeg DTS 软解）：集成 media3 官方 decoder_ffmpeg
 * 扩展（FFmpeg 6.0 预编译静态库 + FfmpegAudioRenderer 反射加载）。
 * FFmpeg 可用时 MIME 直接用标准名（audio/vnd.dts → dca 解码器），
 * DTS 播放不再依赖设备硬件解码器——与海贝播放器同原理（CPU 软解）。
 * 硬解优先级不变：MediaCodec 能解的格式仍走硬解，FFmpeg 只做兜底。
 *
 * v1.4.58 第九轮（进度条/时长/seek/自动切歌修复）：原 Unseekable
 * (TIME_UNSET)（时长未知+不可 seek）导致 UI 无总时长、进度条不动、
 * 不能拖动、播完不触发 STATE_ENDED 自动切歌。DTS core 是 CBR（帧
 * 大小/帧时长恒定），按官方 ConstantBitrateSeekMap 思路自研可 seek
 * 的 SeekMap：首帧解析出 frameSize/帧时长后，用 input.length 换算
 * 总时长；seek 定位 = 时间戳 × 每字节时间 + 帧内余数对齐帧边界。
 *
 * v1.4.58 第十轮（seek 跳回开头 + 播完不切歌修复）：
 * ① SeekPoint 构造参数顺序反了——签名是 SeekPoint(timeUs, position)，
 *   原实现传成 (position, timeUs)：seek 时 startLoading 拿 first.position
 *   当字节偏移去 FileDataSource.open，实际拿到的是帧时间微秒值（远超
 *   文件长度）→ FileDataSourceException(2008) 超界 → PlayerError →
 *   onPlayerError 原地重试 playAt → 从头重播（"拖到位置停一下跳回开头"）。
 * ② 尾部截断帧/残余字节：peekFully/readFully 部分读取后 EOF 会抛
 *   EOFException（allowEndOfInput 只救"一字节都没读到"），抛到
 *   ExtractingLoadable 被当作加载错误 → 重试 → PlayerError → playAt
 *   从头播，而非 RESULT_END_OF_INPUT → STATE_ENDED → onEnded 切歌
 *   （"播完不切歌"）。修复：read 内捕获 EOFException 优雅结束。
 * ③ 首帧字节偏移：文件可能带前导数据（ID3/WAV 头），官方
 *   ConstantBitrateSeekMap 有 firstFrameBytePosition 字段——记录首帧
 *   实际字节位置，durationUs 与 seek 偏移都从首帧起算，否则 durationUs
 *   偏大（前导字节被算成帧时间，ENDED 判定 durationUs<=positionUs
 *   可能永不满足）且 seek 落点错位。
 */
@UnstableApi
class DtsExtractor : Extractor {

    companion object {
        // DTS 同步字 4 种变体（与 DtsUtil 私有常量一致，反编译核对）：
        // 16bit 大端 / 16bit 小端 / 14bit 大端 / 14bit 小端
        private const val SYNC_VALUE_BE = 0x7FFE8001      // 2147385345
        private const val SYNC_VALUE_LE = -25230976       // 0xFE7F0180 小端
        private const val SYNC_VALUE_14B_BE = 536864768   // 0x1FFFE800
        private const val SYNC_VALUE_14B_LE = -14745368   // 0xFF1F00E8 小端
        /** sniff 最多扫描字节数。 */
        private const val MAX_SNIFF_BYTES = 8192
        /** 单帧最大尺寸。 */
        private const val MAX_FRAME_SIZE = 8192
        /** 帧头解析所需字节数（同步字 + 帧参数区，DtsUtil 解析上限）。 */
        private const val HEADER_SIZE = 18

        /** 判定是否 DTS 同步字（4 种变体）。 */
        private fun isDtsSync(word: Int): Boolean =
            word == SYNC_VALUE_BE || word == SYNC_VALUE_LE ||
                word == SYNC_VALUE_14B_BE || word == SYNC_VALUE_14B_LE

        /**
         * v1.4.58：探测本机 DTS 解码器实际注册的 MIME。
         * MediaCodecList 遍历所有 audio 解码器，逐个试三个候选 MIME
         * （audio/vnd.dts 标准名 / audio/dts / audio/x-dts 常见厂商名），
         * 谁能解就用谁注册的名字。都不行返回 null（无解码器）。
         * 结果进程内缓存（MediaCodecList 只读，注册表不变）。
         */
        @Volatile private var cachedDecoderMime: String? = null
        @Volatile private var decoderMimeProbed = false

        fun probeDtsDecoderMime(): String? {
            if (decoderMimeProbed) return cachedDecoderMime
            val candidates = listOf(
                MimeTypes.AUDIO_DTS,      // audio/vnd.dts（media3 标准名）
                "audio/dts",
                "audio/x-dts"
            )
            var found: String? = null
            runCatching {
                val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                outer@ for (info in list.codecInfos) {
                    if (!info.isEncoder) continue
                    for (type in info.supportedTypes) {
                        for (cand in candidates) {
                            if (type.equals(cand, ignoreCase = true)) {
                                found = cand
                                break@outer
                            }
                        }
                    }
                }
            }
            cachedDecoderMime = found
            decoderMimeProbed = true
            return found
        }

        /**
         * v1.4.58 第八轮：FFmpeg 软解是否可用（native 库加载成功且
         * 带 dts 解码器）。可用时 DTS 播放不依赖设备硬件解码器。
         */
        fun ffmpegSoftDecodeAvailable(): Boolean =
            runCatching {
                androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable() &&
                    androidx.media3.decoder.ffmpeg.FfmpegLibrary
                        .supportsFormat(MimeTypes.AUDIO_DTS)
            }.getOrDefault(false)
    }

    private lateinit var trackOutput: androidx.media3.extractor.TrackOutput
    private var formatSet = false
    private val headerScratch = ParsableByteArray(HEADER_SIZE)
    private val frameBuffer = ParsableByteArray(MAX_FRAME_SIZE)

    override fun sniff(input: ExtractorInput): Boolean {
        // peek 滑窗：逐字节推进，窗口内 4 字节匹配任一同步字变体即命中
        val scratch = ParsableByteArray(4)
        var sync = 0
        var bytesPeeked = 0
        while (bytesPeeked < MAX_SNIFF_BYTES) {
            if (!input.peekFully(scratch.data, 0, 1, true)) return false
            sync = ((sync and 0x00FFFFFF) shl 8) or (scratch.data[0].toInt() and 0xFF)
            bytesPeeked++
            if (bytesPeeked >= 4 && isDtsSync(sync)) {
                input.resetPeekPosition()
                return true
            }
        }
        input.resetPeekPosition()
        return false
    }

    override fun init(output: ExtractorOutput) {
        trackOutput = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
        extractorOutput = output
        // 第九轮：SeekMap 延迟到首帧上报（官方 AdtsExtractor 模式——
        // maybeOutputSeekMap 二选一：CBR 参数齐 + 长度已知报 DtsSeekMap，
        // 否则 Unseekable。init 不预占位，避免「先 Unseekable 后升级」
        // 触发 ProgressiveMediaPeriod 的 durationUs 重算竞态）
    }

    override fun seek(position: Long, timeUs: Long) {
        // 第九轮：seek 后从目标位置重新找同步字；时间戳按 seek 目标时间
        // 重置（原实现重置 0——seek 到中段后时间轴错乱，进度条跳回开头）
        formatSet = false
        currentTimestampUs = timeUs
    }

    override fun release() {
        // 无资源需释放
    }

    @Throws(IOException::class)
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        // 1. peek 4 字节判同步字（不消费）——不匹配则消费 1 字节滑窗前进。
        //    第十轮②：尾部截断帧/残余字节时 peek 不足 4 字节会抛
        //    EOFException（peekFully(…, allowEndOfInput=true) 只在"一字节
        //    都没读到"时返回 false，部分读取仍抛）——必须捕获并优雅结束，
        //    否则被 ExtractingLoadable 当加载错误 → 重试 → PlayerError →
        //    playAt 从头播（"播完不切歌"根因）。
        try {
            if (!input.peekFully(syncScratch.data, 0, 4, true)) {
                return Extractor.RESULT_END_OF_INPUT
            }
        } catch (e: java.io.EOFException) {
            return Extractor.RESULT_END_OF_INPUT
        }
        syncScratch.setPosition(0)
        val sync = syncScratch.readInt()
        if (!isDtsSync(sync)) {
            input.skipFully(1)
            return Extractor.RESULT_CONTINUE
        }

        // 2. 同步字命中：读帧头（18 字节，消费）。尾部不足 18 字节 = 截断
        //    帧，同样优雅结束（见第十轮②）
        try {
            if (!input.readFully(headerScratch.data, 0, HEADER_SIZE, true)) {
                return Extractor.RESULT_END_OF_INPUT
            }
        } catch (e: java.io.EOFException) {
            return Extractor.RESULT_END_OF_INPUT
        }
        headerScratch.setPosition(0)
        headerScratch.readInt() // 跳过同步字（已验证）

        // 3. 首帧解析 Format（采样率/声道/帧率），缓存采样率供时间戳计算。
        //    MIME 选择策略（v1.4.58 第八轮）：
        //    - FFmpeg 软解可用 → 直接用标准 MIME（audio/vnd.dts）——
        //      FfmpegLibrary.getCodecName 把它映射到 dca 解码器，
        //      不依赖设备硬件解码器（海贝播放器同原理）
        //    - 无 FFmpeg → 运行时探测硬件解码器注册名直配（第五轮方案，
        //      兜底有硬解的设备）；都没有则零轨道检测兜底提示
        if (!formatSet) {
            val mimeType = if (ffmpegSoftDecodeAvailable()) {
                MimeTypes.AUDIO_DTS
            } else {
                probeDtsDecoderMime() ?: MimeTypes.AUDIO_DTS
            }
            val format = try {
                DtsUtil.parseDtsFormat(
                    headerScratch.data, "dts", null, Format.NO_VALUE, null
                ).buildUpon().setSampleMimeType(mimeType).build()
            } catch (e: Exception) {
                throw IOException("DTS 帧头解析失败", e)
            }
            trackOutput.format(format)
            cachedSampleRate = format.sampleRate
            formatSet = true
            // 第九轮：首帧成功 → CBR 参数齐了（frameSize/帧时长），升级
            // SeekMap 为可 seek + 精确时长（文件长度已知时）
            maybeUpgradeSeekMap(input)
        }

        // 4. 帧大小（DtsUtil 按帧头参数计算）；坏帧跳 1 字节重新找同步
        val frameSize = DtsUtil.getDtsFrameSize(headerScratch.data)
        if (frameSize < HEADER_SIZE || frameSize > MAX_FRAME_SIZE) {
            input.skipFully(1)
            return Extractor.RESULT_CONTINUE
        }

        // 5. 读整帧剩余部分（前 18 字节已在 headerScratch）。尾部不足 =
        //    截断帧，优雅结束（见第十轮②）
        val remaining = frameSize - HEADER_SIZE
        if (remaining > 0) {
            try {
                if (!input.readFully(frameBuffer.data, 0, remaining, true)) {
                    return Extractor.RESULT_END_OF_INPUT
                }
            } catch (e: java.io.EOFException) {
                return Extractor.RESULT_END_OF_INPUT
            }
        }

        // 6. 输出：先头 18 字节再剩余部分（ParsableByteArray 重载）
        headerScratch.setPosition(0)
        trackOutput.sampleData(headerScratch, HEADER_SIZE)
        if (remaining > 0) {
            frameBuffer.setPosition(0)
            trackOutput.sampleData(frameBuffer, remaining)
        }
        // 时间戳：先输出当前帧起始时间，再按帧时长（512 样本/采样率）累积
        trackOutput.sampleMetadata(
            currentTimestampUs, C.BUFFER_FLAG_KEY_FRAME, frameSize, 0, null
        )
        val sampleCount = DtsUtil.parseDtsAudioSampleCount(headerScratch.data)
        val sampleRate = if (cachedSampleRate > 0) cachedSampleRate else 44100
        currentTimestampUs += sampleCount * 1_000_000L / sampleRate
        return Extractor.RESULT_CONTINUE
    }

    /**
     * 第九轮：首帧成功后上报 SeekMap（官方 AdtsExtractor.maybeOutputSeekMap 模式）。
     * DTS core 是 CBR：帧大小恒定、帧时长恒定（sampleCount/sampleRate）。
     * 文件长度已知时报 DtsSeekMap（可 seek + 精确时长）；否则（流式）
     * 报 Unseekable。必须在 trackOutput.format 之后调用（SeekMap 上报
     * 会触发 maybeFinishPrepare，Format 已上报才不丢）。
     * 第十轮③：记录首帧实际字节位置（文件可能带 ID3/WAV 头等前导数据），
     * durationUs 与 seek 偏移都从首帧起算。
     */
    private fun maybeUpgradeSeekMap(input: ExtractorInput) {
        if (seekMapUpgraded) return
        seekMapUpgraded = true
        // 首帧字节位置 = 当前读取位置 - 已读的 18 字节帧头
        firstFramePosition = input.position - HEADER_SIZE
        val length = input.length
        if (length == C.LENGTH_UNSET.toLong() || length <= 0) {
            extractorOutput?.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
            return
        }
        val sampleCount = DtsUtil.parseDtsAudioSampleCount(headerScratch.data)
        val frameSize = DtsUtil.getDtsFrameSize(headerScratch.data)
        if (sampleCount <= 0 || frameSize <= 0) {
            extractorOutput?.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
            return
        }
        val sampleRate = if (cachedSampleRate > 0) cachedSampleRate else 44100
        val frameDurationUs = sampleCount * 1_000_000L / sampleRate
        extractorOutput?.seekMap(
            DtsSeekMap(length, firstFramePosition, frameSize, frameDurationUs)
        )
    }

    /** SeekMap 是否已升级（每实例一次）。 */
    private var seekMapUpgraded = false

    /** init 时保存的 ExtractorOutput（升级 SeekMap 用）。 */
    private var extractorOutput: ExtractorOutput? = null

    /** 首帧实际字节位置（第十轮③：前导数据存在时非 0）。 */
    private var firstFramePosition = 0L

    /** 当前帧起始时间戳（逐帧累积）。 */
    private var currentTimestampUs = 0L

    /** 首帧解析出的采样率（时间戳计算用）。 */
    private var cachedSampleRate = 0

    /** 同步字 peek 缓冲。 */
    private val syncScratch = ParsableByteArray(4)

    /**
     * 第九轮：DTS CBR SeekMap（参考官方 ConstantBitrateSeekMap）。
     * - 总时长：数据字节数（文件长度 - 首帧偏移）/ 帧大小 × 帧时长
     * - seek：时间戳 → 帧序号 → 字节偏移（首帧偏移 + 帧序号 × 帧大小）
     *
     * 第十轮①：SeekPoint 构造签名是 SeekPoint(timeUs, position)——
     * 第一个参数是时间戳、第二个是字节位置。原实现传反了 (position,
     * timeUs)：startLoading 拿 first.position 当字节偏移去
     * FileDataSource.open，实际拿到帧时间微秒值（远超文件长度）→
     * FileDataSourceException(2008) 超界 → PlayerError → onPlayerError
     * 原地重试 playAt → 从头重播。这就是"拖到位置停一下就跳回开头"。
     */
    private class DtsSeekMap(
        private val streamLength: Long,
        private val firstFramePosition: Long,
        private val frameSize: Int,
        private val frameDurationUs: Long
    ) : SeekMap {

        override fun isSeekable(): Boolean = true

        /** 数据字节数（前导数据不算帧）。 */
        private fun dataBytes(): Long =
            (streamLength - firstFramePosition).coerceAtLeast(0L)

        override fun getDurationUs(): Long =
            dataBytes() / frameSize * frameDurationUs

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val durationUs = getDurationUs()
            val targetUs = timeUs.coerceIn(0L, durationUs)
            // 时间戳 → 帧序号 → 字节偏移（对齐帧边界，从首帧起算）
            val frameIndex = targetUs / frameDurationUs
            val position = firstFramePosition + frameIndex * frameSize
            // 第十轮①：SeekPoint(timeUs, position)——时间在前、字节在后！
            val point = SeekPoint(frameIndex * frameDurationUs, position)
            return SeekMap.SeekPoints(point)
        }
    }
}

/**
 * v1.4.58：ExtractorsFactory 包装——DTS 裸流 Extractor 置于
 * DefaultExtractorsFactory 之前（DTS 同步字与 mp3/flac 等无冲突，
 * sniff 失败自动落到默认列表）。
 */
@UnstableApi
class DtsExtractorFactory : androidx.media3.extractor.ExtractorsFactory {
    private val default = androidx.media3.extractor.DefaultExtractorsFactory()

    override fun createExtractors(): Array<Extractor> {
        val defaults = default.createExtractors()
        // Kotlin 对 Java 泛型数组拼接需显式构造（Array<Extractor!> 不型变）
        val result = arrayOfNulls<Extractor>(defaults.size + 1)
        result[0] = DtsExtractor()
        System.arraycopy(defaults, 0, result, 1, defaults.size)
        @Suppress("UNCHECKED_CAST")
        return result as Array<Extractor>
    }
}
