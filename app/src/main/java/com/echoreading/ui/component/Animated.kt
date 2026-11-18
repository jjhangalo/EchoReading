package com.echoreading.ui.component

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * True when the user asked the system to remove animations (the accessibility
 * "Remove animations" toggle sets the animator duration scale to 0).
 *
 * shortcut: read once per composition; a mid-session toggle is only picked up
 * when this composable leaves and re-enters composition, which is acceptable
 * because the setting is normally changed from outside the app.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

/**
 * Play/pause icon that morphs between a triangle and two bars.
 *
 * Each shape is drawn as two quads (the triangle is split vertically, with the
 * right quad collapsing into the tip), so a single 0..1 fraction interpolates
 * every corner. [playing] shows the pause bars, matching the transport button
 * convention of showing the action the tap will trigger.
 */
@Composable
fun PlayPauseIcon(
    playing: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val reducedMotion = rememberReducedMotion()
    val pauseFraction by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            spring(dampingRatio = 0.82f, stiffness = 380f)
        },
        label = "playPauseMorph",
    )
    Canvas(
        modifier = modifier.semantics {
            if (contentDescription != null) this.contentDescription = contentDescription
        },
    ) {
        val path = Path()
        fun addQuad(quad: FloatArray) {
            path.moveTo(quad[0] * size.width, quad[1] * size.height)
            path.lineTo(quad[2] * size.width, quad[3] * size.height)
            path.lineTo(quad[4] * size.width, quad[5] * size.height)
            path.lineTo(quad[6] * size.width, quad[7] * size.height)
            path.close()
        }
        val (left, right) = playPauseMorphQuads(pauseFraction)
        addQuad(left)
        addQuad(right)
        drawPath(path, tint)
    }
}

// Corner order per quad: top-left, top-right, bottom-right, bottom-left, as
// fractions of the icon size. The triangle's right quad collapses to the tip.
private val LEFT_TRIANGLE = floatArrayOf(0.28f, 0.22f, 0.50f, 0.343f, 0.50f, 0.657f, 0.28f, 0.78f)
private val RIGHT_TRIANGLE = floatArrayOf(0.50f, 0.343f, 0.78f, 0.50f, 0.78f, 0.50f, 0.50f, 0.657f)
private val LEFT_BAR = floatArrayOf(0.19f, 0.20f, 0.41f, 0.20f, 0.41f, 0.80f, 0.19f, 0.80f)
private val RIGHT_BAR = floatArrayOf(0.59f, 0.20f, 0.81f, 0.20f, 0.81f, 0.80f, 0.59f, 0.80f)

internal fun playPauseMorphQuads(pauseFraction: Float): Pair<FloatArray, FloatArray> {
    val p = pauseFraction.coerceIn(0f, 1f)
    fun lerp(triangle: FloatArray, bar: FloatArray) =
        FloatArray(8) { triangle[it] + (bar[it] - triangle[it]) * p }
    return lerp(LEFT_TRIANGLE, LEFT_BAR) to lerp(RIGHT_TRIANGLE, RIGHT_BAR)
}

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
