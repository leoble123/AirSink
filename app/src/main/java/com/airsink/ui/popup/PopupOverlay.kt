package com.airsink.ui.popup

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.airsink.AppGraph
import com.airsink.MainActivity
import com.airsink.airpods.ProximityMessage
import com.airsink.ui.components.BatteryRing
import com.airsink.ui.components.BudArt
import com.airsink.ui.components.CaseArt
import com.airsink.ui.components.CircleIconButton
import com.airsink.ui.components.FilledButton
import com.airsink.ui.components.HeadphonesArt
import com.airsink.ui.theme.AirSinkTheme
import com.airsink.ui.theme.Ios
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The iOS "AirPods nearby" card, drawn over whatever app is on screen when the case opens.
 * Needs the "Display over other apps" permission.
 */
class PopupOverlay(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val graph: AppGraph,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null

    fun show(message: ProximityMessage) {
        if (view != null) return
        val savedState = OverlaySavedState()
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedState)
            setContent {
                AirSinkTheme(graph.prefs.theme.value) {
                    PopupCard(message, graph, onConnect = ::connect, onDismiss = ::dismiss)
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        runCatching { wm.addView(v, params); view = v }
    }

    fun dismiss() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun connect() {
        val bonded = graph.airpods.bondedAppleDevices()
        val device = bonded.firstOrNull()
        when {
            device == null -> context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_PAIR, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            !graph.airpods.tryConnect(device) -> context.startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        dismiss()
    }

    private class OverlaySavedState : SavedStateRegistryOwner {
        private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

        init {
            controller.performRestore(Bundle())
            lifecycleRegistry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
        }
    }
}

@Composable
private fun PopupCard(
    message: ProximityMessage,
    graph: AppGraph,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val offset = remember { Animatable(900f) }
    val scope = rememberCoroutineScope()
    val live by graph.airpods.state.collectAsState()
    val connected = live?.connected == true

    fun close() = scope.launch {
        offset.animateTo(900f, tween(260))
        onDismiss()
    }

    LaunchedEffect(Unit) {
        offset.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 260f))
        delay(12_000)
        close()
    }

    val left = live?.left ?: message.left
    val right = live?.right ?: message.right
    val case = live?.case ?: message.case

    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(10.dp)
            .graphicsLayer { translationY = offset.value }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (offset.value > 120f) close() else scope.launch { offset.animateTo(0f, spring()) } },
                ) { change, dy ->
                    change.consume()
                    scope.launch { offset.snapTo((offset.value + dy).coerceAtLeast(0f)) }
                }
            }
            .shadow(30.dp, RoundedCornerShape(40.dp))
            .clip(RoundedCornerShape(40.dp))
            .background(Ios.colors.card),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                live?.name ?: message.model.name,
                style = Ios.type.title2,
                color = Ios.colors.label,
                textAlign = TextAlign.Center,
            )
            HeadphonesArt(message.model.formFactor, size = 150.dp, modifier = Modifier.padding(vertical = 12.dp))
            if (message.model.hasCase) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    BatteryRing(left.level, left.charging, "Left", size = 56.dp) { BudArt(false) }
                    BatteryRing(right.level, right.charging, "Right", size = 56.dp) { BudArt(true) }
                    BatteryRing(case.level, case.charging, "Case", size = 56.dp) { CaseArt() }
                }
            } else {
                BatteryRing(message.headset.level, message.headset.charging, "Battery", size = 64.dp)
            }
            Box(Modifier.padding(top = 22.dp).fillMaxWidth()) {
                if (connected) {
                    FilledButton("Done", onClick = { close() }, modifier = Modifier.fillMaxWidth())
                } else {
                    FilledButton("Connect", onClick = onConnect, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        CircleIconButton(
            Icons.Rounded.Close, onClick = { close() },
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )
    }
}
