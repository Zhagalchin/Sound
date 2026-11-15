package io.github.zhagalchin.evensound.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import io.github.zhagalchin.evensound.dsp.EngineSettings
import io.github.zhagalchin.evensound.dsp.LevelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Проверяет стыковку с ExoPlayer: 5.1 PCM 16 бит на входе → стерео на выходе. */
class LevelingAudioProcessorTest {

    @Test
    fun surroundIsDownmixedToStereoAndNothingIsLost() {
        val processor = LevelingAudioProcessor()
        processor.settings = EngineSettings(LevelMode.OFF, 0f)
        val out = processor.configure(AudioFormat(48000, 6, C.ENCODING_PCM_16BIT))
        assertEquals(2, out.channelCount)
        assertEquals(48000, out.sampleRate)
        assertTrue(processor.isActive)
        processor.flush()
        assertEquals(6, processor.sourceChannels)

        val frames = 48000
        var outFrames = 0
        var peak = 0
        var done = 0
        while (done < frames) {
            val n = minOf(1024, frames - done)
            val input = ByteBuffer.allocateDirect(n * 6 * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until n) {
                val centre = (8000 * sin(2 * PI * 440 * (done + i) / 48000)).toInt().toShort()
                for (ch in 0 until 6) input.putShort(if (ch == 2) centre else 0)
            }
            input.flip()
            processor.queueInput(input)
            assertFalse(input.hasRemaining())
            val o = processor.output
            while (o.hasRemaining()) {
                val l = o.getShort().toInt()
                val r = o.getShort().toInt()
                assertEquals(l, r) // центр попадает в оба канала поровну
                peak = maxOf(peak, abs(l))
                outFrames++
            }
            done += n
        }
        processor.queueEndOfStream()
        val tail = processor.output
        outFrames += tail.remaining() / 4
        assertTrue(processor.isEnded)

        // центр: 8000 × 0.707 (ITU) × 0.707 (запас) = 4000
        assertEquals(4000.0, peak.toDouble(), 20.0)
        // всё, что вошло, вышло (плюс хвост линии задержки)
        assertTrue(outFrames >= frames)
    }

    @Test
    fun unsupportedEncodingIsLeftAlone() {
        val processor = LevelingAudioProcessor()
        processor.configure(AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT))
        processor.flush()
        assertFalse(processor.isActive)
    }
}
