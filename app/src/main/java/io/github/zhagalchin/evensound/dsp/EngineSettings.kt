package io.github.zhagalchin.evensound.dsp

/**
 * Режим выравнивания громкости.
 *
 * @param strength доля компенсации отклонения от целевого уровня: 1.0 — всё приводится
 *   к одной громкости, 0.5 — разница между тихим и громким сокращается вдвое.
 * @param maxBoostDb насколько максимум можно поднять тихое место.
 * @param maxCutDb насколько максимум можно приглушить громкое место.
 * @param releaseDbPerSec скорость возврата громкости после громкого места.
 * @param attackMs постоянная времени приглушения громкого места.
 */
enum class LevelMode(
    val strength: Float,
    val maxBoostDb: Float,
    val maxCutDb: Float,
    val releaseDbPerSec: Float,
    val attackMs: Float,
) {
    OFF(0f, 0f, 0f, 0f, 0f),
    LIGHT(0.5f, 9f, 9f, 5f, 60f),
    MEDIUM(0.75f, 15f, 15f, 8f, 40f),
    NIGHT(1f, 24f, 24f, 12f, 25f),
}

data class EngineSettings(
    val mode: LevelMode = LevelMode.MEDIUM,
    /** Дополнительное усиление диалогов, дБ (0..12). */
    val dialogBoostDb: Float = 3f,
)
