package com.airsink.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airsink.ui.theme.Ios
import dev.chrisbanes.haze.HazeState

/**
 * A UINavigationController-style screen: large title that scrolls away, a compact title that
 * fades in over a frosted bar, and an optional back button.
 */
@Composable
fun IosScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Back",
    largeTitle: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val haze = remember { HazeState() }
    val listState = rememberLazyListState()
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val scrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 4 }
    }
    val compactTitleVisible by remember {
        derivedStateOf {
            !largeTitle || listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 90
        }
    }
    val barAlpha by animateFloatAsState(if (scrolled) 1f else 0f, label = "bar")
    val titleAlpha by animateFloatAsState(if (compactTitleVisible) 1f else 0f, label = "title")

    CompositionLocalProvider(LocalHazeState provides haze) {
        Box(modifier.fillMaxSize().background(Ios.colors.background)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().glassSource(haze),
                contentPadding = PaddingValues(top = statusBar + 44.dp, bottom = navBar + 40.dp),
            ) {
                if (largeTitle) {
                    item(key = "largeTitle") {
                        Text(
                            title,
                            style = Ios.type.largeTitle,
                            color = Ios.colors.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 2.dp),
                        )
                    }
                }
                content()
            }

            // Navigation bar
            Box(Modifier.fillMaxWidth().height(statusBar + 44.dp)) {
                Box(
                    Modifier
                        .matchParentSize()
                        .alpha(barAlpha)
                        .glass(haze),
                )
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .alpha(barAlpha)
                        .background(Ios.colors.separator),
                )
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (onBack != null) {
                            Row(
                                Modifier.bouncyClick(onClick = onBack).padding(horizontal = 4.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, null, tint = Ios.colors.blue, modifier = Modifier.size(20.dp))
                                Text(backLabel, style = Ios.type.body, color = Ios.colors.blue, maxLines = 1)
                            }
                        }
                    }
                    Text(
                        title,
                        style = Ios.type.headline,
                        color = Ios.colors.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(2f).alpha(titleAlpha),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                        content = actions,
                    )
                }
            }
        }
    }
}

/** Bumps whenever the screen resumes, so permission checks refresh after system dialogs. */
@Composable
fun rememberResumeTick(): Int {
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { tick++ }
    }
    return tick
}
