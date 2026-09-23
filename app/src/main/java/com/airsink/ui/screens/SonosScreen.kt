package com.airsink.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Airplay
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airsink.LocalActions
import com.airsink.LocalGraph
import com.airsink.sonos.SonosClient
import com.airsink.sonos.SonosTrack
import com.airsink.ui.components.CheckRow
import com.airsink.ui.components.FatSlider
import com.airsink.ui.components.FilledButton
import com.airsink.ui.components.IosScaffold
import com.airsink.ui.components.ListRow
import com.airsink.ui.components.Section
import com.airsink.ui.components.SonosArt
import com.airsink.ui.components.ThinSlider
import com.airsink.ui.components.ToggleRow
import com.airsink.ui.components.bouncyClick
import com.airsink.ui.theme.Ios
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

private data class SonosStatus(
    val playing: Boolean = false,
    val track: SonosTrack? = null,
    val groupVolume: Int = 0,
    val bass: Int = 0,
    val treble: Int = 0,
    val loudness: Boolean = true,
    val nightMode: Boolean? = null,
    val speech: Boolean? = null,
    val light: Boolean = true,
    val buttonLock: Boolean = false,
)

@Composable
fun SonosScreen(uuid: String, onBack: () -> Unit) {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val groups by graph.sonos.groups.collectAsState()
    val airplay by graph.airplay.devices.collectAsState()
    val active by graph.cast.active.collectAsState()
    val group = groups.firstOrNull { g -> g.coordinator.uuid == uuid || g.members.any { it.uuid == uuid } }
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf(SonosStatus()) }
    val memberVolumes = remember { mutableStateMapOf<String, Int>() }
    val client = remember(group?.coordinator?.host) { group?.let { SonosClient(it.coordinator.host) } }

    // Poll gently while the screen is open; Sonos doesn't push state without UPnP eventing.
    LaunchedEffect(client) {
        val c = client ?: return@LaunchedEffect
        var first = true
        while (isActive) {
            runCatching {
                status = status.copy(
                    playing = c.transportState() == "PLAYING",
                    track = c.currentTrack(),
                    groupVolume = c.getGroupVolume(),
                )
                if (first) {
                    status = status.copy(
                        bass = c.getBass(), treble = c.getTreble(), loudness = c.getLoudness(),
                        nightMode = c.getNightMode(), speech = c.getSpeechEnhancement(),
                        light = c.getStatusLight(), buttonLock = runCatching { c.getButtonLock() }.getOrDefault(false),
                    )
                    group?.members?.forEach { m -> memberVolumes[m.uuid] = SonosClient(m.host).getVolume() }
                    first = false
                }
            }
            delay(3000)
        }
    }

    fun act(block: suspend SonosClient.() -> Unit) {
        val c = client ?: return
        scope.launch { runCatching { c.block() } }
    }

    IosScaffold(title = group?.name ?: "Sonos", onBack = onBack, backLabel = "AirSink", largeTitle = false) {
        if (group == null || client == null) {
            item { Text("This speaker is no longer on the network.", style = Ios.type.body, color = Ios.colors.secondaryLabel, modifier = Modifier.padding(32.dp)) }
            return@IosScaffold
        }
        val target = sonosTarget(group, airplay.values)
        val streaming = active.containsKey(target.id)

        item(key = "hero") {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SonosArt(size = 120.dp)
                Spacer(Modifier.height(8.dp))
                Text(group.name, style = Ios.type.title2, color = Ios.colors.label)
                Text(group.members.joinToString(" · ") { it.model }.ifEmpty { "Sonos" }, style = Ios.type.subheadline, color = Ios.colors.secondaryLabel)
            }
        }

        item(key = "nowPlaying") {
            NowPlayingCard(
                track = if (streaming) SonosTrack("Your phone's audio", android.os.Build.MODEL, null, null) else status.track,
                playing = status.playing,
                onPrevious = { act { previous() } },
                onPlayPause = {
                    val next = !status.playing
                    status = status.copy(playing = next)
                    act { if (next) play() else pause() }
                },
                onNext = { act { next() } },
            )
        }

        item(key = "stream") {
            FilledButton(
                text = if (streaming) "Stop Playing Phone Audio" else "Play Phone Audio Here",
                icon = if (streaming) Icons.Rounded.Stop else Icons.Rounded.Airplay,
                color = if (streaming) Ios.colors.red else Ios.colors.blue,
                onClick = { actions.toggleCast(target) },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth(),
            )
        }

        item(key = "volume") {
            var v by remember(status.groupVolume) { mutableFloatStateOf(status.groupVolume / 100f) }
            Section(header = if (group.members.size > 1) "Group Volume" else "Volume") {
                Column(Modifier.padding(12.dp)) {
                    FatSlider(v, { v = it }, icon = Icons.AutoMirrored.Rounded.VolumeUp, onValueChangeFinished = { act { setGroupVolume((v * 100).toInt()) } })
                }
                if (group.members.size > 1) {
                    group.members.forEach { m ->
                        var mv by remember(memberVolumes[m.uuid]) { mutableFloatStateOf((memberVolumes[m.uuid] ?: 0) / 100f) }
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Text(m.room, style = Ios.type.footnote, color = Ios.colors.secondaryLabel)
                            ThinSlider(mv, { mv = it }, onValueChangeFinished = {
                                scope.launch { runCatching { SonosClient(m.host).setVolume((mv * 100).toInt()) } }
                            })
                        }
                    }
                }
            }
        }

        item(key = "rooms") {
            val allPlayers = groups.flatMap { it.members }.distinctBy { it.uuid }
            if (allPlayers.size > 1) {
                Section(header = "Rooms", footer = "Choose which rooms play together.") {
                    allPlayers.forEachIndexed { i, p ->
                        val inGroup = group.members.any { it.uuid == p.uuid }
                        CheckRow(p.room, inGroup, showSeparator = i < allPlayers.lastIndex) {
                            if (p.uuid == group.coordinator.uuid) return@CheckRow
                            scope.launch {
                                runCatching {
                                    val c = SonosClient(p.host)
                                    if (inGroup) c.leaveGroup() else c.joinGroup(group.coordinator.uuid)
                                }
                                graph.sonos.refresh()
                            }
                        }
                    }
                }
            }
        }

        item(key = "eq") {
            var bass by remember(status.bass) { mutableFloatStateOf(status.bass.toFloat()) }
            var treble by remember(status.treble) { mutableFloatStateOf(status.treble.toFloat()) }
            Section(header = "Sound", footer = "EQ applies to ${group.coordinator.room}.") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    EqLabel("Bass", bass.toInt())
                    ThinSlider(bass, { bass = it }, range = -10f..10f, steps = 20, onValueChangeFinished = { act { setBass(bass.toInt()) } })
                    EqLabel("Treble", treble.toInt())
                    ThinSlider(treble, { treble = it }, range = -10f..10f, steps = 20, onValueChangeFinished = { act { setTreble(treble.toInt()) } })
                }
                ToggleRow("Loudness", status.loudness, { on -> status = status.copy(loudness = on); act { setLoudness(on) } }, icon = Icons.Rounded.GraphicEq, iconColor = Ios.colors.pink, showSeparator = status.nightMode != null)
                status.nightMode?.let { night ->
                    ToggleRow("Night Sound", night, { on -> status = status.copy(nightMode = on); act { setNightMode(on) } }, icon = Icons.Rounded.Bedtime, iconColor = Ios.colors.indigo)
                }
                status.speech?.let { speech ->
                    ToggleRow("Speech Enhancement", speech, { on -> status = status.copy(speech = on); act { setSpeechEnhancement(on) } }, icon = Icons.Rounded.RecordVoiceOver, iconColor = Ios.colors.teal, showSeparator = false)
                }
            }
        }

        item(key = "device") {
            Section(header = "Speaker") {
                ToggleRow("Status Light", status.light, { on -> status = status.copy(light = on); act { setStatusLight(on) } }, icon = Icons.Rounded.Lightbulb, iconColor = Ios.colors.yellow)
                ToggleRow("Lock Touch Controls", status.buttonLock, { on -> status = status.copy(buttonLock = on); act { setButtonLock(on) } }, icon = Icons.Rounded.Lock, iconColor = Ios.colors.gray)
                ListRow("IP Address", value = group.coordinator.host, showSeparator = false)
            }
        }
    }
}

@Composable
private fun EqLabel(name: String, value: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(name, style = Ios.type.body, color = Ios.colors.label, modifier = Modifier.weight(1f))
        Text(if (value > 0) "+$value" else "$value", style = Ios.type.body, color = Ios.colors.secondaryLabel)
    }
}

@Composable
private fun NowPlayingCard(
    track: SonosTrack?,
    playing: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    val art by produceState<ImageBitmap?>(null, track?.artUrl) {
        value = track?.artUrl?.let { url ->
            withContext(Dispatchers.IO) {
                runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
            }
        }
    }
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Ios.colors.card)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(Ios.colors.fill),
                contentAlignment = Alignment.Center,
            ) {
                val a = art
                if (a != null) Image(a, null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp))
                else Icon(Icons.Rounded.MusicNote, null, tint = Ios.colors.secondaryLabel)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(track?.title ?: "Not Playing", style = Ios.type.headline, color = Ios.colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(track?.artist, track?.album).joinToString(" — ").ifEmpty { "Sonos" },
                    style = Ios.type.subheadline, color = Ios.colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TransportButton(Icons.Rounded.SkipPrevious, 34, onPrevious)
            TransportButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 46, onPlayPause)
            TransportButton(Icons.Rounded.SkipNext, 34, onNext)
        }
    }
}

@Composable
private fun TransportButton(icon: androidx.compose.ui.graphics.vector.ImageVector, size: Int, onClick: () -> Unit) {
    Box(Modifier.size((size + 16).dp).bouncyClick(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Ios.colors.label, modifier = Modifier.size(size.dp))
    }
}
