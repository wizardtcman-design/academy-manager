package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ============================================================
// Design system: "glow cards" — every card gets a soft brand glow
// behind it, a hairline gradient border and a header band.
// Layout and ordering of content inside cards is unchanged.
// ============================================================

private fun Modifier.brandGlow(
    color: Color,
    cornerRadius: Int = 20,
    intensity: Float = 0.20f,
    offsetY: Int = 12
): Modifier = drawBehind {
    val radius = cornerRadius.dp.toPx()
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(color.copy(alpha = intensity * 1.6f), color.copy(alpha = 0f)),
            startY = offsetY.dp.toPx(),
            endY = offsetY.dp.toPx() + radius * 2.4f
        ),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius * 4f, radius * 4f),
        topLeft = Offset(0f, offsetY.dp.toPx()),
        size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.92f)
    )
}

private fun Modifier.gradientBorder(
    shape: Shape,
    colors: List<Color>,
    width: Int = 1
): Modifier = border(
    brush = Brush.linearGradient(colors),
    width = width.dp,
    shape = shape
)

/** Two decorative soft light blobs, used inside hero/header bands. */
@Composable
fun GlowOrbs(
    modifier: Modifier = Modifier,
    primary: Color = Color.White,
    secondary: Color = Color.White,
    animate: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "orbs")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Restart),
        label = "orbAngle"
    )
    Canvas(modifier = modifier) {
        val cx = size.width * 0.5f + cos(t) * size.width * 0.06f
        val cy = size.height * 0.35f + sin(t) * size.height * 0.12f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primary.copy(alpha = 0.55f), Color.Transparent),
                center = Offset(cx, cy),
                radius = size.minDimension * 0.75f
            ),
            radius = size.minDimension * 0.75f,
            center = Offset(cx, cy)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(secondary.copy(alpha = 0.45f), Color.Transparent),
                center = Offset(size.width * 0.95f - cos(t) * size.width * 0.05f, size.height * 0.85f),
                radius = size.minDimension * 0.6f
            ),
            radius = size.minDimension * 0.6f,
            center = Offset(size.width * 0.95f, size.height * 0.9f)
        )
    }
}

/**
 * Card with a colored header band: icon + title (+ optional trailing badge)
 * above the existing content. Content itself is passed in untouched.
 */
@Composable
fun HeaderCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    accent2: Color = MaterialTheme.colorScheme.secondary,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(20.dp),
    showHeader: Boolean = true,
    content: ColumnScopeContent
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val gradient = Brush.linearGradient(listOf(accent, accent2))
    val base = Modifier
        .fillMaxWidth()
        .then(if (onClick != null) Modifier.clip(shape) else Modifier)
        .brandGlow(accent, cornerRadius = 20)
        .gradientBorder(
            shape,
            listOf(
                accent.copy(alpha = 0.45f),
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                accent2.copy(alpha = 0.18f)
            )
        )

    val headerShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    Box(modifier = modifier) {
        Surface(
            modifier = base,
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = if (pressed) 3.dp else 9.dp,
            onClick = onClick ?: {},
            enabled = onClick != null,
            interactionSource = interaction
        ) {
            Column {
                if (showHeader) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(headerShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(accent.copy(alpha = 0.16f), accent2.copy(alpha = 0.07f))
                                )
                            )
                    ) {
                        GlowOrbs(
                            modifier = Modifier.matchParentSize(),
                            primary = accent,
                            secondary = accent2
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(gradient),
                                contentAlignment = Alignment.Center
                            ) {
                                if (icon != null) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (subtitle != null) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            trailing?.invoke()
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(accent.copy(alpha = 0.35f), Color.Transparent)
                                    )
                                )
                        )
                    }
                }
                content(this)
            }
        }
    }
}

typealias ColumnScopeContent = @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
