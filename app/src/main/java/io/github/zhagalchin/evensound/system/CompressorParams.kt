package io.github.zhagalchin.evensound.system

import io.github.zhagalchin.evensound.dsp.LevelMode

/**
 * Параметры системного компрессора Android для каждого режима выравнивания.
 *
 * Системный эффект — обычный компрессор «сверху вниз»: всё, что громче порога,
 * сжимается в [ratio] раз, затем весь сигнал поднимается на [makeupDb].
 */
data class CompressorParams(
    val thresholdDb: Float,
    val ratio: Float,
    val makeupDb: Float,
    val attackMs: Float,
    val releaseMs: Float,
) {
    /** Уровень на выходе для установившегося уровня [inputDb] на входе. */
    fun outputDb(inputDb: Float): Float =
        (if (inputDb > thresholdDb) thresholdDb + (inputDb - thresholdDb) / ratio else inputDb) + makeupDb

    companion object {
        fun forMode(mode: LevelMode): CompressorParams? = when (mode) {
            LevelMode.OFF -> null
            LevelMode.LIGHT -> CompressorParams(thresholdDb = -35f, ratio = 2f, makeupDb = 6f, attackMs = 50f, releaseMs = 900f)
            LevelMode.MEDIUM -> CompressorParams(thresholdDb = -40f, ratio = 3f, makeupDb = 12f, attackMs = 35f, releaseMs = 700f)
            LevelMode.NIGHT -> CompressorParams(thresholdDb = -45f, ratio = 6f, makeupDb = 20f, attackMs = 20f, releaseMs = 500f)
        }
    }
}
