package io.github.zhagalchin.evensound.dsp

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Выравниватель громкости: медленная автоматическая регулировка усиления.
 *
 * Измеряет текущую громкость (RMS без низов, чтобы бас взрывов не «качал» громкость речи),
 * сравнивает с целевой и плавно двигает общее усиление: громкое приглушается быстро,
 * тихое поднимается медленно и только после паузы. Спектр и баланс каналов не трогаются —
 * меняется только общая громкость, как если бы кто-то крутил ручку за зрителя.
 */
internal class Leveler(private val sampleRate: Int, private val channels: Int) {

    val latencyFrames: Int = (sampleRate * LOOKAHEAD_MS / 1000).toInt()

    /** Текущее усиление в дБ — для индикатора в интерфейсе. */
    @Volatile
    var gainDb: Float = 0f
        private set

    private val delay = FloatArray(latencyFrames * channels)
    private var delayPos = 0

    private val detectorFilters = Array(channels) { Biquad.highPass(sampleRate, DETECTOR_HP_HZ) }
    private val envAttack = coef(DETECTOR_ATTACK_MS, 1)
    private val envRelease = coef(DETECTOR_RELEASE_MS, 1)
    private var env = 0.0

    private val controlDt = CONTROL_FRAMES.toFloat() / sampleRate
    private var controlCounter = 0
    private var gDb = 0f
    private var holdSec = 0f
    private var gainLin = 1f
    private var gainStep = 0f

    fun process(buf: FloatArray, frames: Int, mode: LevelMode) {
        var p = 0
        for (i in 0 until frames) {
            // 1. Детектор громкости по входному (ещё не задержанному) сигналу.
            var ms = 0.0
            for (c in 0 until channels) {
                val w = detectorFilters[c].process(buf[p + c])
                ms += w * w
            }
            ms /= channels
            env += (ms - env) * (if (ms > env) envAttack else envRelease)

            // 2. Раз в CONTROL_FRAMES пересчитываем желаемое усиление.
            if (controlCounter == 0) {
                updateGain(mode)
                controlCounter = CONTROL_FRAMES
            }
            controlCounter--
            gainLin += gainStep

            // 3. Применяем усиление к сигналу, задержанному на lookahead:
            //    громкость начинает снижаться чуть раньше, чем придёт сам удар.
            val g = gainLin
            for (c in 0 until channels) {
                val x = buf[p + c]
                buf[p + c] = if (g == 1f) delay[delayPos] else delay[delayPos] * g
                delay[delayPos] = x
                if (++delayPos == delay.size) delayPos = 0
            }
            p += channels
        }
    }

    private fun updateGain(mode: LevelMode) {
        val levelDb = (10.0 * log10(env + 1e-12)).toFloat()
        if (mode == LevelMode.OFF) {
            // Плавно возвращаемся к 0 дБ и дальше не вмешиваемся вообще.
            val step = OFF_RETURN_DB_PER_SEC * controlDt
            gDb = if (gDb > 0) max(0f, gDb - step) else min(0f, gDb + step)
        } else {
            val want = (mode.strength * (TARGET_DB - levelDb)).coerceIn(-mode.maxCutDb, mode.maxBoostDb)
            if (want < gDb) {
                // Стало громко — приглушаем быстро.
                gDb += (want - gDb) * coef(mode.attackMs, CONTROL_FRAMES)
                holdSec = HOLD_SEC
            } else if (levelDb < GATE_DB) {
                // Тишина или шум: не поднимаем, чтобы не вытягивать шипение.
            } else if (holdSec > 0f) {
                // Пауза между словами — ждём, не «дышим» громкостью.
                holdSec -= controlDt
            } else {
                gDb = min(want, gDb + mode.releaseDbPerSec * controlDt)
            }
        }
        gainDb = gDb
        val target = if (gDb == 0f) 1f else 10f.pow(gDb / 20f)
        gainStep = (target - gainLin) / CONTROL_FRAMES
        if (gainStep == 0f) gainLin = target
    }

    /** Сброс при перемотке: очищаем историю сигнала, но усиление сохраняем. */
    fun reset() {
        delay.fill(0f)
        delayPos = 0
        detectorFilters.forEach { it.reset() }
        holdSec = 0f
    }

    private fun coef(timeMs: Float, frames: Int): Float =
        (1.0 - exp(-frames.toDouble() / (timeMs * 0.001 * sampleRate))).toFloat()

    companion object {
        /** Целевая громкость, дБFS RMS. Близко к −16 LUFS, принятым для мобильных устройств. */
        const val TARGET_DB = -18f
        const val GATE_DB = -55f
        private const val LOOKAHEAD_MS = 10f
        private const val DETECTOR_HP_HZ = 100.0
        private const val DETECTOR_ATTACK_MS = 5f
        private const val DETECTOR_RELEASE_MS = 60f
        private const val HOLD_SEC = 0.2f
        private const val OFF_RETURN_DB_PER_SEC = 20f
        private const val CONTROL_FRAMES = 32
    }
}
