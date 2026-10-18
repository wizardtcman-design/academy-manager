package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

/**
 * Fades and lifts its content in. Give each list item a different [index] to
 * get a staggered cascade; the animation only runs once per item.
 */
@Composable
fun RevealItem(
    index: Int = 0,
    stepMillis: Int = 45,
    maxDelayMillis: Int = 360,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val delay = (index * stepMillis).coerceAtMost(maxDelayMillis)
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 420, delayMillis = delay, easing = FastOutSlowInEasing),
        label = "reveal"
    )
    Box(
        modifier = modifier
            .alpha(progress)
            .offset(y = (1f - progress).dp * 18),
        content = content
    )
}
