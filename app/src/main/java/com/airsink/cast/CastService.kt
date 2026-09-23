package com.airsink.cast

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.airsink.AirSinkApp
import com.airsink.MainActivity
import com.airsink.R
import kotlin.concurrent.thread

/**
 * Captures what other apps are playing (Android's AudioPlaybackCapture, API 29+) and hands
 * the PCM to [CastManager]. Apps that opt out of capture (some DRM'd apps) will be silent.
 */
class CastService : Service() {
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    @Volatile private var running = false

    private val cast get() = (application as AirSinkApp).graph.cast

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Must be in the foreground with the mediaProjection type before using the token.
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
                if (data == null) { stopSelf(); return START_NOT_STICKY }
                startCapture(code, data)
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(code: Int, data: Intent) {
        if (running) return
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data) ?: run { stopSelf(); return }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        val config = AudioPlaybackCaptureConfiguration.Builder(mp)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AudioSink.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(AudioSink.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuffer * 4, 32 * 1024))
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't create capture", e)
            stopCapture(); stopSelf(); return
        }
        record = rec
        running = true
        rec.startRecording()
        cast.onCaptureStarted()

        thread(name = "airsink-capture", priority = Thread.MAX_PRIORITY) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            // ~16 ms chunks keep latency low without flooding the network with tiny writes.
            val buf = ByteArray(Alac352Bytes * 2)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) cast.dispatch(buf, n)
                else if (n < 0) break
            }
        }
    }

    private fun stopCapture() {
        running = false
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
        projection?.stop()
        projection = null
        cast.onCaptureStopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        if (running) stopCapture()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Streaming", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while AirSink is sending audio to speakers"
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CastService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_airplay)
            .setContentTitle("AirSink is streaming")
            .setContentText("Sending your phone's audio to your speakers")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
    }

    companion object {
        private const val TAG = "CastService"
        private const val CHANNEL = "cast"
        private const val NOTIFICATION_ID = 42
        private const val Alac352Bytes = 352 * AudioSink.BYTES_PER_FRAME
        const val ACTION_START = "com.airsink.cast.START"
        const val ACTION_STOP = "com.airsink.cast.STOP"
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_RESULT_DATA = "data"
    }
}
