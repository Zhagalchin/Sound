package io.github.zhagalchin.evensound

import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.zhagalchin.evensound.dsp.EngineSettings
import io.github.zhagalchin.evensound.player.LevelingAudioProcessor
import io.github.zhagalchin.evensound.player.PlayerFactory
import io.github.zhagalchin.evensound.system.GlobalEffect
import io.github.zhagalchin.evensound.system.GlobalEffectService
import io.github.zhagalchin.evensound.ui.SoundPanel
import io.github.zhagalchin.evensound.ui.focusRing
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private val processor = LevelingAudioProcessor()
    private lateinit var player: ExoPlayer

    private var currentUri by mutableStateOf<Uri?>(null)
    private var settings by mutableStateOf(EngineSettings())
    private var error by mutableStateOf<String?>(null)
    private var sheetOpen by mutableStateOf(false)
    private var globalEnabled by mutableStateOf(false)
    private var pickerMissing by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        prefs = Prefs(this)
        settings = prefs.settings
        processor.settings = settings
        globalEnabled = prefs.globalEnabled
        // Сервис мог быть остановлен системой — поднимаем его снова.
        if (globalEnabled) runCatching { GlobalEffectService.setEnabled(this, true) }
        player = PlayerFactory.create(this, processor)
        player.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = "Не удалось воспроизвести: ${e.errorCodeName}"
            }
        })

        val restored = savedInstanceState?.getString(KEY_URI)?.let(Uri::parse)
        (intent.viewUri() ?: restored)?.let { open(it, autoPlay = restored == null) }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val uri = currentUri
                if (uri == null) {
                    HomeScreen()
                } else {
                    PlayerScreen()
                    BackHandler { close() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.viewUri()?.let { open(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentUri?.let { outState.putString(KEY_URI, it.toString()) }
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        player.pause()
        GlobalEffect.setPaused(false)
    }

    override fun onStart() {
        super.onStart()
        if (currentUri != null) GlobalEffect.setPaused(true)
    }

    /** Кнопка «Меню» на пульте открывает настройки звука во время просмотра. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (currentUri != null && !sheetOpen &&
            (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS)
        ) {
            sheetOpen = true
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }

    private fun Intent.viewUri(): Uri? = if (action == Intent.ACTION_VIEW) data else null

    private fun open(uri: Uri, autoPlay: Boolean = true) {
        savePosition()
        error = null
        currentUri = uri
        player.setMediaItem(MediaItem.fromUri(uri), prefs.position(uri))
        player.playWhenReady = autoPlay
        player.prepare()
        GlobalEffect.setPaused(true)
        setFullscreen(true)
    }

    private fun close() {
        savePosition()
        player.stop()
        player.clearMediaItems()
        currentUri = null
        sheetOpen = false
        GlobalEffect.setPaused(false)
        setFullscreen(false)
    }

    private fun savePosition() {
        val uri = currentUri ?: return
        if (player.playbackState == Player.STATE_IDLE) return
        prefs.savePosition(uri, player.currentPosition, player.duration.coerceAtLeast(0))
    }

    private fun updateSettings(value: EngineSettings) {
        settings = value
        processor.settings = value
        prefs.settings = value
        GlobalEffect.update(value)
    }

    private fun setGlobal(enabled: Boolean) {
        globalEnabled = enabled
        prefs.globalEnabled = enabled
        runCatching { GlobalEffectService.setEnabled(this, enabled) }
    }

    private fun setFullscreen(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (on) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    @Composable
    private fun HomeScreen() {
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                // Чтобы после перезапуска можно было продолжить с того же места.
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                open(uri)
            }
        }
        val notificationPermission =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Ровный звук", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Плеер, который делает шёпот слышным, а взрывы — не оглушающими. " +
                            "Громкость не придётся крутить весь фильм.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    SoundPanel(settings, ::updateSettings, gainDb = null, sourceChannels = 0)

                    HorizontalDivider()
                    Text("Для всех приложений", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Выравнивает звук там, где нельзя выбрать свой плеер: онлайн-кинотеатры, " +
                            "ТВ, YouTube. Работает в фоне и включается сам после перезагрузки.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            if (!globalEnabled && Build.VERSION.SDK_INT >= 33 &&
                                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                            }
                            setGlobal(!globalEnabled)
                        },
                        modifier = Modifier.fillMaxWidth().focusRing(),
                    ) { Text(if (globalEnabled) "Выключить для всех приложений" else "Включить для всех приложений") }
                    if (globalEnabled) {
                        val (text, bad) = when (val st = GlobalEffect.status) {
                            GlobalEffect.Status.Active -> "Работает. Откройте любое приложение — звук уже выравнивается." to false
                            GlobalEffect.Status.Paused -> "Пауза: сейчас играет встроенный плеер." to false
                            GlobalEffect.Status.Off -> "Запускается…" to false
                            GlobalEffect.Status.LostControl ->
                                "Эффектом управляет другое приложение (эквалайзер или «улучшайзер» звука). Отключите его." to true
                            is GlobalEffect.Status.Failed ->
                                "Устройство не разрешило системный эффект: ${st.reason}" to true
                        }
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "Чтобы работало в полную силу: в настройках звука приставки выберите вывод PCM " +
                                "(не «сквозной»/passthrough/RAW), а громкость самой приставки держите на максимуме " +
                                "и регулируйте её телевизором.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    HorizontalDivider()
                    Text("Встроенный плеер", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Для своих файлов. Обработка точнее, чем в системном режиме: " +
                            "в дорожках 5.1 диалоги усиливаются отдельно от музыки и эффектов.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching { picker.launch(arrayOf("video/*", "audio/*")) }
                                .onFailure { pickerMissing = true }
                        },
                        modifier = Modifier.fillMaxWidth().focusRing(),
                    ) { Text("Открыть видео") }
                    Text(
                        if (pickerMissing) "На этом устройстве нет системного выбора файлов. Откройте видео из " +
                            "файлового менеджера: «Открыть с помощью» → «Ровный звук»."
                        else "Можно и из файлового менеджера: «Открыть с помощью» → «Ровный звук». " +
                            "Во время просмотра настройки звука — кнопка «Меню» на пульте.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PlayerScreen() {
        var controlsVisible by remember { mutableStateOf(true) }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    PlayerView(context).apply {
                        player = this@MainActivity.player
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        setShowSubtitleButton(true)
                        isFocusable = true
                        post { requestFocus() }
                        setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { controlsVisible = it == android.view.View.VISIBLE }
                        )
                    }
                },
                onRelease = { it.player = null },
            )
            if (controlsVisible) {
                FilledTonalButton(
                    onClick = { sheetOpen = true },
                    modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp).focusRing(),
                ) { Text("Звук") }
            }
            error?.let {
                Text(
                    it,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
        }

        if (sheetOpen) {
            var gainDb by remember { mutableFloatStateOf(0f) }
            var channels by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                while (true) {
                    gainDb = processor.gainDb
                    channels = processor.sourceChannels
                    delay(100)
                }
            }
            ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
                SoundPanel(
                    settings = settings,
                    onChange = ::updateSettings,
                    gainDb = gainDb,
                    sourceChannels = channels,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 24.dp),
                )
            }
        }
    }

    private companion object {
        const val KEY_URI = "uri"
    }
}
