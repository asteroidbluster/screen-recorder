package com.asteroidbluster.screenrecorder

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.radiobutton.MaterialRadioButton

/**
 * Material 3 UI: pick an audio mode, a video quality and a frame rate,
 * then Start. Recording itself lives in [ScreenRecordService] so it keeps
 * going when you leave the app.
 *
 * Audio modes:
 * - Mic only: works everywhere.
 * - Internal only: Android 10+ playback capture (games/media apps; apps
 *   can opt out, and system sounds are not captured).
 * - Mic + internal: both mixed together.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var micOnlyRadio: MaterialRadioButton
    private lateinit var internalOnlyRadio: MaterialRadioButton
    private lateinit var bothRadio: MaterialRadioButton
    private lateinit var qualityView: AutoCompleteTextView
    private lateinit var fpsView: AutoCompleteTextView

    private val prefs by lazy { getSharedPreferences("recorder", MODE_PRIVATE) }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startRecordingService(result.resultCode, result.data!!)
            } else {
                Toast.makeText(this, "Screen capture permission denied.", Toast.LENGTH_SHORT).show()
            }
        }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchCapture()
            } else {
                // Without the mic, fall back to internal audio where
                // possible instead of failing outright.
                if (Build.VERSION.SDK_INT >= 29) {
                    internalOnlyRadio.isChecked = true
                    Toast.makeText(
                        this,
                        "Mic denied — falling back to internal audio.",
                        Toast.LENGTH_SHORT
                    ).show()
                    launchCapture()
                } else {
                    Toast.makeText(
                        this,
                        "Mic permission is required on this Android version.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

    private val savedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val name = intent.getStringExtra(ScreenRecordService.EXTRA_FILE_NAME)
            statusText.text = if (name != null) "Saved: $name" else "Recording stopped."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        micOnlyRadio = findViewById(R.id.radioMicOnly)
        internalOnlyRadio = findViewById(R.id.radioInternalOnly)
        bothRadio = findViewById(R.id.radioBoth)
        qualityView = findViewById(R.id.qualityDropdown)
        fpsView = findViewById(R.id.fpsDropdown)

        qualityView.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                resources.getStringArray(R.array.quality_options)
            )
        )
        fpsView.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_dropdown_item_1line,
                resources.getStringArray(R.array.fps_options)
            )
        )

        // Restore last choices.
        when (prefs.getString("audio_mode", AudioMode.EXTERNAL.name)) {
            AudioMode.INTERNAL.name -> internalOnlyRadio.isChecked = true
            AudioMode.BOTH.name -> bothRadio.isChecked = true
            else -> micOnlyRadio.isChecked = true
        }
        qualityView.setText(prefs.getString("quality", "1080p"), false)
        fpsView.setText(prefs.getString("fps", "30 fps"), false)

        // Internal capture needs Android 10 (API 29)+.
        if (Build.VERSION.SDK_INT < 29) {
            internalOnlyRadio.isEnabled = false
            bothRadio.isEnabled = false
            findViewById<TextView>(R.id.internalNote).text =
                "Internal audio needs Android 10 or newer."
            micOnlyRadio.isChecked = true
        }

        findViewById<MaterialButton>(R.id.startButton).setOnClickListener {
            saveChoices()
            val mode = selectedMode()
            val needsMic = mode == AudioMode.EXTERNAL || mode == AudioMode.BOTH
            if (needsMic &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            } else {
                launchCapture()
            }
        }

        findViewById<MaterialButton>(R.id.stopButton).setOnClickListener {
            val intent = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_STOP
            }
            startService(intent)
        }

        ContextCompat.registerReceiver(
            this,
            savedReceiver,
            IntentFilter(ScreenRecordService.ACTION_RECORDING_SAVED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(savedReceiver)
        } catch (ignored: IllegalArgumentException) {
        }
    }

    private fun selectedMode(): AudioMode = when {
        internalOnlyRadio.isChecked -> AudioMode.INTERNAL
        bothRadio.isChecked -> AudioMode.BOTH
        else -> AudioMode.EXTERNAL
    }

    private fun saveChoices() {
        prefs.edit()
            .putString("audio_mode", selectedMode().name)
            .putString("quality", qualityView.text.toString())
            .putString("fps", fpsView.text.toString())
            .apply()
    }

    private fun launchCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startRecordingService(resultCode: Int, data: Intent) {
        val mode = selectedMode()
        val metrics = resources.displayMetrics
        val nativeW = metrics.widthPixels
        val nativeH = metrics.heightPixels

        // Quality = short-side resolution, so 720p/1080p mean the same
        // thing in portrait and landscape. Original = native, capped.
        val (targetW, targetH, bitrate) = when (qualityView.text.toString()) {
            "720p" -> scaledSize(nativeW, nativeH, 720).let { (w, h) ->
                Triple(w, h, 5_000_000)
            }
            "Original size" -> cappedSize(nativeW, nativeH, 1920).let { (w, h) ->
                Triple(w, h, 12_000_000)
            }
            else -> scaledSize(nativeW, nativeH, 1080).let { (w, h) ->
                Triple(w, h, 8_000_000)
            }
        }
        val fps = when (fpsView.text.toString()) {
            "60 fps" -> 60
            else -> 30
        }

        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_DATA, data)
            putExtra(ScreenRecordService.EXTRA_AUDIO_MODE, mode.name)
            putExtra(ScreenRecordService.EXTRA_WIDTH, targetW)
            putExtra(ScreenRecordService.EXTRA_HEIGHT, targetH)
            putExtra(ScreenRecordService.EXTRA_DENSITY, metrics.densityDpi)
            putExtra(ScreenRecordService.EXTRA_BITRATE, bitrate)
            putExtra(ScreenRecordService.EXTRA_FPS, fps)
        }
        ContextCompat.startForegroundService(this, intent)
        statusText.text = "Recording… pull down the notification shade to stop."
    }

    /** Scale so the short side equals [target], keeping aspect, even sides. */
    private fun scaledSize(w: Int, h: Int, target: Int): Pair<Int, Int> {
        val scale = target.toFloat() / minOf(w, h)
        return even((w * scale).toInt()) to even((h * scale).toInt())
    }

    /** Native size, but cap the long side at [cap], keeping aspect. */
    private fun cappedSize(w: Int, h: Int, cap: Int): Pair<Int, Int> {
        val longer = maxOf(w, h)
        if (longer <= cap) return even(w) to even(h)
        val scale = cap.toFloat() / longer
        return even((w * scale).toInt()) to even((h * scale).toInt())
    }

    private fun even(value: Int): Int = if (value % 2 == 1) value - 1 else value
}
