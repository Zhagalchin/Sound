package io.github.zhagalchin.evensound.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Звено второго порядка (RBJ cookbook), транспонированная прямая форма II. */
internal class Biquad private constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y.toFloat()
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    companion object {
        fun highPass(sampleRate: Int, freq: Double, q: Double = 0.7071): Biquad {
            val w = 2 * PI * freq / sampleRate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun lowPass(sampleRate: Int, freq: Double, q: Double = 0.7071): Biquad {
            val w = 2 * PI * freq / sampleRate
            val alpha = sin(w) / (2 * q)
            val c = cos(w)
            val a0 = 1 + alpha
            return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }
    }
}
