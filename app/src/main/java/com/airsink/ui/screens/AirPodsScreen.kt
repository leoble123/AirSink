package com.airsink.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airsink.LocalActions
import com.airsink.LocalGraph
import com.airsink.airpods.AapState
import com.airsink.airpods.NoiseMode
import com.airsink.ui.components.BatteryRing
import com.airsink.ui.components.BudArt
import com.airsink.ui.components.CaseArt
import com.airsink.ui.components.CheckRow
import com.airsink.ui.components.HeadphonesArt
import com.airsink.ui.components.IosScaffold
import com.airsink.ui.components.ListRow
import com.airsink.ui.components.Section
import com.airsink.ui.components.SegmentedControl
import com.airsink.ui.components.ThinSlider
import com.airsink.ui.components.ToggleRow
import com.airsink.ui.theme.Ios

@Composable
fun AirPodsScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val stateOrNull by graph.airpods.state.collectAsState()
    val earDetection by graph.prefs.earDetection.flowValue.collectAsState()
    val ducking by graph.prefs.conversationDucking.flowValue.collectAsState()
    val popup by graph.prefs.connectionPopup.flowValue.collectAsState()
    val advanced by graph.prefs.advancedControls.flowValue.collectAsState()
    var renaming by remember { mutableStateOf(false) }

    val state = stateOrNull
    IosScaffold(title = state?.name ?: "AirPods", onBack = onBack, backLabel = "AirSink", largeTitle = false) {
        if (state == null) {
            item {
                Text(
                    "Your AirPods aren't nearby. Open the case next to your phone.",
                    style = Ios.type.body, color = Ios.colors.secondaryLabel, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(40.dp),
                )
            }
            return@IosScaffold
        }
        val aap = state.aap
        val controls = aap.connection == AapState.Connection.CONNECTED

        item(key = "hero") {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                HeadphonesArt(state.model.formFactor, size = 170.dp)
                Text(state.name, style = Ios.type.title2, color = Ios.colors.label)
                Text(state.model.name, style = Ios.type.subheadline, color = Ios.colors.secondaryLabel)
                Spacer(Modifier.height(18.dp))
                if (state.model.hasCase) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        BatteryRing(state.left.level, state.left.charging, if (state.leftInEar) "Left · In Ear" else "Left", size = 62.dp) { BudArt(false) }
                        BatteryRing(state.right.level, state.right.charging, if (state.rightInEar) "Right · In Ear" else "Right", size = 62.dp) { BudArt(true) }
                        BatteryRing(state.case.level, state.case.charging, "Case", size = 62.dp) { CaseArt() }
                    }
                } else {
                    BatteryRing(state.headset.level, state.headset.charging, "Battery", size = 70.dp)
                }
            }
        }

        if (state.model.anc) {
            item(key = "noise") {
                val modes = noiseModesFor(state)
                Section(
                    header = "Noise Control",
                    footer = if (!controls) controlsFooter(aap, advanced) else null,
                ) {
                    Column(Modifier.padding(12.dp)) {
                        SegmentedControl(
                            options = modes.map { it.short },
                            selectedIndex = modes.indexOfFirst { it.mode == aap.noiseMode },
                            onSelect = { if (controls) graph.airpods.aap.setNoiseMode(modes[it].mode) },
                        )
                    }
                }
            }
        }

        if (controls && state.model.adaptive && aap.noiseMode == NoiseMode.ADAPTIVE) {
            item(key = "adaptive") {
                var strength by remember(aap.adaptiveStrength) { mutableFloatStateOf((aap.adaptiveStrength ?: 50).toFloat()) }
                Section(header = "Adaptive Audio", footer = "Adjust how much sound Adaptive mode lets in.") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row {
                            Text("Less Noise", style = Ios.type.footnote, color = Ios.colors.secondaryLabel, modifier = Modifier.weight(1f))
                            Text("More Noise", style = Ios.type.footnote, color = Ios.colors.secondaryLabel)
                        }
                        ThinSlider(
                            value = strength, range = 0f..100f,
                            onValueChange = { strength = it },
                            onValueChangeFinished = { graph.airpods.aap.setAdaptiveStrength(strength.toInt()) },
                        )
                    }
                }
            }
        }

        if (controls && state.model.conversationalAwareness) {
            item(key = "audio") {
                Section(
                    header = "Audio",
                    footer = "Conversational Awareness lowers media and boosts voices when you start talking. Personalized Volume adapts to your environment.",
                ) {
                    ToggleRow(
                        "Conversational Awareness", aap.conversationalAwareness == true,
                        graph.airpods.aap::setConversationalAwareness, icon = Icons.Rounded.RecordVoiceOver, iconColor = Ios.colors.indigo,
                    )
                    ToggleRow(
                        "Personalized Volume", aap.personalizedVolume == true,
                        graph.airpods.aap::setPersonalizedVolume, icon = Icons.AutoMirrored.Rounded.VolumeDown, iconColor = Ios.colors.pink,
                        showSeparator = false,
                    )
                }
            }
        }

        if (controls && state.model.anc) {
            item(key = "cycle") {
                val mask = aap.cycleModes ?: 0x06
                fun toggle(bit: Int) {
                    val next = mask xor bit
                    // AirPods require at least two modes in the cycle.
                    if (Integer.bitCount(next) >= 2) graph.airpods.aap.setCycleModes(next)
                }
                Section(header = "Press and Hold AirPods", footer = "Choose the listening modes that pressing and holding the stem cycles through.") {
                    CheckRow("Noise Cancellation", mask and 0x02 != 0) { toggle(0x02) }
                    CheckRow("Transparency", mask and 0x04 != 0) { toggle(0x04) }
                    if (state.model.adaptive) CheckRow("Adaptive", mask and 0x08 != 0) { toggle(0x08) }
                    CheckRow("Off", mask and 0x01 != 0, showSeparator = false) { toggle(0x01) }
                }
            }
            item(key = "accessibility") {
                Section(header = "Accessibility") {
                    ToggleRow(
                        "Noise Cancellation with One AirPod", aap.oneBudAnc == true,
                        graph.airpods.aap::setOneBudAnc, icon = Icons.Rounded.Hearing, iconColor = Ios.colors.blue,
                    )
                    ToggleRow(
                        "Volume Swipe", aap.volumeSwipe == true,
                        graph.airpods.aap::setVolumeSwipe, icon = Icons.Rounded.Swipe, iconColor = Ios.colors.gray,
                    )
                    ToggleRow(
                        "Allow “Off” Listening Mode", aap.allowOffOption != false,
                        graph.airpods.aap::setAllowOffOption, icon = Icons.Rounded.TouchApp, iconColor = Ios.colors.orange,
                        showSeparator = false,
                    )
                }
            }
        }

        item(key = "phone") {
            Section(
                header = "On This Phone",
                footer = "Automatic Ear Detection pauses media when you take an AirPod out and resumes when you put it back.",
            ) {
                ToggleRow("Automatic Ear Detection", earDetection, graph.prefs.earDetection::set, icon = Icons.Rounded.Hearing, iconColor = Ios.colors.green)
                if (state.model.conversationalAwareness) {
                    ToggleRow("Lower Media When Talking", ducking, graph.prefs.conversationDucking::set, icon = Icons.Rounded.RecordVoiceOver, iconColor = Ios.colors.indigo)
                }
                ToggleRow("Connection Pop-up", popup, graph.prefs.connectionPopup::set, icon = Icons.Rounded.PictureInPicture, iconColor = Ios.colors.blue, showSeparator = false)
            }
        }

        item(key = "connection") {
            Section(header = "Connection", footer = controlsFooter(aap, advanced)) {
                ListRow(
                    "Advanced Controls",
                    icon = Icons.Rounded.Refresh, iconColor = Ios.colors.teal,
                    value = when (aap.connection) {
                        AapState.Connection.CONNECTED -> "Active"
                        AapState.Connection.CONNECTING -> null
                        AapState.Connection.UNSUPPORTED -> "Unavailable"
                        AapState.Connection.DISCONNECTED -> if (state.connected) "Tap to Retry" else "Off"
                    },
                    onClick = if (state.connected && aap.connection != AapState.Connection.CONNECTED) graph.airpods::reconnectControls else null,
                ) {
                    if (aap.connection == AapState.Connection.CONNECTING) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ios.colors.secondaryLabel)
                    }
                }
                if (controls) {
                    ListRow("Name", icon = Icons.Rounded.Edit, iconColor = Ios.colors.gray, value = state.name, chevron = true, onClick = { renaming = true })
                }
                ListRow("Bluetooth Settings", icon = Icons.Rounded.Bluetooth, iconColor = Ios.colors.blue, chevron = true, onClick = actions::openBluetoothSettings, showSeparator = false)
            }
        }

        item(key = "about") {
            Section(header = "About") {
                ListRow("Model", icon = Icons.Rounded.Info, iconColor = Ios.colors.gray, value = state.model.name)
                ListRow("Bluetooth Address", value = state.address ?: "—", showSeparator = false)
            }
        }
    }

    if (renaming && state != null) {
        var name by remember { mutableStateOf(state.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename", style = Ios.type.headline) },
            text = { OutlinedTextField(name, { name = it.take(32) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { graph.airpods.aap.rename(name.trim()); renaming = false }) { Text("Done", color = Ios.colors.blue) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel", color = Ios.colors.blue) } },
            containerColor = Ios.colors.card,
        )
    }
}

private fun controlsFooter(aap: AapState, enabled: Boolean): String? = when {
    !enabled -> "Advanced controls are turned off in Settings."
    aap.connection == AapState.Connection.UNSUPPORTED ->
        "This Android build blocks the Bluetooth channel AirPods use for settings, so noise control and other options can't be changed from the phone. Battery, ear detection and the pop-up still work."
    aap.connection == AapState.Connection.CONNECTED -> null
    else -> "Connect your AirPods to change their settings."
}
