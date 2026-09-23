package com.airsink.ui.screens

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
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airsink.LocalActions
import com.airsink.LocalGraph
import com.airsink.melody.AncLevel
import com.airsink.melody.Gesture
import com.airsink.melody.MelodyProtocol
import com.airsink.melody.WearState
import com.airsink.melody.MelodyAnc
import com.airsink.melody.MelodyBattery
import com.airsink.melody.MelodyState
import com.airsink.melody.TouchActions
import com.airsink.melody.TouchSide
import com.airsink.ui.components.BatteryRing
import com.airsink.ui.components.CheckRow
import com.airsink.ui.components.FilledButton
import com.airsink.ui.components.IosScaffold
import com.airsink.ui.components.ListRow
import com.airsink.ui.components.OnePlusBudArt
import com.airsink.ui.components.OnePlusBudsArt
import com.airsink.ui.components.PebbleCaseArt
import com.airsink.ui.components.Section
import com.airsink.ui.components.SegmentedControl
import com.airsink.ui.components.ToggleRow
import com.airsink.ui.components.bouncyClick
import com.airsink.ui.theme.Ios

private val ancOrder = listOf(MelodyAnc.OFF, MelodyAnc.TRANSPARENCY, MelodyAnc.ON)

// ---- Home card ------------------------------------------------------------------------

@Composable
fun MelodyCard(state: MelodyState, onOpen: () -> Unit) {
    val graph = LocalGraph.current
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .fillMaxWidth()
            .bouncyClick(onClick = onOpen)
            .clip(RoundedCornerShape(22.dp))
            .background(Ios.colors.card)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OnePlusBudsArt(size = 72.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(state.name, style = Ios.type.title3, color = Ios.colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(Ios.colors.green, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(statusText(state), style = Ios.type.subheadline, color = Ios.colors.secondaryLabel)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        MelodyBatteries(state, ringSize = 54)
        if (state.connection == MelodyState.Connection.CONNECTED && state.supportsAnc) {
            Spacer(Modifier.height(16.dp))
            AncSelector(state) { graph.melody.setAnc(it) }
        }
    }
}

private fun statusText(state: MelodyState) = when (state.connection) {
    MelodyState.Connection.CONNECTED -> "Connected"
    MelodyState.Connection.CONNECTING -> "Connecting…"
    else -> "Connected · Limited controls"
}

@Composable
private fun MelodyBatteries(state: MelodyState, ringSize: Int) {
    fun lvl(b: MelodyBattery?) = b?.level
    fun chg(b: MelodyBattery?) = b?.charging == true
    fun label(side: String, w: WearState?) = when (w) {
        WearState.IN_EAR -> "$side · In Ear"
        WearState.IN_CASE -> "$side · In Case"
        else -> side
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        BatteryRing(lvl(state.left), chg(state.left), label("Left", state.leftWear), size = ringSize.dp) { OnePlusBudArt(false, size = (ringSize * 0.45f).dp) }
        BatteryRing(lvl(state.right), chg(state.right), label("Right", state.rightWear), size = ringSize.dp) { OnePlusBudArt(true, size = (ringSize * 0.45f).dp) }
        BatteryRing(lvl(state.case), chg(state.case), "Case", size = ringSize.dp) { PebbleCaseArt(size = (ringSize * 0.45f).dp) }
    }
}

@Composable
private fun AncSelector(state: MelodyState, onSelect: (MelodyAnc) -> Unit) {
    // Models with several ANC strengths report codes we don't name; treat them as "Noise Cancel".
    val current = state.anc ?: if (state.ancRaw != null) MelodyAnc.ON else null
    SegmentedControl(
        options = ancOrder.map { it.label },
        selectedIndex = ancOrder.indexOf(current),
        onSelect = { onSelect(ancOrder[it]) },
    )
}

// ---- Detail screen --------------------------------------------------------------------

@Composable
fun OnePlusScreen(onBack: () -> Unit, openGesture: (TouchSide, Gesture) -> Unit) {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val stateOrNull by graph.melody.state.collectAsState()
    val state = stateOrNull

    IosScaffold(title = state?.name ?: "Earbuds", onBack = onBack, backLabel = "AirSink", largeTitle = false) {
        if (state == null) {
            item {
                Text(
                    "Your earbuds aren't connected. Connect them in Bluetooth settings first.",
                    style = Ios.type.body, color = Ios.colors.secondaryLabel, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(40.dp),
                )
            }
            return@IosScaffold
        }
        val live = state.connection == MelodyState.Connection.CONNECTED

        item(key = "hero") {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                OnePlusBudsArt(size = 170.dp)
                Text(state.name, style = Ios.type.title2, color = Ios.colors.label)
                Text(
                    listOfNotNull(state.brand.label, state.firmware?.let { "Firmware $it" }).joinToString(" · "),
                    style = Ios.type.subheadline, color = Ios.colors.secondaryLabel,
                )
                Spacer(Modifier.height(18.dp))
                MelodyBatteries(state, ringSize = 62)
            }
        }

        if (!live) {
            item(key = "status") {
                Section(
                    footer = if (state.connection == MelodyState.Connection.FAILED)
                        "These earbuds didn't open their control channel. Some models use a different protocol, and HeyMelody or another companion app may be holding the connection; close it and try again."
                    else null,
                ) {
                    ListRow(
                        "Controls",
                        icon = Icons.Rounded.Refresh, iconColor = Ios.colors.teal,
                        value = if (state.connection == MelodyState.Connection.CONNECTING) null else "Tap to Retry",
                        onClick = graph.melody::reconnect,
                        showSeparator = false,
                    ) {
                        if (state.connection == MelodyState.Connection.CONNECTING) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ios.colors.secondaryLabel)
                        }
                    }
                }
            }
        }

        if (live && state.supportsAnc) {
            item(key = "anc") {
                Section(header = "Noise Control") {
                    Column(Modifier.padding(12.dp)) { AncSelector(state) { graph.melody.setAnc(it) } }
                }
            }
            if (state.isOnePlus && state.anc == MelodyAnc.ON) {
                item(key = "level") {
                    Section(header = "Noise Cancellation Level", footer = "Smart adjusts to your surroundings. Levels your model doesn't have are ignored by the earbuds.") {
                        Column(Modifier.padding(12.dp)) {
                            SegmentedControl(
                                options = AncLevel.entries.map { it.label },
                                selectedIndex = AncLevel.entries.indexOf(state.ancLevel),
                                onSelect = { graph.melody.setAnc(MelodyAnc.ON, AncLevel.entries[it]) },
                            )
                        }
                    }
                }
            }
            item(key = "cycle") {
                fun bit(m: MelodyAnc) = MelodyProtocol.cycleBit(m, state.brand)
                val mask = state.cycleMask ?: (bit(MelodyAnc.ON) or bit(MelodyAnc.TRANSPARENCY))
                fun toggle(mode: MelodyAnc) {
                    val next = mask xor bit(mode)
                    // Keep at least two of the three basic modes in the cycle.
                    val basics = next and (bit(MelodyAnc.OFF) or bit(MelodyAnc.ON) or bit(MelodyAnc.TRANSPARENCY))
                    if (Integer.bitCount(basics) >= 2) graph.melody.setCycleMask(next)
                }
                Section(header = "Noise Control Switching", footer = "Choose the modes the noise-control gesture cycles through.") {
                    ancOrder.forEachIndexed { i, mode ->
                        CheckRow(mode.label, mask and bit(mode) != 0, showSeparator = i < ancOrder.lastIndex) { toggle(mode) }
                    }
                }
            }
        }

        if (live) {
            item(key = "gestures") {
                Section(header = "Gestures", footer = "Customize what tapping or pressing each earbud does.") {
                    val entries = TouchSide.entries.flatMap { side -> Gesture.entries.map { side to it } }
                    entries.forEachIndexed { i, (side, gesture) ->
                        ListRow(
                            "${side.label} · ${gesture.label}",
                            value = TouchActions.label(state.touch[side to gesture], state.brand),
                            chevron = true,
                            showSeparator = i < entries.lastIndex,
                            onClick = { openGesture(side, gesture) },
                        )
                    }
                }
            }
            item(key = "audio") {
                Section(
                    header = "Sound & Connection",
                    footer = "LDAC streams higher quality audio when your phone supports it. Game Mode lowers latency. Dual Connection keeps the earbuds connected to two devices.",
                ) {
                    if (state.isOnePlus || state.autoPause != null) {
                        ToggleRow(
                            "Automatic Ear Detection", state.autoPause ?: true, graph.melody::setAutoPause,
                            icon = Icons.Rounded.Hearing, iconColor = Ios.colors.green,
                            subtitle = "Pause when you take an earbud out",
                        )
                    }
                    ToggleRow("LDAC High-Res Audio", state.ldac == true, graph.melody::setLdac, icon = Icons.Rounded.GraphicEq, iconColor = Ios.colors.orange, enabled = state.ldac != null)
                    ToggleRow("Game Mode", state.gameMode == true, graph.melody::setGameMode, icon = Icons.Rounded.SportsEsports, iconColor = Ios.colors.indigo, enabled = state.gameMode != null)
                    ToggleRow("Dual Connection", state.multipoint == true, graph.melody::setMultipoint, icon = Icons.Rounded.Devices, iconColor = Ios.colors.blue, enabled = state.multipoint != null, showSeparator = false)
                }
            }
            item(key = "find") {
                Section(header = "Find My Earbuds", footer = "Plays a loud sound from both earbuds. Take them out of your ears first.") {
                    Box(Modifier.padding(12.dp)) {
                        FilledButton(
                            if (state.ringing) "Stop Sound" else "Play Sound",
                            icon = if (state.ringing) Icons.Rounded.Stop else Icons.Rounded.NotificationsActive,
                            color = if (state.ringing) Ios.colors.red else Ios.colors.orange,
                            onClick = { graph.melody.ring(!state.ringing) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        item(key = "about") {
            Section(header = "About") {
                ListRow("Brand", icon = Icons.Rounded.Info, iconColor = Ios.colors.gray, value = state.brand.label)
                ListRow("Firmware", icon = Icons.Rounded.Memory, iconColor = Ios.colors.gray, value = state.firmware ?: "—")
                ListRow("Bluetooth Address", value = state.address ?: "—")
                ListRow("Bluetooth Settings", icon = Icons.Rounded.Bluetooth, iconColor = Ios.colors.blue, chevron = true, onClick = actions::openBluetoothSettings, showSeparator = false)
            }
        }
    }
}

// ---- Gesture picker ---------------------------------------------------------------------

@Composable
fun GesturePickerScreen(side: TouchSide, gesture: Gesture, onBack: () -> Unit) {
    val graph = LocalGraph.current
    val state by graph.melody.state.collectAsState()
    val s = state
    IosScaffold(title = "${side.label} ${gesture.label}", onBack = onBack, backLabel = "Back", largeTitle = false) {
        if (s == null) return@IosScaffold
        val options = TouchActions.all(s.brand, s.supportsAnc)
        val current = s.touch[side to gesture]
        item {
            Section(footer = "Changes are sent to your earbuds right away.") {
                options.forEachIndexed { i, action ->
                    val selected = current == action.code || (action.label == "Voice Assistant" && current in setOf(0x03, 0x04))
                    CheckRow(action.label, selected, showSeparator = i < options.lastIndex) {
                        graph.melody.setTouch(side, gesture, action.code)
                    }
                }
            }
        }
    }
}
