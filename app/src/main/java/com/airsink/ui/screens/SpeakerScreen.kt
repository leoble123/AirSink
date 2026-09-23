package com.airsink.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Airplay
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airsink.LocalActions
import com.airsink.LocalGraph
import com.airsink.airplay.AirPlayKind
import com.airsink.cast.CastTarget
import com.airsink.cast.SinkState
import com.airsink.ui.components.FatSlider
import com.airsink.ui.components.FilledButton
import com.airsink.ui.components.IosScaffold
import com.airsink.ui.components.ListRow
import com.airsink.ui.components.Section
import com.airsink.ui.components.SpeakerArt
import com.airsink.ui.theme.Ios

@Composable
fun SpeakerScreen(id: String, onBack: () -> Unit) {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val devices by graph.airplay.devices.collectAsState()
    val active by graph.cast.active.collectAsState()
    val device = devices[id]

    IosScaffold(title = device?.name ?: "Speaker", onBack = onBack, backLabel = "AirSink", largeTitle = false) {
        if (device == null) {
            item { Text("This speaker is no longer on the network.", style = Ios.type.body, color = Ios.colors.secondaryLabel, modifier = Modifier.padding(32.dp)) }
            return@IosScaffold
        }
        val target = CastTarget.AirPlay(device)
        val sink = active[target.id]

        item(key = "hero") {
            val state = sink?.state?.collectAsState()?.value
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SpeakerArt(device.kind, size = 150.dp, active = state == SinkState.Streaming)
                Spacer(Modifier.height(10.dp))
                Text(device.name, style = Ios.type.title2, color = Ios.colors.label)
                Text(kindName(device.kind, device.model), style = Ios.type.subheadline, color = Ios.colors.secondaryLabel)
                Spacer(Modifier.height(20.dp))
                FilledButton(
                    text = if (sink == null) "Play Phone Audio Here" else "Stop Playing",
                    icon = if (sink == null) Icons.Rounded.Airplay else Icons.Rounded.Stop,
                    color = if (sink == null) Ios.colors.blue else Ios.colors.red,
                    onClick = { actions.toggleCast(target) },
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                )
                if (state is SinkState.Failed) {
                    Text(state.message, style = Ios.type.footnote, color = Ios.colors.red, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
                }
            }
        }

        if (sink != null) {
            item(key = "volume") {
                val volume by sink.volume.collectAsState()
                Section(header = "Volume") {
                    Column(Modifier.padding(12.dp)) {
                        FatSlider(volume, sink::setVolume, icon = Icons.AutoMirrored.Rounded.VolumeUp)
                    }
                }
            }
        }

        item(key = "info") {
            Section(
                header = "Details",
                footer = if (device.kind == AirPlayKind.HOMEPOD || device.kind == AirPlayKind.HOMEPOD_MINI)
                    "If HomePod won't connect, open the Home app on an Apple device → Home Settings → Speakers & TV, and allow access for “Everyone” or “Anyone on the Same Network” without a password."
                else null,
            ) {
                ListRow("Model", icon = Icons.Rounded.Speaker, iconColor = Ios.colors.gray, value = device.model.ifEmpty { "—" })
                ListRow("IP Address", icon = Icons.Rounded.Lan, iconColor = Ios.colors.blue, value = device.host)
                ListRow("AirPlay 2", icon = Icons.Rounded.Airplay, iconColor = Ios.colors.indigo, value = if (device.supportsAirPlay2) "Yes" else "No")
                ListRow("Password", icon = Icons.Rounded.Lock, iconColor = Ios.colors.orange, value = if (device.requiresPassword) "Required" else "None", showSeparator = false)
            }
        }
    }
}

fun kindName(kind: AirPlayKind, model: String) = when (kind) {
    AirPlayKind.HOMEPOD -> if (model.startsWith("AudioAccessory6")) "HomePod (2nd generation)" else "HomePod"
    AirPlayKind.HOMEPOD_MINI -> "HomePod mini"
    AirPlayKind.APPLE_TV -> "Apple TV"
    AirPlayKind.AIRPORT -> "AirPort Express"
    AirPlayKind.SONOS -> "Sonos"
    AirPlayKind.SPEAKER -> "AirPlay Speaker"
}
