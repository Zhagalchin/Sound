package io.github.zhagalchin.evensound.dsp

import kotlin.math.abs
import kotlin.math.exp

/**
 * Упреждающий лимитер пиков: гарантирует, что ни один сэмпл не превысит [CEILING],
 * даже когда тихую сцену подняли на +20 дБ, а в ней хлопнула дверь.
 *
 * Требуемое ослабление для каждого сэмпла проходит через скользящий минимум и
 * скользящее среднее длиной в окно упреждения — усиление опускается плавно и
 * заранее, без щелчков. Пока пиков нет, сигнал проходит бит-в-бит.
 */
internal class Limiter(sampleRate: Int, private val channels: Int) {

    private val window: Int = (sampleRate * LOOKAHEAD_MS / 1000).toInt().coerceAtLeast(2)
    val latencyFrames: Int = window - 1

    private val delay = FloatArray(latencyFrames * channels)
    private var delayPos = 0

    private val releaseCoef = (1.0 - exp(-1.0 / (RELEASE_MS * 0.001 * sampleRate))).toFloat()
    private var released = 1f

    // Монотонная очередь для скользящего минимума.
    private val dqVal = FloatArray(window)
    private val dqIdx = LongArray(window)
    private var dqHead = 0
    private var dqSize = 0
    private var t = 0L

    // Скользящее среднее минимумов.
    private val box = FloatArray(window) { 1f }
    private var boxPos = 0
    private var boxSum = window.toDouble()
    private var boxActive = 0 // сколько значений в окне меньше 1

    fun process(buf: FloatArray, frames: Int) {
        var p = 0
        for (i in 0 until frames) {
            var peak = 0f
            for (c in 0 until channels) peak = maxOf(peak, abs(buf[p + c]))
            val needed = if (peak > CEILING) CEILING / peak else 1f
            released += (1f - released) * releaseCoef
            if (needed < released) released = needed

            // скользящий минимум за последние `window` сэмплов
            if (dqSize > 0 && dqIdx[dqHead] <= t - window) {
                dqHead = (dqHead + 1) % window
                dqSize--
            }
            while (dqSize > 0 && dqVal[(dqHead + dqSize - 1) % window] >= released) dqSize--
            val tail = (dqHead + dqSize) % window
            dqVal[tail] = released
            dqIdx[tail] = t
            dqSize++
            t++
            val m = dqVal[dqHead]

            // скользящее среднее
            val old = box[boxPos]
            if (old < 1f) boxActive--
            if (m < 1f) boxActive++
            boxSum += m - old
            box[boxPos] = m
            if (++boxPos == window) boxPos = 0
            if (boxActive == 0) boxSum = window.toDouble() // гасим накопленную погрешность

            if (boxActive == 0) {
                for (c in 0 until channels) {
                    val x = buf[p + c]
                    buf[p + c] = delay[delayPos]
                    delay[delayPos] = x
                    if (++delayPos == delay.size) delayPos = 0
                }
            } else {
                val g = (boxSum / window).toFloat()
                for (c in 0 until channels) {
                    val x = buf[p + c]
                    buf[p + c] = (delay[delayPos] * g).coerceIn(-CEILING, CEILING)
                    delay[delayPos] = x
                    if (++delayPos == delay.size) delayPos = 0
                }
            }
            p += channels
        }
    }

    fun reset() {
        delay.fill(0f)
        delayPos = 0
        released = 1f
        dqHead = 0
        dqSize = 0
        box.fill(1f)
        boxPos = 0
        boxSum = window.toDouble()
        boxActive = 0
    }

    companion object {
        /** −1 дБFS. */
        const val CEILING = 0.8913f
        private const val LOOKAHEAD_MS = 5f
        private const val RELEASE_MS = 80f
    }
}
