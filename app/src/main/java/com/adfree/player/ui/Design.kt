package com.adfree.player.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Motion + material tokens, modelled on Apple's fluid-interface guidelines:
 * springs (not timed curves) so every animation is interruptible, critically
 * damped by default, a little bounce only where a gesture carried momentum.
 */
object Motion {
    // Apple "response" 0.35s, damping 1.0  -> stiffness ≈ (2π / 0.35)² ≈ 320
    fun <T> smooth() = spring<T>(dampingRatio = 1f, stiffness = 320f)
    // Snappier UI (press states, small chips): response ≈ 0.25s
    fun <T> snappy() = spring<T>(dampingRatio = 1f, stiffness = 630f)
    // Drawer/sheet after a flick: damping 0.8, response 0.3
    fun <T> bouncy() = spring<T>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
}

object Glass {
    /** Dark translucent material for floating controls over video. */
    val fill = Color(0xB3141416)
    /** Thin bright edge = light catching the material. */
    val edge = Color(0x26FFFFFF)
}

/** Translucent "glass" capsule. Compose can't blur what's behind it, so we use tint + edge. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(50)): Modifier =
    this.background(Glass.fill, shape).border(0.5.dp, Glass.edge, shape)

/**
 * Tap target that responds on press-down (scale + dim), not on release,
 * and fires a light haptic on commit.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressable(
    haptic: Boolean = true,
    pressedScale: Float = 0.94f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, Motion.snappy(), label = "press")
    val alpha by animateFloatAsState(if (pressed) 0.7f else 1f, Motion.snappy(), label = "pressAlpha")
    val hf = LocalHapticFeedback.current
    this
        .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
        .combinedClickable(
            interactionSource = source,
            indication = null,
            onLongClick = onLongClick?.let { lc ->
                { hf.performHapticFeedback(HapticFeedbackType.LongPress); lc() }
            },
        ) {
            if (haptic) hf.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

/** Apple-style type scale: tight negative tracking on large text, neutral on body. */
object Type {
    val largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
    val title = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp)
    val subhead = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp)
}

@Composable
fun rememberHaptics() = LocalHapticFeedback.current
