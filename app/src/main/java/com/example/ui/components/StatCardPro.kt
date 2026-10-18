package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.sin

/**
 * Statistic card with a colored top edge, a live mini sparkline and an
 * animated value. The number animation runs once when the card appears.
 */
@Composable
fun StatCardPro(
    title: String,
    value: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    numericValue: Int? = null,
    numericLabel: String? = null,
    progress: Float? = null,
    revealIndex: Int = 0
) {
    val gradient = Brush.linearGradient(listOf(tint, tint.copy(alpha = 0.55f)))
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val enter by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(420, delayMillis = revealIndex * 70, easing = FastOutSlowInEasing),
        label = "statEnter"
    )
    Surface(
        modifier = modifier
            .graphicsLayer { alpha = enter; translationY = (1f - enter) * 24f },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(tint.copy(alpha = 0.45f), MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            )
        )
    ) {
        Box {
            // top accent bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(gradient)
            )
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(tint.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
                    }
                    Sparkline(tint = tint, seed = title.length, modifier = Modifier.size(width = 54.dp, height = 22.dp))
                }

                androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(2.dp))
                if (numericValue != null) {
                    var started by remember { mutableStateOf(false) }
                    LaunchedEffect(numericValue) { started = true }
                    val animated by animateIntAsState(
                        targetValue = if (started) numericValue else 0,
                        animationSpec = tween(900, easing = FastOutSlowInEasing),
                        label = "countUp"
                    )
                    Text(
                        text = "${com.example.data.util.PersianDateUtil.toPersianDigits(animated.toString())}${numericLabel ?: ""}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                } else {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
                if (progress != null) {
                    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                    val animatedProgress by animateFloatAsState(
                        targetValue = progress.coerceIn(0f, 1f),
                        animationSpec = tween(1100, easing = FastOutSlowInEasing),
                        label = "progress"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedProgress)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(gradient)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Sparkline(tint: Color, seed: Int, modifier: Modifier = Modifier) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val reveal by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "spark"
    )
    Canvas(modifier = modifier) {
        val points = 14
        val values = FloatArray(points) { i ->
            val base = sin((i + seed) * 0.7f) * 0.35f + 0.5f
            (base + sin(i * 1.9f) * 0.12f).coerceIn(0.08f, 1f)
        }
        val maxV = values.max().let { max(it, 0.0001f) }
        val stepX = size.width / (points - 1)
        val visible = (points * reveal).toInt().coerceAtLeast(2)
        val path = Path()
        val fill = Path()
        values.take(visible).forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - (v / maxV) * (size.height - 3.dp.toPx())
            if (i == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, size.height)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo((visible - 1) * stepX, size.height)
        fill.close()
        drawPath(
            fill,
            brush = Brush.verticalGradient(listOf(tint.copy(alpha = 0.30f), Color.Transparent)),
            alpha = 1f
        )
        drawPath(
            path,
            color = tint,
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        )
        drawCircle(
            color = tint,
            radius = 2.2.dp.toPx(),
            center = Offset((visible - 1) * stepX, size.height - (values[visible - 1] / maxV) * (size.height - 3.dp.toPx()))
        )
    }
}
