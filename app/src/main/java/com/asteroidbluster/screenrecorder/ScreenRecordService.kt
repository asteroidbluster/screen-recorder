package com.asteroidbluster.screenrecorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Hosts the [RecordingEngine] as a foreground service so recording survives
 * leaving the app. Owns the MediaProjection, the MediaStore output file and
 * the notification; the engine owns codecs, muxer and capture threads.
 */
class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "com.asteroidbluster.screenrecorder.START"
        const val ACTION_STOP = "com.asteroidbluster.screenrecorder.STOP"
        const val ACTION_RECORDING_SAVED =
            "com.asteroidbluster.screenrecorder.RECORDING_SAVED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_AUDIO_MODE = "audio_mode"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DENSITY = "density"
        const val EXTRA_BITRATE = "bitrate"
        const val EXTRA_FPS = "fps"
        const val EXTRA_FLOATING = "floating"
        const val EXTRA_FILE_NAME = "file_name"

        private const val CHANNEL_ID = "screen_recorder"
        private const val NOTIFICATION_ID = 1
    }

    private var mediaProjection: MediaProjection? = null
    private var engine: RecordingEngine? = null
    private var outputFd: ParcelFileDescriptor? = null
    private var outputUri: android.net.Uri? = null
    private var outputName: String? = null
    private var stopRequested = false
    private val stopHandler = Handler(Looper.getMainLooper())
    private var stopWatchdog: Runnable? = null

    // Floating ball state (reset on every new recording).
    private var floating: FloatingControls? = null
    private var pausedState = false
    private var micMutedState = false
    private var audioMutedState = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // User revoked capture from the system UI.
            requestStop()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (engine == null) {
                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                    val data = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_DATA) as? Intent
                    }
                    if (data != null) startRecording(resultCode, data, intent)
                }
            }
            ACTION_STOP -> requestStop()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent, intent: Intent) {
        val modeName = intent.getStringExtra(EXTRA_AUDIO_MODE) ?: AudioMode.EXTERNAL.name
        val mode = runCatching { AudioMode.valueOf(modeName) }.getOrDefault(AudioMode.EXTERNAL)
        val config = RecordingConfig(
            audioMode = mode,
            width = intent.getIntExtra(EXTRA_WIDTH, 0),
            height = intent.getIntExtra(EXTRA_HEIGHT, 0),
            densityDpi = intent.getIntExtra(EXTRA_DENSITY, 320),
            videoBitRate = intent.getIntExtra(EXTRA_BITRATE, 8_000_000),
            frameRate = intent.getIntExtra(EXTRA_FPS, 30)
        )
        if (config.width <= 0 || config.height <= 0) {
            stopSelf()
            return
        }
        val modeLabel = when (mode) {
            AudioMode.EXTERNAL -> "Mic only"
            AudioMode.INTERNAL -> "Internal audio only"
            AudioMode.BOTH -> "Mic + internal audio"
        }

        createNotificationChannel()
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentPending = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen recording")
            .setContentText("$modeLabel • ${config.width}x${config.height} @ ${config.frameRate}fps")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(contentPending)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, data)
        projection.registerCallback(projectionCallback, null)
        mediaProjection = projection

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        outputName = "ScreenRec_$stamp.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outputName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenRecorder")
        }
        outputUri = contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
        )
        outputFd = outputUri?.let { contentResolver.openFileDescriptor(it, "w") }
        if (outputFd == null) {
            cleanupProjection()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        try {
            val recordingEngine = RecordingEngine(outputFd!!) {}
            engine = recordingEngine
            recordingEngine.start(projection, config)
        } catch (e: Exception) {
            outputUri?.let { contentResolver.delete(it, null, null) }
            cleanupProjection()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        // Floating ball, only if the user enabled it and the overlay
        // permission is actually granted. Denied? Notification controls
        // still work, nothing else changes.
        pausedState = false
        micMutedState = false
        audioMutedState = false
        if (intent.getBooleanExtra(EXTRA_FLOATING, false) &&
            Settings.canDrawOverlays(this)
        ) {
            val controls = FloatingControls(this, object : FloatingControls.Callbacks {
                override fun onPauseToggle() {
                    pausedState = !pausedState
                    engine?.setPaused(pausedState)
                    floating?.setPaused(pausedState)
                }

                override fun onMicToggle() {
                    micMutedState = !micMutedState
                    engine?.setMicMuted(micMutedState)
                    floating?.setMicMuted(micMutedState)
                }

                override fun onMuteToggle() {
                    audioMutedState = !audioMutedState
                    engine?.setAudioMuted(audioMutedState)
                    floating?.setMuted(audioMutedState)
                }

                override fun onStop() {
                    requestStop()
                }

                override fun onHide() {
                    floating?.hide()
                    floating = null
                }
            })
            floating = controls
            controls.show()
        }
    }

    private fun requestStop() {
        if (stopRequested) return
        stopRequested = true
        val currentEngine = engine
        if (currentEngine == null) {
            cleanupProjection()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        currentEngine.stopAsync { success ->
            stopWatchdog?.let { stopHandler.removeCallbacks(it) }
            stopWatchdog = null
            if (success) {
                sendBroadcast(
                    Intent(ACTION_RECORDING_SAVED).apply {
                        putExtra(EXTRA_FILE_NAME, outputName)
                        setPackage(packageName)
                    }
                )
            } else {
                outputUri?.let { contentResolver.delete(it, null, null) }
                sendBroadcast(
                    Intent(ACTION_RECORDING_SAVED).apply {
                        setPackage(packageName)
                    }
                )
            }
            try {
                outputFd?.close()
            } catch (ignored: Exception) {
            }
            outputFd = null
            outputUri = null
            outputName = null
            engine = null
            floating?.hide()
            floating = null
            cleanupProjection()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        // Watchdog: if the stop thread ever wedges, force the recording
        // to end anyway after 12s. A late-saved file beats a recording
        // that can never be stopped.
        val watchdog = Runnable {
            if (engine != null) {
                try {
                    outputFd?.close()
                } catch (ignored: Exception) {
                }
                outputFd = null
                engine = null
                floating?.hide()
                floating = null
                cleanupProjection()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        stopWatchdog = watchdog
        stopHandler.postDelayed(watchdog, 12_000)
    }

    private fun cleanupProjection() {
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (ignored: Exception) {
        }
        try {
            mediaProjection?.stop()
        } catch (ignored: Exception) {
        }
        mediaProjection = null
    }

    override fun onDestroy() {
        floating?.hide()
        floating = null
        if (engine != null && !stopRequested) {
            requestStop()
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen recording",
                // DEFAULT, not LOW: LOW drops the notification into the
                // shade's silent section (or off it entirely on some
                // devices), which is how v1.1.0's Stop button went missing.
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }
    }
}
