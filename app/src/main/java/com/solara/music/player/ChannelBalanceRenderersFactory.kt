package com.solara.music.player

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.solara.music.data.Store

/**
 * v1.4.59：左右声道平衡——带 ChannelMixingAudioProcessor 的渲染器工厂。
 *
 * 背景：media3 1.3.1 的 ExoPlayer.setVolume(Float) 是整体音量（无左右
 * 声道独立版）。左右平衡需把 ChannelMixingAudioProcessor 注入
 * DefaultAudioSink 的处理器链（buildAudioSink 是 protected，子类覆盖）。
 *
 * DefaultAudioProcessorChain(vararg) 构造器会自动追加
 * SilenceSkippingAudioProcessor + SonicAudioProcessor（变速变调/跳静音
 * 功能保留），所以 setAudioProcessors 只传本处理器即可。
 *
 * 矩阵：立体声输入 2x2 对角阵——左声道增益 = 左侧系数，右声道增益 =
 * 右侧系数（非对角为 0，声道间无串音）。单声道输入 1x2 阵——输入
 * 同时乘左右增益（单声道源也能偏置声像）。
 *
 * 平衡值（Store.channelBalance）：0.5 居中（两侧 1.0 倍），0 全左
 * （左 1.0/右 0），1 全右。仅 App 内生效（音频处理器在 App 进程内，
 * 不碰系统音量/全局效果）。
 */
class ChannelBalanceRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    /** 声道混合处理器实例（buildAudioSink 与运行时更新共用）。 */
    val channelMixingProcessor = ChannelMixingAudioProcessor()

    init {
        // 初始矩阵按当前持久化设置应用（服务重启后恢复用户设置）
        applyBalance(Store.settings.value.channelBalance)
    }

    /**
     * 应用平衡值（0..1，0.5 居中）。设置页滑杆实时调用。
     * 矩阵按输入声道数在 onConfigure 时重配——此处先按立体声 2x2 配置
     * （绝大多数歌曲场景），单声道输入时 ChannelMixingAudioProcessor
     * 会用 1x2 矩阵（见 onConfigure 的输入声道数分支——本类不覆盖
     * onConfigure，处理器按 putChannelMixingMatrix 的矩阵工作）。
     */
    fun applyBalance(balance: Float) {
        val b = balance.coerceIn(0f, 1f)
        // v1.4.59 r14 修正：恒定功率交叉衰减（constant-power）——
        // b=0.5 居中时左右都是 1.0（等音量，原公式两侧都算成 0 导致静音）；
        // b=0 全左（左 1.0/右 0）；b=1 全右（左 0/右 1.0）。
        // 偏离中心时：本侧保持 1.0 满增益，对侧线性衰减到 0——
        // 偏左时右声道衰减（左满右弱），偏右时左声道衰减。
        val leftGain = if (b <= 0.5f) 1f else (2f - 2f * b).coerceIn(0f, 1f)
        val rightGain = if (b >= 0.5f) 1f else (2f * b).coerceIn(0f, 1f)
        // v1.5.1 r25：对角系数 1f → 0.999f——绕过 ChannelMixingMatrix 的
        // isIdentity 判定（media3 1.3.1 反编译确认：isIdentity = isDiagonal &&
        // 全对角系数==1）。居中时矩阵 [1,0,0,1] 是 identity → onConfigure
        // 返回 NOT_SET → processor 被旁路出音频链 → 之后滑杆更新矩阵也
        // 无人消费（queueInput 永不调用）——「平衡没有起作用」根因。
        // 0.999 与 1.0 听感零差异（-0.009dB），但保证 processor 永远在链上，
        // 滑杆调节实时生效。queueInput 每次从 map 现查矩阵（反编译确认），
        // 热更新天然支持。
        val lg = leftGain * 0.999f
        val rg = rightGain * 0.999f
        // 立体声 2x2 对角阵：输出左 = 输入左×lg，输出右 = 输入右×rg
        val matrix = ChannelMixingMatrix(
            /* inputChannelCount = */ 2,
            /* outputChannelCount = */ 2,
            /* mixingCoefficients = */ floatArrayOf(
                lg, 0f,   // 输出左：输入左 × lg + 输入右 × 0
                0f, rg    // 输出右：输入左 × 0 + 输入右 × rg
            )
        )
        channelMixingProcessor.putChannelMixingMatrix(matrix)
        // v1.4.59 r22：单声道 1x2 矩阵——单声道输入乘左右增益（声像可偏置）。
        // ChannelMixingAudioProcessor.onConfigure 按输入声道数查矩阵，
        // 查不到直接抛 UnhandledAudioFormatException（media3 1.3.1 反编译
        // 确认："No mixing matrix for input channel count"）——此前只注册
        // 2x2，DTS 5.1（6 声道 PCM）进 onConfigure 必抛异常 → onPlayerError
        // →「播放中断，正在重试」无限循环。修复：1-8 声道全覆盖——
        // 1/2 声道做平衡，3-8 声道注册 identity 直通矩阵（多声道不做
        // 平衡，保证播放不断流）。
        channelMixingProcessor.putChannelMixingMatrix(
            ChannelMixingMatrix(
                /* inputChannelCount = */ 1,
                /* outputChannelCount = */ 2,
                /* mixingCoefficients = */ floatArrayOf(lg, rg)
            )
        )
        for (channels in 3..8) {
            val identity = FloatArray(channels * channels) { i ->
                if (i % (channels + 1) == 0) 1f else 0f
            }
            channelMixingProcessor.putChannelMixingMatrix(
                ChannelMixingMatrix(channels, channels, identity)
            )
        }
    }

    /**
     * 覆盖 buildAudioSink：默认 sink + 注入声道混合处理器。
     * 参数透传父类（enableFloatOutput/enableAudioTrackPlaybackParams）。
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf<AudioProcessor>(channelMixingProcessor))
        .build()
}
