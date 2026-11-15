package io.github.zhagalchin.evensound.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Яркая рамка вокруг элемента в фокусе — чтобы с пульта было видно, где находишься. */
fun Modifier.focusRing(shape: Shape = CircleShape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
}
