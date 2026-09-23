package com.airsink

import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.content.IntentSender
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.IntentCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.airsink.cast.CastTarget
import com.airsink.core.Permissions
import com.airsink.ui.screens.AirPodsScreen
import com.airsink.ui.screens.HomeScreen
import com.airsink.ui.screens.OnboardingScreen
import com.airsink.ui.screens.SettingsScreen
import com.airsink.ui.screens.SonosScreen
import com.airsink.ui.screens.SpeakerScreen
import com.airsink.ui.theme.AirSinkTheme
import java.util.regex.Pattern

/** Things screens ask the Activity to do (permissions, system dialogs). */
interface AppActions {
    fun requestPermissions()
    fun toggleCast(target: CastTarget)
    fun pairNewHeadphones()
    fun openBluetoothSettings()
    fun openOverlaySettings()
    fun openAppSettings()
}

val LocalGraph = staticCompositionLocalOf<AppGraph> { error("no graph") }
val LocalActions = staticCompositionLocalOf<AppActions> { error("no actions") }

class MainActivity : ComponentActivity(), AppActions {
    private val graph get() = (application as AirSinkApp).graph

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Permissions.hasBluetooth(this)) {
            graph.airpods.start()
            graph.airpods.startScan(lowLatency = true)
            HeadphonesService.start(this)
        }
        pendingCapture?.let { if (Permissions.hasRecordAudio(this)) launchCapture() }
        pendingCapture = null
    }

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) graph.cast.onCaptureGranted(r.resultCode, data)
    }

    private val pairLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val data = r.data ?: return@registerForActivityResult
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 34) {
            IntentCompat.getParcelableExtra(data, CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
                ?.associatedDevice?.bluetoothDevice
        } else {
            @Suppress("DEPRECATION")
            IntentCompat.getParcelableExtra(data, CompanionDeviceManager.EXTRA_DEVICE, BluetoothDevice::class.java)
        }
        try {
            device?.createBond()
        } catch (_: SecurityException) {
            openBluetoothSettings()
        }
    }

    private var pendingCapture: Unit? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val theme by graph.prefs.theme.flowValue.collectAsState()
            AirSinkTheme(theme) {
                CompositionLocalProvider(LocalGraph provides graph, LocalActions provides this) {
                    AppNavigation(startOnboarding = !graph.prefs.onboarded.value)
                }
            }
        }
        if (intent.getBooleanExtra(EXTRA_PAIR, false)) pairNewHeadphones()
    }

    override fun onStart() {
        super.onStart()
        graph.airplay.start()
        graph.sonos.start()
        graph.airpods.startScan(lowLatency = true)
    }

    override fun onStop() {
        super.onStop()
        if (graph.cast.active.value.isEmpty()) {
            graph.airplay.stop()
            graph.sonos.stop()
        }
        graph.airpods.startScan(lowLatency = false)
    }

    // ---- AppActions ------------------------------------------------------------------

    override fun requestPermissions() {
        permissionLauncher.launch(Permissions.all)
    }

    override fun toggleCast(target: CastTarget) {
        if (!graph.cast.toggle(target)) return
        if (!Permissions.hasRecordAudio(this)) {
            pendingCapture = Unit
            permissionLauncher.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO) + Permissions.notifications)
        } else {
            launchCapture()
        }
    }

    private fun launchCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Audio capture needs the whole-device option, not a single app window.
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        captureLauncher.launch(intent)
    }

    override fun pairNewHeadphones() {
        if (Build.VERSION.SDK_INT < 33) { openBluetoothSettings(); return }
        val cdm = getSystemService(CompanionDeviceManager::class.java)
        val filter = BluetoothDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("(?i).*(airpods|beats|powerbeats).*"))
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(false).build()
        cdm.associate(request, mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                pairLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            override fun onFailure(error: CharSequence?) {
                Toast.makeText(this@MainActivity, "Put your AirPods in pairing mode: open the case and hold the button on the back.", Toast.LENGTH_LONG).show()
            }
        })
    }

    override fun openBluetoothSettings() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    override fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    override fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    companion object {
        const val EXTRA_PAIR = "pair"
    }
}

private val iosEasing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)
private const val PUSH_MS = 420

@Composable
private fun AppNavigation(startOnboarding: Boolean) {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = if (startOnboarding) "onboarding" else "home",
        // UINavigationController push/pop: new screen slides over, old one drifts a third.
        enterTransition = { slideInHorizontally(tween(PUSH_MS, easing = iosEasing)) { it } },
        exitTransition = { slideOutHorizontally(tween(PUSH_MS, easing = iosEasing)) { -it / 3 } + fadeOut(tween(PUSH_MS), 0.6f) },
        popEnterTransition = { slideInHorizontally(tween(PUSH_MS, easing = iosEasing)) { -it / 3 } + fadeIn(tween(PUSH_MS), 0.6f) },
        popExitTransition = { slideOutHorizontally(tween(PUSH_MS, easing = iosEasing)) { it } },
    ) {
        composable("onboarding") {
            OnboardingScreen(onDone = {
                nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
            })
        }
        composable("home") {
            HomeScreen(
                openHeadphones = { nav.navigate("airpods") },
                openSpeaker = { id -> nav.navigate("airplay/${Uri.encode(id)}") },
                openSonos = { uuid -> nav.navigate("sonos/${Uri.encode(uuid)}") },
                openSettings = { nav.navigate("settings") },
            )
        }
        composable("airpods") { AirPodsScreen(onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable("airplay/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
            SpeakerScreen(it.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable("sonos/{uuid}", arguments = listOf(navArgument("uuid") { type = NavType.StringType })) {
            SonosScreen(it.arguments?.getString("uuid").orEmpty(), onBack = { nav.popBackStack() })
        }
    }
}
