package io.github.zhagalchin.evensound.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zhagalchin.evensound.dsp.EngineSettings
import io.github.zhagalchin.evensound.dsp.LevelMode
import kotlin.math.roundToInt

private val modeTitles = mapOf(
    LevelMode.OFF to "Выкл",
    LevelMode.LIGHT to "Лёгкое",
    LevelMode.MEDIUM to "Среднее",
    LevelMode.NIGHT to "Ночь",
)

private val modeHints = mapOf(
    LevelMode.OFF to "Звук как в оригинале. Работает только защита от перегруза.",
    LevelMode.LIGHT to "Разница между тихим и громким сокращается примерно вдвое. Динамика фильма сохраняется.",
    LevelMode.MEDIUM to "Шёпот хорошо слышен, взрывы заметно тише. Подходит в большинстве случаев.",
    LevelMode.NIGHT to "Всё звучит почти на одной громкости. Для просмотра ночью, в транспорте, через динамик телефона.",
)

/**
 * Панель настроек звука.
 *
 * @param gainDb текущее усиление выравнивателя (null — ничего не играет).
 * @param sourceChannels число каналов исходной дорожки.
 */
@Composable
fun SoundPanel(
    settings: EngineSettings,
    onChange: (EngineSettings) -> Unit,
    gainDb: Float?,
    sourceChannels: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Выравнивание громкости", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            LevelMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = settings.mode == mode,
                    onClick = { onChange(settings.copy(mode = mode)) },
                    shape = SegmentedButtonDefaults.itemShape(index, LevelMode.entries.size),
                    icon = {},
                    modifier = Modifier.focusRing(SegmentedButtonDefaults.itemShape(index, LevelMode.entries.size)),
                ) { Text(modeTitles.getValue(mode), maxLines = 1) }
            }
        }
        Text(
            modeHints.getValue(settings.mode),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Усиление диалогов", style = MaterialTheme.typography.titleMedium)
            Text("+${settings.dialogBoostDb.roundToInt()} дБ", style = MaterialTheme.typography.titleMedium)
        }
        // Кнопки вместо ползунка: одинаково удобно пальцем и пультом.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                onClick = { onChange(settings.copy(dialogBoostDb = (settings.dialogBoostDb - 1f).coerceAtLeast(0f))) },
                enabled = settings.dialogBoostDb > 0f,
                modifier = Modifier.weight(1f).focusRing(),
            ) { Text("− тише") }
            FilledTonalButton(
                onClick = { onChange(settings.copy(dialogBoostDb = (settings.dialogBoostDb + 1f).coerceAtMost(12f))) },
                enabled = settings.dialogBoostDb < 12f,
                modifier = Modifier.weight(1f).focusRing(),
            ) { Text("+ громче") }
        }

        if (gainDb != null) {
            Spacer(Modifier.height(4.dp))
            val sign = if (gainDb >= 0.05f) "+" else if (gainDb <= -0.05f) "−" else ""
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Сейчас громкость изменена на", style = MaterialTheme.typography.bodyMedium)
                Text("$sign%.1f дБ".format(kotlin.math.abs(gainDb)), style = MaterialTheme.typography.bodyMedium)
            }
            // Шкала −24…+24 дБ, середина — без изменений.
            LinearProgressIndicator(
                progress = { ((gainDb + 24f) / 48f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                drawStopIndicator = {},
            )
            Text(
                when {
                    sourceChannels > 2 -> "Дорожка: $sourceChannels каналов. Диалоги усиливаются в центральном канале."
                    sourceChannels == 2 -> "Дорожка: стерео. Диалоги выделяются как звук по центру в полосе речи."
                    else -> "Дорожка: моно."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
