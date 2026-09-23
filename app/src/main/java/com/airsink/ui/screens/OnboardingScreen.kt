package com.airsink.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Airplay
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airsink.LocalActions
import com.airsink.LocalGraph
import com.airsink.airpods.FormFactor
import com.airsink.ui.components.FilledButton
import com.airsink.ui.components.HeadphonesArt
import com.airsink.ui.theme.Ios
import kotlinx.coroutines.delay

/** An iOS "What's New"-style welcome sheet. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val features = listOf(
        Feature(Icons.Rounded.Headphones, Ios.colors.blue, "AirPods, Beautifully", "Battery for each AirPod and the case, a pop-up when you open the case, and automatic ear detection."),
        Feature(Icons.Rounded.BatteryChargingFull, Ios.colors.green, "Noise Control & More", "Switch between Noise Cancellation, Transparency and Adaptive, and turn on Conversational Awareness."),
        Feature(Icons.Rounded.Airplay, Ios.colors.indigo, "HomePod with AirPlay", "Play anything on your phone through HomePod, HomePod mini and other AirPlay speakers."),
        Feature(Icons.Rounded.Speaker, Ios.colors.orange, "Sonos, Too", "Stream to Sonos, group rooms, and adjust EQ, Night Sound and more."),
    )

    Box(Modifier.fillMaxSize().background(Ios.colors.card)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            HeadphonesArt(FormFactor.EARBUDS, size = 110.dp)
            Spacer(Modifier.height(16.dp))
            Text("Welcome to AirSink", style = Ios.type.largeTitle, color = Ios.colors.label, textAlign = TextAlign.Center)
            Spacer(Modifier.height(36.dp))
            features.forEachIndexed { i, f -> FeatureRow(f, delayMs = 120L * i) }
            Spacer(Modifier.height(24.dp))
            Text(
                "AirSink uses Bluetooth to talk to your AirPods and your Wi-Fi network to find speakers. Nothing leaves your home network.",
                style = Ios.type.caption1, color = Ios.colors.secondaryLabel, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            FilledButton(
                "Continue",
                onClick = {
                    graph.prefs.onboarded.set(true)
                    actions.requestPermissions()
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

private data class Feature(val icon: ImageVector, val color: Color, val title: String, val body: String)

@Composable
private fun FeatureRow(f: Feature, delayMs: Long) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(200 + delayMs)
        anim.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 200f))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 26.dp)
            .graphicsLayer { alpha = anim.value; translationY = (1 - anim.value) * 40f },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(f.icon, null, tint = f.color, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(18.dp))
        Column {
            Text(f.title, style = Ios.type.headline, color = Ios.colors.label)
            Text(f.body, style = Ios.type.subheadline, color = Ios.colors.secondaryLabel)
        }
    }
}
