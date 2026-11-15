package io.github.zhagalchin.evensound.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import io.github.zhagalchin.evensound.dsp.AudioEngine
import io.github.zhagalchin.evensound.dsp.EngineSettings
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Встраивает [AudioEngine] в звуковой тракт ExoPlayer: получает декодированный PCM
 * (стерео или 5.1/7.1), отдаёт выровненное стерео.
 */
@UnstableApi
class LevelingAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var settings: EngineSettings = EngineSettings()
        set(value) {
            field = value
            engine?.settings = value
        }

    /** Текущее усиление выравнивателя, дБ. */
    val gainDb: Float get() = engine?.gainDb ?: 0f

    /** Число каналов исходной дорожки (0 — ничего не играет). */
    @Volatile
    var sourceChannels: Int = 0
        private set

    @Volatile
    private var engine: AudioEngine? = null
    private var inFloats = FloatArray(0)
    private var outFloats = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // DefaultAudioSink приводит звук к 16 бит до пользовательских процессоров.
        // Любой другой формат пропускаем нетронутым.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET
        val outChannels = if (inputAudioFormat.channelCount == 1) 1 else 2
        return AudioFormat(inputAudioFormat.sampleRate, outChannels, C.ENCODING_PCM_16BIT)
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val format = inputAudioFormat
        val current = engine
        if (current != null && current.sampleRate == format.sampleRate && current.inChannels == format.channelCount) {
            current.reset()
        } else {
            engine = AudioEngine(format.sampleRate, format.channelCount).also { it.settings = settings }
        }
        sourceChannels = format.channelCount
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val engine = engine ?: return
        val inChannels = engine.inChannels
        val frames = inputBuffer.remaining() / (2 * inChannels)
        if (frames == 0) return
        ensureCapacity(frames, engine)
        for (i in 0 until frames * inChannels) inFloats[i] = inputBuffer.getShort() * (1f / 32768f)
        processAndWrite(engine, frames)
    }

    override fun onQueueEndOfStream() {
        // Досылаем хвост, застрявший в линиях задержки.
        val engine = engine ?: return
        val frames = engine.latencyFrames
        ensureCapacity(frames, engine)
        inFloats.fill(0f, 0, frames * engine.inChannels)
        processAndWrite(engine, frames)
    }

    override fun onReset() {
        engine = null
        sourceChannels = 0
    }

    private fun ensureCapacity(frames: Int, engine: AudioEngine) {
        if (inFloats.size < frames * engine.inChannels) inFloats = FloatArray(frames * engine.inChannels)
        if (outFloats.size < frames * engine.outChannels) outFloats = FloatArray(frames * engine.outChannels)
    }

    private fun processAndWrite(engine: AudioEngine, frames: Int) {
        engine.process(inFloats, outFloats, frames)
        val n = frames * engine.outChannels
        val out = replaceOutputBuffer(n * 2)
        for (i in 0 until n) {
            out.putShort((outFloats[i] * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
        }
        out.flip()
    }
}
