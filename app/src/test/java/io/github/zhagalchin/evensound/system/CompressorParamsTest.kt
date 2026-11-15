package io.github.zhagalchin.evensound.system

import io.github.zhagalchin.evensound.dsp.LevelMode
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressorParamsTest {
    @Test
    fun everyModeNarrowsTheGapAndNeverClips() {
        assertNull(CompressorParams.forMode(LevelMode.OFF))
        var previousGap = 37f
        for (mode in listOf(LevelMode.LIGHT, LevelMode.MEDIUM, LevelMode.NIGHT)) {
            val p = CompressorParams.forMode(mode)!!
            val gap = p.outputDb(-8f) - p.outputDb(-45f) // взрыв против шёпота, на входе 37 дБ
            println("$mode: шёпот −45 → %.1f, диалог −27 → %.1f, взрыв −8 → %.1f, разница %.1f дБ"
                .format(p.outputDb(-45f), p.outputDb(-27f), p.outputDb(-8f), gap))
            assertTrue(gap < previousGap) // каждый следующий режим сильнее предыдущего
            assertTrue(p.outputDb(0f) < -6f) // даже полная шкала остаётся с запасом до клиппинга
            assertTrue(p.outputDb(-27f) > -27f) // диалог становится громче
            previousGap = gap
        }
        assertTrue(previousGap < 8f)
    }
}
