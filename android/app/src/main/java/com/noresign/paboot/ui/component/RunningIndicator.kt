package com.noresign.paboot.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 运行中脉冲圆点（§5 运行中指示器）。 */
@Composable
fun RunningIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 9.dp,
) {
    val transition = rememberInfiniteTransition(label = "running")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(
        modifier = modifier
            .size(size)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.primary, CircleShape),
    )
}
