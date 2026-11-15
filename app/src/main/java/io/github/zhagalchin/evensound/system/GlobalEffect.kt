package io.github.zhagalchin.evensound.system

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.zhagalchin.evensound.dsp.EngineSettings

/**
 * Системный режим: компрессор Android ([DynamicsProcessing]) на общем аудиовыходе
 * (сессия 0). Действует на звук всех приложений — так можно выровнять громкость там,
 * где нельзя выбрать сторонний плеер.
 *
 * Все методы вызываются из главного потока.
 */
object GlobalEffect {

    sealed interface Status {
        data object Off : Status
        data object Active : Status
        /** Играет наш собственный плеер — он выравнивает звук сам, двойная обработка не нужна. */
        data object Paused : Status
        /** Другое приложение перехватило управление эффектом. */
        data object LostControl : Status
        data class Failed(val reason: String) : Status
    }

    var status: Status by mutableStateOf(Status.Off)
        private set

    private const val TAG = "GlobalEffect"
    private const val GLOBAL_SESSION = 0
    private const val PRIORITY = 1000

    // Полосы эквалайзера для усиления речи: (верхняя граница полосы, доля усиления).
    private val voiceBands = listOf(180f to 0f, 400f to 0.5f, 3200f to 1f, 5500f to 0.5f, 20000f to 0f)

    private var effect: DynamicsProcessing? = null
    private var settings = EngineSettings()
    private var running = false
    private var paused = false

    fun start(settings: EngineSettings) {
        this.settings = settings
        running = true
        refresh()
    }

    fun stop() {
        running = false
        release()
        status = Status.Off
    }

    fun update(settings: EngineSettings) {
        this.settings = settings
        if (running) refresh()
    }

    fun setPaused(paused: Boolean) {
        if (this.paused == paused) return
        this.paused = paused
        if (running) refresh()
    }

    private fun refresh() {
        if (paused) {
            runCatching { effect?.enabled = false }
            status = Status.Paused
            return
        }
        try {
            val dp = effect ?: create().also { effect = it }
            configure(dp, settings)
            dp.enabled = true
            status = if (dp.hasControl()) Status.Active else Status.LostControl
        } catch (e: Throwable) {
            // Прошивка может не содержать эффекта или запрещать его на общем выходе.
            Log.w(TAG, "Не удалось включить системный эффект", e)
            release()
            status = Status.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun create(): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            /* channelCount = */ 2,
            /* preEqInUse = */ true, voiceBands.size,
            /* mbcInUse = */ true, 1,
            /* postEqInUse = */ false, 0,
            /* limiterInUse = */ true,
        ).build()
        return DynamicsProcessing(PRIORITY, GLOBAL_SESSION, config).apply {
            setControlStatusListener { _, controlGranted ->
                if (running && !paused) status = if (controlGranted) Status.Active else Status.LostControl
            }
        }
    }

    private fun configure(dp: DynamicsProcessing, s: EngineSettings) {
        // 1. Усиление диалогов: поднимаем полосу речи.
        val eq = DynamicsProcessing.Eq(true, true, voiceBands.size)
        voiceBands.forEachIndexed { i, (cutoff, share) ->
            eq.setBand(i, DynamicsProcessing.EqBand(true, cutoff, s.dialogBoostDb * share))
        }
        dp.setPreEqAllChannelsTo(eq)

        // 2. Компрессор на всю полосу: спектр не трогаем, меняем только громкость.
        val p = CompressorParams.forMode(s.mode)
        val mbc = DynamicsProcessing.Mbc(true, true, 1)
        mbc.setBand(
            0,
            DynamicsProcessing.MbcBand(
                /* enabled = */ p != null,
                /* cutoffFrequency = */ 20000f,
                /* attackTime = */ p?.attackMs ?: 50f,
                /* releaseTime = */ p?.releaseMs ?: 500f,
                /* ratio = */ p?.ratio ?: 1f,
                /* threshold = */ p?.thresholdDb ?: 0f,
                /* kneeWidth = */ 8f,
                /* noiseGateThreshold = */ -80f,
                /* expanderRatio = */ 1f,
                /* preGain = */ 0f,
                /* postGain = */ p?.makeupDb ?: 0f,
            ),
        )
        dp.setMbcAllChannelsTo(mbc)

        // 3. Лимитер от перегруза.
        dp.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(
                /* inUse = */ true, /* enabled = */ true, /* linkGroup = */ 0,
                /* attackTime = */ 1f, /* releaseTime = */ 80f, /* ratio = */ 20f,
                /* threshold = */ -1f, /* postGain = */ 0f,
            )
        )
    }

    private fun release() {
        runCatching { effect?.release() }
        effect = null
    }
}
