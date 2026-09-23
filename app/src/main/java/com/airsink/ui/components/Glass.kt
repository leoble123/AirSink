package com.airsink.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.airsink.ui.theme.Ios
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/** The scrolling content that frosted bars blur. Provided by each screen scaffold. */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/** Marks content that glass surfaces above it should blur. */
fun Modifier.glassSource(state: HazeState): Modifier = hazeSource(state)

/** iOS "regular material": a strong backdrop blur with a translucent tint. */
@Composable
fun Modifier.glass(state: HazeState?, tint: Color = Ios.colors.material): Modifier =
    if (state == null) this
    else hazeEffect(
        state = state,
        style = HazeStyle(
            backgroundColor = Ios.colors.background,
            tint = HazeTint(tint),
            blurRadius = 30.dp,
            noiseFactor = 0f,
        ),
    )
