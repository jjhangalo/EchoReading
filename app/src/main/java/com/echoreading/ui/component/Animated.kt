package com.echoreading.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SoundWave(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "stitch_wave")
    val h1 by transition.animateFloat(
        6f,
        18f,
        infiniteRepeatable(tween(400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "w1"
    )
    val h2 by transition.animateFloat(
        10f,
        24f,
        infiniteRepeatable(tween(300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "w2"
    )
    val h3 by transition.animateFloat(
        5f,
        16f,
        infiniteRepeatable(tween(480, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "w3"
    )
    val h4 by transition.animateFloat(
        8f,
        22f,
        infiniteRepeatable(tween(350, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "w4"
    )
    val h5 by transition.animateFloat(
        6f,
        18f,
        infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "w5"
    )

    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(if (isPlaying) h1.dp else 6.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )
            Box(
                Modifier
                    .width(3.dp)
                    .height(if (isPlaying) h2.dp else 10.dp)
                    .background(
                        MaterialTheme.colorScheme.secondary,
                        androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                    )
            )
            Box(
                Modifier
                    .width(3.dp)
                    .height(if (isPlaying) h3.dp else 5.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                    )
            )
            Box(
                Modifier
                    .width(3.dp)
                    .height(if (isPlaying) h4.dp else 8.dp)
                    .background(
                        MaterialTheme.colorScheme.secondary,
                        androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                    )
            )
            Box(
                Modifier
                    .width(3.dp)
                    .height(if (isPlaying) h5.dp else 6.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}
