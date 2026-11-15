package io.github.zhagalchin.evensound.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

@UnstableApi
object PlayerFactory {

    fun create(context: Context, processor: LevelingAudioProcessor): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = PcmOnlyAudioSink(
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(processor))
                    .build()
            )
        }
            // Если на устройстве нет своего декодера (AC3, E-AC3, DTS, TrueHD) — берём FFmpeg.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val trackSelector = DefaultTrackSelector(context).apply {
            // По умолчанию на «стерео» устройствах ExoPlayer избегает многоканальных дорожек.
            // Нам они как раз нужны: в 5.1 диалоги лежат в отдельном канале.
            setParameters(
                buildUponParameters().setConstrainAudioChannelCountToDeviceCapabilities(false)
            )
        }

        return ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    /**
     * Запрещает «сквозной» вывод сжатого звука (passthrough на ТВ-приставках, ресиверах, offload):
     * в таком режиме звук уходит мимо приложения и выровнять его невозможно.
     */
    private class PcmOnlyAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
        override fun supportsFormat(format: Format): Boolean =
            format.sampleMimeType == MimeTypes.AUDIO_RAW && super.supportsFormat(format)

        override fun getFormatSupport(format: Format): Int =
            if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format)
            else AudioSink.SINK_FORMAT_UNSUPPORTED
    }
}
