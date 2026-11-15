package io.github.zhagalchin.evensound.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class AudioEngineTest {
    private val fs = 48000

    /** Стерео-синус 1 кГц с заданным RMS-уровнем. */
    private fun tone(rmsDb: Float, seconds: Double, channels: Int = 2): FloatArray {
        val amp = 10f.pow(rmsDb / 20f) * sqrt(2f)
        val n = (seconds * fs).toInt()
        return FloatArray(n * channels) { i -> amp * sin(2 * PI * 1000 * (i / channels) / fs).toFloat() }
    }

    private fun run(engine: AudioEngine, input: FloatArray): FloatArray {
        val frames = input.size / engine.inChannels
        val out = FloatArray(frames * engine.outChannels)
        // подаём кусками разного размера, как это делает плеер
        var done = 0
        val rnd = Random(1)
        while (done < frames) {
            val n = minOf(frames - done, 1 + rnd.nextInt(4096))
            val inChunk = input.copyOfRange(done * engine.inChannels, (done + n) * engine.inChannels)
            val outChunk = FloatArray(n * engine.outChannels)
            engine.process(inChunk, outChunk, n)
            outChunk.copyInto(out, done * engine.outChannels)
            done += n
        }
        return out
    }

    private fun rmsDb(x: FloatArray, fromSec: Double, toSec: Double, channels: Int = 2): Double {
        var sum = 0.0
        val a = (fromSec * fs).toInt() * channels
        val b = (toSec * fs).toInt() * channels
        for (i in a until b) sum += x[i] * x[i]
        return 10 * log10(sum / (b - a) + 1e-20)
    }

    @Test
    fun nightModeBringsQuietAndLoudToSameLevel() {
        val engine = AudioEngine(fs, 2).apply { settings = EngineSettings(LevelMode.NIGHT, 0f) }
        val input = tone(-45f, 6.0) + tone(-8f, 6.0) + tone(-45f, 6.0)
        val out = run(engine, input)
        val quiet = rmsDb(out, 5.0, 6.0)
        val loud = rmsDb(out, 11.0, 12.0)
        val quietAgain = rmsDb(out, 17.0, 18.0)
        println("NIGHT: вход −45 / −8 / −45 дБ → выход %.1f / %.1f / %.1f дБ".format(quiet, loud, quietAgain))
        assertTrue("разница была 37 дБ, стала ${loud - quiet}", loud - quiet < 5.0)
        // детектор квазипиковый, поэтому синус садится на пару дБ ниже цели
        assertEquals(Leveler.TARGET_DB.toDouble(), loud, 3.0)
        assertEquals(quiet, quietAgain, 1.0)
    }

    @Test
    fun mediumModeHalvesTheGapOrBetter() {
        val engine = AudioEngine(fs, 2).apply { settings = EngineSettings(LevelMode.MEDIUM, 0f) }
        val out = run(engine, tone(-45f, 6.0) + tone(-8f, 6.0))
        val gap = rmsDb(out, 11.0, 12.0) - rmsDb(out, 5.0, 6.0)
        println("MEDIUM: разница 37 дБ → %.1f дБ".format(gap))
        assertTrue(gap < 20.0)
        assertTrue(gap > 5.0) // но динамика не убита полностью
    }

    @Test
    fun loudOnsetIsTamedQuickly() {
        val engine = AudioEngine(fs, 2).apply { settings = EngineSettings(LevelMode.NIGHT, 0f) }
        val out = run(engine, tone(-45f, 5.0) + tone(-8f, 1.0))
        // через 150 мс после «взрыва» громкость уже в пределах 3 дБ от целевой
        val after = rmsDb(out, 5.15, 5.25)
        println("NIGHT: через 150 мс после взрыва %.1f дБ".format(after))
        assertTrue(after < Leveler.TARGET_DB + 3.0)
    }

    @Test
    fun outputNeverExceedsCeiling() {
        val rnd = Random(7)
        val engine = AudioEngine(fs, 6).apply { settings = EngineSettings(LevelMode.NIGHT, 12f) }
        val frames = fs * 4
        val input = FloatArray(frames * 6) { i ->
            val sec = (i / 6) / fs.toDouble()
            // тихо → все каналы в полную шкалу → тихо → снова
            val amp = if (sec.toInt() % 2 == 0) 0.003f else 1f
            (rnd.nextFloat() * 2 - 1) * amp
        }
        val out = run(engine, input)
        val peak = out.maxOf { abs(it) }
        println("Пик на выходе при полной шкале во всех 6 каналах: %.4f".format(peak))
        assertTrue(peak <= Limiter.CEILING + 1e-6f)
    }

    @Test
    fun silenceAndNoiseFloorAreNotPumpedUp() {
        val rnd = Random(3)
        val engine = AudioEngine(fs, 2).apply { settings = EngineSettings(LevelMode.NIGHT, 0f) }
        run(engine, tone(-30f, 4.0))
        val noiseAmp = 10f.pow(-75f / 20f)
        fun noise(sec: Int) = FloatArray(fs * 2 * sec) { (rnd.nextFloat() * 2 - 1) * noiseAmp }
        run(engine, noise(1)) // затухание детектора после речи
        val gainAtStart = engine.gainDb
        run(engine, noise(10))
        println("Усиление в начале шума −75 дБ: %.1f дБ, через 10 с: %.1f дБ".format(gainAtStart, engine.gainDb))
        assertEquals(gainAtStart, engine.gainDb, 0.01f)
        assertTrue(gainAtStart < LevelMode.NIGHT.maxBoostDb - 6f)
    }

    @Test
    fun offModeIsBitTransparentForStereo() {
        val rnd = Random(5)
        val engine = AudioEngine(fs, 2).apply { settings = EngineSettings(LevelMode.OFF, 0f) }
        val input = FloatArray(fs * 2) { (rnd.nextFloat() * 2 - 1) * 0.8f }
        val out = run(engine, input)
        val shift = engine.latencyFrames * 2
        for (i in 0 until input.size - shift) {
            if (out[i + shift] != input[i]) throw AssertionError("отличие в сэмпле $i")
        }
    }

    @Test
    fun centerChannelIsDownmixedEquallyAndBoosted() {
        fun centerOnly(): FloatArray {
            val n = fs * 2
            return FloatArray(n * 6) { i -> if (i % 6 == 2) 0.5f * sin(2 * PI * 1000 * (i / 6) / fs).toFloat() else 0f }
        }
        val plain = AudioEngine(fs, 6).apply { settings = EngineSettings(LevelMode.OFF, 0f) }
        val boosted = AudioEngine(fs, 6).apply { settings = EngineSettings(LevelMode.OFF, 6f) }
        val a = run(plain, centerOnly())
        val b = run(boosted, centerOnly())
        for (i in a.indices step 2) assertEquals(a[i], a[i + 1], 0f)
        val gain = rmsDb(b, 1.0, 2.0) - rmsDb(a, 1.0, 2.0)
        println("Усиление диалогов +6 дБ на 5.1 дало %.2f дБ".format(gain))
        assertEquals(6.0, gain, 0.1)
    }

    @Test
    fun dialogBoostOnStereoRaisesCenteredVoiceButNotSides() {
        fun measure(boost: Float, side: Boolean): Double {
            val engine = AudioEngine(fs, 2).apply { settings = EngineSettings(LevelMode.OFF, boost) }
            val input = tone(-30f, 2.0)
            if (side) for (i in 1 until input.size step 2) input[i] = -input[i] // противофаза = «не центр»
            return rmsDb(run(engine, input), 1.0, 2.0)
        }
        val centerGain = measure(6f, false) - measure(0f, false)
        val sideGain = measure(6f, true) - measure(0f, true)
        println("Стерео, +6 дБ: голос по центру %.2f дБ, боковой сигнал %.2f дБ".format(centerGain, sideGain))
        assertTrue(centerGain > 4.5)
        assertEquals(0.0, sideGain, 0.01)
    }

    @Test
    fun monoAndOddLayoutsWork() {
        for (ch in 1..8) {
            val engine = AudioEngine(fs, ch)
            val out = run(engine, tone(-20f, 1.0, ch))
            assertEquals((if (ch == 1) 1 else 2) * fs, out.size)
            assertTrue(out.all { it.isFinite() })
        }
    }
}
