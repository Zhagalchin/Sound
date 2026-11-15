package io.github.zhagalchin.evensound.dsp

import kotlin.math.exp
import kotlin.math.pow

/**
 * Весь звуковой тракт приложения. Чистый Kotlin без зависимостей от Android,
 * чтобы его можно было проверять обычными юнит-тестами.
 *
 * Вход: PCM float, [inChannels] каналов в порядке Android
 * (FL FR FC LFE BL BR SL SR). Выход: стерео (или моно для моно-источника).
 *
 * Тракт:
 *  1. Сведение многоканального звука в стерео с отдельной регулировкой центрального
 *     канала — именно в нём живут диалоги. Для стерео-источников диалоги выделяются
 *     как «середина» (L+R) в полосе речи.
 *  2. [Leveler] — выравнивание громкости.
 *  3. [Limiter] — защита от клиппинга.
 */
class AudioEngine(val sampleRate: Int, val inChannels: Int) {

    val outChannels: Int = if (inChannels == 1) 1 else 2

    @Volatile
    var settings: EngineSettings = EngineSettings()

    /** Текущее усиление выравнивателя, дБ. Можно читать из любого потока. */
    val gainDb: Float get() = leveler.gainDb

    val latencyFrames: Int get() = leveler.latencyFrames + limiter.latencyFrames

    private val coefL = FloatArray(inChannels)
    private val coefR = FloatArray(inChannels)
    private val centerIndex: Int

    private val leveler = Leveler(sampleRate, outChannels)
    private val limiter = Limiter(sampleRate, outChannels)

    // Усиление речи для источников без отдельного центрального канала.
    private val midHighPass = Biquad.highPass(sampleRate, 250.0)
    private val midLowPass = Biquad.lowPass(sampleRate, 3500.0)

    private var boostLin = 1f
    private val boostSmooth = (1.0 - exp(-1.0 / (0.02 * sampleRate))).toFloat()

    init {
        // Коэффициенты ITU-R BS.775: центр и тылы −3 дБ, LFE отбрасывается
        // (телефон и наушники его всё равно не воспроизведут, а запас по уровню он съедает).
        val s = 0.7071f
        val layout: List<Pair<Float, Float>> = when (inChannels) {
            1 -> listOf(1f to 0f)
            2 -> listOf(1f to 0f, 0f to 1f)
            3 -> listOf(1f to 0f, 0f to 1f, s to s)
            4 -> listOf(1f to 0f, 0f to 1f, s to 0f, 0f to s)
            5 -> listOf(1f to 0f, 0f to 1f, s to s, s to 0f, 0f to s)
            6 -> listOf(1f to 0f, 0f to 1f, s to s, 0f to 0f, s to 0f, 0f to s)
            7 -> listOf(1f to 0f, 0f to 1f, s to s, 0f to 0f, 0.5f to 0.5f, s to 0f, 0f to s)
            8 -> listOf(1f to 0f, 0f to 1f, s to s, 0f to 0f, s to 0f, 0f to s, s to 0f, 0f to s)
            else -> List(inChannels) { ch -> (if (ch == 0) 1f else 0f) to (if (ch == 1) 1f else 0f) }
        }
        centerIndex = if (inChannels in intArrayOf(3, 5, 6, 7, 8)) 2 else -1
        // Многоканальную сумму опускаем на 3 дБ, чтобы лимитеру не приходилось работать постоянно.
        val master = if (inChannels > 2) s else 1f
        for (ch in 0 until inChannels) {
            coefL[ch] = layout[ch].first * master
            coefR[ch] = layout[ch].second * master
        }
    }

    /**
     * Обрабатывает [frames] кадров из [input] в [output]. Сколько кадров вошло — столько
     * и вышло; сигнал задержан на [latencyFrames].
     */
    fun process(input: FloatArray, output: FloatArray, frames: Int) {
        val s = settings
        val boostTarget = if (s.dialogBoostDb <= 0f) 1f else 10f.pow(s.dialogBoostDb / 20f)

        if (outChannels == 1) {
            System.arraycopy(input, 0, output, 0, frames)
        } else {
            var ip = 0
            var op = 0
            for (i in 0 until frames) {
                if (boostLin != boostTarget) {
                    boostLin += (boostTarget - boostLin) * boostSmooth
                    if (kotlin.math.abs(boostLin - boostTarget) < 1e-4f) boostLin = boostTarget
                }
                var l = 0f
                var r = 0f
                for (ch in 0 until inChannels) {
                    val x = input[ip + ch]
                    l += coefL[ch] * x
                    r += coefR[ch] * x
                }
                val extra = boostLin - 1f
                if (centerIndex >= 0) {
                    if (extra != 0f) {
                        val c = input[ip + centerIndex] * extra
                        l += coefL[centerIndex] * c
                        r += coefR[centerIndex] * c
                    }
                } else if (inChannels >= 2) {
                    // Фильтры гоняем всегда, чтобы при включении усиления не было щелчка.
                    val voice = midLowPass.process(midHighPass.process((l + r) * 0.5f))
                    if (extra != 0f) {
                        l += voice * extra
                        r += voice * extra
                    }
                }
                output[op] = l
                output[op + 1] = r
                ip += inChannels
                op += 2
            }
        }
        leveler.process(output, frames, s.mode)
        limiter.process(output, frames)
    }

    /** Сброс истории сигнала (перемотка). Набранное усиление сохраняется. */
    fun reset() {
        leveler.reset()
        limiter.reset()
        midHighPass.reset()
        midLowPass.reset()
    }
}
