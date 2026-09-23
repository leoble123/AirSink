package com.airsink.ui.screens

import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.airsink.BuildConfig
import com.airsink.LocalActions
import com.airsink.LocalGraph
import com.airsink.core.Permissions
import com.airsink.ui.components.IosScaffold
import com.airsink.ui.components.ListRow
import com.airsink.ui.components.Section
import com.airsink.ui.components.SegmentedControl
import com.airsink.ui.components.ToggleRow
import com.airsink.ui.theme.Ios

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val graph = LocalGraph.current
    val actions = LocalActions.current
    val context = LocalContext.current
    val prefs = graph.prefs
    val popup by prefs.connectionPopup.flowValue.collectAsState()
    val ear by prefs.earDetection.flowValue.collectAsState()
    val ducking by prefs.conversationDucking.flowValue.collectAsState()
    val advanced by prefs.advancedControls.flowValue.collectAsState()
    val rssi by prefs.rssiThreshold.flowValue.collectAsState()
    val latency by prefs.airplayLatencyMs.flowValue.collectAsState()
    val sonosFormat by prefs.sonosFormat.flowValue.collectAsState()
    val deviceName by prefs.deviceName.flowValue.collectAsState()
    val theme by prefs.theme.flowValue.collectAsState()
    var editingName by remember { mutableStateOf(false) }

    // Re-check permissions whenever we come back from system settings.
    val permTick = com.airsink.ui.components.rememberResumeTick()
    val hasBt = remember(permTick) { Permissions.hasBluetooth(context) }
    val hasMic = remember(permTick) { Permissions.hasRecordAudio(context) }
    val hasNotif = remember(permTick) { Permissions.notifications.all { Permissions.granted(context, it) } }
    val hasOverlay = remember(permTick) { Settings.canDrawOverlays(context) }

    IosScaffold(title = "Settings", onBack = onBack, backLabel = "AirSink") {
        item {
            Section(header = "Headphones", footer = "Detection range controls how close AirPods must be before AirSink shows them, so it ignores other people's.") {
                ToggleRow("Connection Pop-up", popup, prefs.connectionPopup::set, icon = Icons.Rounded.PictureInPicture, iconColor = Ios.colors.blue)
                ToggleRow("Automatic Ear Detection", ear, prefs.earDetection::set, icon = Icons.Rounded.Hearing, iconColor = Ios.colors.green)
                ToggleRow("Lower Media When Talking", ducking, prefs.conversationDucking::set, icon = Icons.Rounded.RecordVoiceOver, iconColor = Ios.colors.indigo)
                ToggleRow(
                    "Advanced Controls", advanced,
                    { on -> prefs.advancedControls.set(on); if (on) graph.airpods.reconnectControls() else graph.airpods.aap.disconnect() },
                    icon = Icons.Rounded.Tune, iconColor = Ios.colors.gray,
                    subtitle = "Noise control, Conversational Awareness & more",
                )
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Detection Range", style = Ios.type.body, color = Ios.colors.label, modifier = Modifier.padding(bottom = 8.dp))
                    val options = listOf(-55 to "Close", -65 to "Normal", -75 to "Far")
                    SegmentedControl(options.map { it.second }, options.indexOfFirst { it.first == rssi }, { prefs.rssiThreshold.set(options[it].first) })
                }
            }
        }

        item {
            Section(
                header = "Streaming",
                footer = "A bigger buffer is more resistant to Wi-Fi hiccups but adds delay. If Sonos won't play AAC, try WAV.",
            ) {
                ListRow("Name Shown on Speakers", icon = Icons.Rounded.Smartphone, iconColor = Ios.colors.blue, value = deviceName, chevron = true, onClick = { editingName = true })
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("AirPlay Buffer", style = Ios.type.body, color = Ios.colors.label, modifier = Modifier.padding(bottom = 8.dp))
                    val options = listOf(1000 to "1 s", 2000 to "2 s", 3000 to "3 s")
                    SegmentedControl(options.map { it.second }, options.indexOfFirst { it.first == latency }, { prefs.airplayLatencyMs.set(options[it].first) })
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Sonos Stream Format", style = Ios.type.body, color = Ios.colors.label, modifier = Modifier.padding(bottom = 8.dp))
                    val options = listOf("aac" to "AAC", "wav" to "WAV (Lossless)")
                    SegmentedControl(options.map { it.second }, options.indexOfFirst { it.first == sonosFormat }, { prefs.sonosFormat.set(options[it].first) })
                }
            }
        }

        item {
            Section(header = "Appearance") {
                Column(Modifier.padding(12.dp)) {
                    val options = listOf("system" to "Automatic", "light" to "Light", "dark" to "Dark")
                    SegmentedControl(options.map { it.second }, options.indexOfFirst { it.first == theme }, { prefs.theme.set(options[it].first) })
                }
            }
        }

        item {
            Section(header = "Permissions", footer = "“Display over other apps” is needed for the AirPods pop-up. Microphone access is how Android lets apps capture what's playing; AirSink never records your mic.") {
                PermissionRow("Nearby Devices", hasBt, Icons.Rounded.Bluetooth, Ios.colors.blue, actions::requestPermissions)
                PermissionRow("Display Over Other Apps", hasOverlay, Icons.Rounded.PictureInPicture, Ios.colors.orange, actions::openOverlaySettings)
                PermissionRow("Notifications", hasNotif, Icons.Rounded.Notifications, Ios.colors.red, actions::requestPermissions)
                PermissionRow("Audio Capture", hasMic, Icons.Rounded.Mic, Ios.colors.pink, actions::requestPermissions, last = true)
            }
        }

        item {
            Section(header = "About") {
                ListRow("Version", value = BuildConfig.VERSION_NAME)
                ListRow("App Info", chevron = true, onClick = actions::openAppSettings, showSeparator = false)
            }
        }
    }

    if (editingName) {
        var name by remember { mutableStateOf(deviceName) }
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Name Shown on Speakers", style = Ios.type.headline) },
            text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { if (name.isNotBlank()) prefs.deviceName.set(name.trim()); editingName = false }) { Text("Done", color = Ios.colors.blue) }
            },
            dismissButton = { TextButton(onClick = { editingName = false }) { Text("Cancel", color = Ios.colors.blue) } },
            containerColor = Ios.colors.card,
        )
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit, last: Boolean = false) {
    ListRow(
        title, icon = icon, iconColor = color,
        value = if (granted) "Allowed" else "Allow",
        chevron = !granted,
        onClick = if (granted) null else onClick,
        showSeparator = !last,
    )
}
