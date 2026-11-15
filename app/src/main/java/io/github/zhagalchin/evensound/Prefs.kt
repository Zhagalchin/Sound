package io.github.zhagalchin.evensound

import android.content.Context
import android.net.Uri
import io.github.zhagalchin.evensound.dsp.EngineSettings
import io.github.zhagalchin.evensound.dsp.LevelMode

/** Настройки звука и позиции просмотра. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("evensound", Context.MODE_PRIVATE)

    var settings: EngineSettings
        get() = EngineSettings(
            mode = runCatching { LevelMode.valueOf(sp.getString("mode", null) ?: "") }.getOrDefault(LevelMode.MEDIUM),
            dialogBoostDb = sp.getFloat("dialogBoost", 3f),
        )
        set(value) = sp.edit().putString("mode", value.mode.name).putFloat("dialogBoost", value.dialogBoostDb).apply()

    /** Включён ли системный режим (для всех приложений). */
    var globalEnabled: Boolean
        get() = sp.getBoolean("globalEnabled", false)
        set(value) = sp.edit().putBoolean("globalEnabled", value).apply()

    fun position(uri: Uri): Long = sp.getLong("pos:$uri", 0L)

    fun savePosition(uri: Uri, positionMs: Long, durationMs: Long) {
        // Досмотренное почти до конца в следующий раз начинаем сначала.
        val finished = durationMs > 0 && positionMs > durationMs - 30_000
        val e = sp.edit()
        if (finished || positionMs < 10_000) e.remove("pos:$uri") else e.putLong("pos:$uri", positionMs)
        e.apply()
    }
}
