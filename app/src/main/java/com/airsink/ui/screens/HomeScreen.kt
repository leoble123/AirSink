package com.airsink.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airsink.LocalActions
import com.airsink.LocalGraph
import com.airsink.airplay.AirPlayDevice
import com.airsink.airplay.AirPlayKind
import com.airsink.airpods.AapState
import com.airsink.airpods.HeadphonesState
import com.airsink.airpods.NoiseMode
import com.airsink.cast.AudioSink
import com.airsink.cast.CastTarget
import com.airsink.cast.SinkState
import com.airsink.core.Permissions
import com.airsink.sonos.SonosGroup
import com.airsink.ui.components.BatteryRing
import com.airsink.ui.components.BudArt
import com.airsink.ui.components.CaseArt
import com.airsink.ui.components.CircleIconButton
import com.airsink.ui.components.FatSlider
import com.airsink.ui.components.FilledButton
import com.airsink.ui.components.HeadphonesArt
import com.airsink.ui.components.SectionHeader
import com.airsink.ui.components.SegmentedControl
import com.airsink.ui.components.SonosArt
import com.airsink.ui.components.SpeakerArt
import com.airsink.ui.components.TintedButton
import com.airsink.ui.components.bouncyClick
import com.airsink.ui.components.IosScaffold
import com.airsink.ui.theme.Ios

@Composable
fun HomeScreen(
    openHeadphones: () -> Unit,
    openOnePlus: () -> Unit,
    openSpeaker: (String) -> Unit,
    openSonos: (String) -> Unit,
    openSettings: () -> Unit,
) {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val context = LocalContext.current
    val headphones by graph.airpods.state.collectAsState()
    val melody by graph.melody.state.collectAsState()
    val airplay by graph.airplay.devices.collectAsState()
    val sonos by graph.sonos.groups.collectAsState()
    val active by graph.cast.active.collectAsState()
    val error by graph.cast.errors.collectAsState()
    val resumeTick = com.airsink.ui.components.rememberResumeTick()
    val hasBluetooth = remember(resumeTick, headphones) { Permissions.hasBluetooth(context) }

    val sonosStatus by graph.sonos.status.collectAsState()

    // Sonos speakers also advertise AirPlay; once their Sonos room shows up, list them there
    // with the full controls instead. Until then they stay here so they're always reachable.
    val sonosNames = sonos.flatMap { g -> g.members.map { it.room } }.toSet()
    val speakers = airplay.values
        .filter { it.name !in sonosNames }
        .sortedWith(compareBy({ it.kind.ordinal }, { it.name }))

    IosScaffold(
        title = "AirSink",
        actions = { CircleIconButton(Icons.Rounded.Settings, onClick = openSettings, size = 34.dp, tint = Ios.colors.blue) },
    ) {
        if (!hasBluetooth) {
            item { PermissionCard(onAllow = actions::requestPermissions) }
        }

        item(key = "headphones") {
            SectionHeader("Headphones")
            // Skip the "no AirPods" placeholder when other earbuds are connected.
            if (headphones != null || melody == null) HeadphonesCard(headphones, onOpen = openHeadphones)
            melody?.let { MelodyCard(it, onOpen = openOnePlus) }
        }

        item(key = "streaming") {
            AnimatedVisibility(
                visible = active.isNotEmpty() || error != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    SectionHeader("Now Streaming")
                    StreamingCard(active.values.toList(), error, onStop = graph.cast::remove, onDismissError = graph.cast::clearError)
                }
            }
        }

        item(key = "airplayHeader") { SectionHeader("HomePod & AirPlay") }
        if (speakers.isEmpty()) {
            item { SearchingCard("Looking for HomePods and AirPlay speakers on your Wi-Fi…") }
        } else {
            val rows = speakers.chunked(2)
            rows.forEach { pair ->
                item(key = "ap-" + pair.joinToString { it.id }) {
                    TileRow {
                        pair.forEach { d ->
                            val target = CastTarget.AirPlay(d)
                            SpeakerTile(
                                name = d.name,
                                subtitle = subtitleFor(active[target.id], d.kind),
                                active = active.containsKey(target.id),
                                art = { a -> SpeakerArt(d.kind, size = 40.dp, active = a) },
                                onClick = { actions.toggleCast(target) },
                                onLongClick = { openSpeaker(d.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        item(key = "sonosHeader") { SectionHeader("Sonos") }
        if (sonos.isEmpty()) {
            item { SearchingCard(sonosStatus ?: "Looking for Sonos speakers…") }
        } else {
            sonos.chunked(2).forEach { pair ->
                item(key = "sonos-" + pair.joinToString { it.coordinator.uuid }) {
                    TileRow {
                        pair.forEach { g ->
                            val target = sonosTarget(g, airplay.values)
                            SpeakerTile(
                                name = g.name,
                                subtitle = subtitleFor(active[target.id], null, g.coordinator.model),
                                active = active.containsKey(target.id),
                                art = { SonosArt(size = 40.dp) },
                                onClick = { actions.toggleCast(target) },
                                onLongClick = { openSonos(g.coordinator.uuid) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        item {
            Text(
                "Tap a speaker to play your phone's audio on it. Tap more than one for multi-room. Press and hold for controls.",
                style = Ios.type.footnote,
                color = Ios.colors.secondaryLabel,
                modifier = Modifier.padding(horizontal = 36.dp, vertical = 12.dp),
            )
        }
    }
}

/** Prefer AirPlay for Sonos when the speaker supports it: tighter timing and lower latency. */
fun sonosTarget(group: SonosGroup, airplay: Collection<AirPlayDevice>): CastTarget {
    val ap = airplay.firstOrNull { it.name == group.coordinator.room && it.supportsAirPlay2 }
    return if (ap != null) CastTarget.AirPlay(ap) else CastTarget.Sonos(group)
}

@Composable
fun subtitleFor(sink: AudioSink?, kind: AirPlayKind?, fallback: String? = null): String {
    val idle = remember { kotlinx.coroutines.flow.MutableStateFlow<SinkState>(SinkState.Stopped) }
    val state by (sink?.state ?: idle).collectAsState()
    return subtitleText(state, kind, fallback)
}

private fun subtitleText(state: SinkState, kind: AirPlayKind?, fallback: String?): String = when (state) {
    is SinkState.Connecting -> "Connecting…"
    is SinkState.Streaming -> "Playing from phone"
    is SinkState.Failed -> "Couldn't connect"
    else -> fallback ?: when (kind) {
        AirPlayKind.HOMEPOD -> "HomePod"
        AirPlayKind.HOMEPOD_MINI -> "HomePod mini"
        AirPlayKind.APPLE_TV -> "Apple TV"
        AirPlayKind.AIRPORT -> "AirPort Express"
        else -> "AirPlay"
    }
}

// ---- Headphones --------------------------------------------------------------------

@Composable
private fun HeadphonesCard(state: HeadphonesState?, onOpen: () -> Unit) {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .bouncyClick(enabled = state != null, onClick = onOpen)
            .clip(RoundedCornerShape(22.dp))
            .background(Ios.colors.card)
            .padding(18.dp),
    ) {
        if (state == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeadphonesArt(com.airsink.airpods.FormFactor.EARBUDS, size = 64.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("No AirPods Nearby", style = Ios.type.headline, color = Ios.colors.label)
                    Text("Open the case next to your phone.", style = Ios.type.subheadline, color = Ios.colors.secondaryLabel)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TintedButton("Pair New", onClick = actions::pairNewHeadphones, modifier = Modifier.weight(1f))
                TintedButton("Bluetooth", onClick = actions::openBluetoothSettings, modifier = Modifier.weight(1f), icon = Icons.Rounded.Bluetooth)
            }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            HeadphonesArt(state.model.formFactor, size = 72.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(state.name, style = Ios.type.title3, color = Ios.colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = when {
                    state.connected && state.anyInEar -> "Connected · In Ear"
                    state.connected -> "Connected"
                    state.nearby -> "Nearby · Not Connected"
                    else -> "Not Connected"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(8.dp).background(
                            if (state.connected) Ios.colors.green else Ios.colors.gray, CircleShape,
                        ),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(status, style = Ios.type.subheadline, color = Ios.colors.secondaryLabel)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (state.model.hasCase) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BatteryRing(state.left.level, state.left.charging, "Left", size = 54.dp) { BudArt(false, size = 24.dp) }
                BatteryRing(state.right.level, state.right.charging, "Right", size = 54.dp) { BudArt(true, size = 24.dp) }
                BatteryRing(state.case.level, state.case.charging, "Case", size = 54.dp) { CaseArt(size = 24.dp) }
            }
        } else {
            BatteryRing(state.headset.level, state.headset.charging, "Battery", size = 54.dp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        if (!state.connected) {
            Spacer(Modifier.height(14.dp))
            FilledButton(
                "Connect",
                onClick = {
                    val device = graph.airpods.bondedAppleDevices().firstOrNull()
                    when {
                        device == null -> actions.pairNewHeadphones()
                        !graph.airpods.tryConnect(device) -> actions.openBluetoothSettings()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (state.aap.connection == AapState.Connection.CONNECTED && state.model.anc) {
            Spacer(Modifier.height(16.dp))
            val modes = noiseModesFor(state)
            SegmentedControl(
                options = modes.map { it.short },
                selectedIndex = modes.indexOfFirst { it.mode == state.aap.noiseMode },
                onSelect = { graph.airpods.aap.setNoiseMode(modes[it].mode) },
            )
        }
    }
}

data class NoiseOption(val mode: NoiseMode, val short: String)

fun noiseModesFor(state: HeadphonesState): List<NoiseOption> = buildList {
    if (state.aap.allowOffOption != false) add(NoiseOption(NoiseMode.OFF, "Off"))
    add(NoiseOption(NoiseMode.TRANSPARENCY, "Transparency"))
    if (state.model.adaptive) add(NoiseOption(NoiseMode.ADAPTIVE, "Adaptive"))
    add(NoiseOption(NoiseMode.ANC, "Noise Cancel"))
}

// ---- Streaming ---------------------------------------------------------------------

@Composable
private fun StreamingCard(sinks: List<AudioSink>, error: String?, onStop: (String) -> Unit, onDismissError: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Ios.colors.card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (error != null) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = Ios.colors.red, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(error, style = Ios.type.subheadline, color = Ios.colors.label, modifier = Modifier.weight(1f))
                CircleIconButton(Icons.Rounded.Close, onClick = onDismissError, size = 24.dp)
            }
        }
        sinks.forEach { sink ->
            val state by sink.state.collectAsState()
            val volume by sink.volume.collectAsState()
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sink.displayName, style = Ios.type.headline, color = Ios.colors.label)
                        Text(
                            when (state) {
                                SinkState.Connecting -> "Connecting…"
                                SinkState.Streaming -> "Playing your phone's audio"
                                is SinkState.Failed -> (state as SinkState.Failed).message
                                SinkState.Stopped -> "Stopped"
                            },
                            style = Ios.type.footnote,
                            color = Ios.colors.secondaryLabel,
                        )
                    }
                    if (state == SinkState.Connecting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ios.colors.secondaryLabel)
                        Spacer(Modifier.width(10.dp))
                    }
                    CircleIconButton(Icons.Rounded.Close, onClick = { onStop(sink.id) })
                }
                Spacer(Modifier.height(10.dp))
                FatSlider(volume, onValueChange = sink::setVolume, icon = Icons.AutoMirrored.Rounded.VolumeUp, height = 40.dp)
            }
        }
    }
}

// ---- Tiles -------------------------------------------------------------------------

@Composable
private fun TileRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** A Home-app style accessory tile. Active tiles turn bright, like a light that's on. */
@Composable
fun SpeakerTile(
    name: String,
    subtitle: String,
    active: Boolean,
    art: @Composable (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        when {
            active -> if (Ios.colors.isDark) Color(0xFFF2F2F7) else Color.White
            Ios.colors.isDark -> Ios.colors.card
            else -> Color(0xFFE3E3E8) // Home app: "off" tiles are muted, "on" tiles pop white
        },
        label = "tile",
    )
    val text = if (active) Color.Black else Ios.colors.label
    val sub = if (active) Color(0x993C3C43) else Ios.colors.secondaryLabel
    Row(
        modifier
            .height(76.dp)
            .bouncyClick(onLongClick = onLongClick, onClick = onClick)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        art(active)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = Ios.type.subheadline.copy(fontWeight = FontWeight.SemiBold), color = text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = Ios.type.caption1, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SearchingCard(text: String) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Ios.colors.card)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ios.colors.secondaryLabel)
        Spacer(Modifier.width(12.dp))
        Text(text, style = Ios.type.subheadline, color = Ios.colors.secondaryLabel, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.Wifi, null, tint = Ios.colors.tertiaryLabel, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PermissionCard(onAllow: () -> Unit) {
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Ios.colors.blue.copy(alpha = 0.12f))
            .padding(18.dp),
    ) {
        Text("Allow Nearby Devices", style = Ios.type.headline, color = Ios.colors.label)
        Text(
            "AirSink needs Bluetooth access to see your earbuds' battery and settings, and local network access to find speakers.",
            style = Ios.type.subheadline, color = Ios.colors.secondaryLabel,
        )
        Spacer(Modifier.height(12.dp))
        FilledButton("Continue", onClick = onAllow, modifier = Modifier.fillMaxWidth())
    }
}
