package vn.onplay.vdubbing.data.speech

import kotlin.math.abs
import kotlin.math.sqrt

internal object KorvaAudio {
    fun cleanTail(input: FloatArray, rate: Int): FloatArray {
        val frame = (0.02 * rate).toInt().coerceAtLeast(1)
        val start = maxOf(0, input.size - (0.6 * rate).toInt())
        var gapStart = 0
        var run = 0
        var best = -1
        var i = start
        while (i < input.size - frame) {
            var energy = 0.0
            for (j in i until i + frame) energy += input[j].toDouble() * input[j]
            if (sqrt(energy / frame) < 0.01) {
                if (run == 0) gapStart = i
                run += frame
            } else {
                if (run >= 0.05 * rate) best = gapStart
                run = 0
            }
            i += frame
        }
        if (run >= 0.05 * rate) best = gapStart
        var wave = input
        if (best >= 0) {
            val after = minOf(input.size, best + (0.05 * rate).toInt())
            var peak = 0f
            for (j in after until input.size) peak = maxOf(peak, abs(input[j]))
            if (peak < 0.05 || (input.size - after <= 0.35 * rate && peak >= 0.4)) {
                wave = input.copyOf(after)
            }
        }
        val fade = minOf(wave.size, (0.03 * rate).toInt())
        for (j in 0 until fade) {
            wave[wave.size - fade + j] *= if (fade > 1) 1f - j.toFloat() / (fade - 1) else 0f
        }
        return wave
    }
}
