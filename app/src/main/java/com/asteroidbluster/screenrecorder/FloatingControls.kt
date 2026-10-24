package com.asteroidbluster.screenrecorder

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The floating ball: a draggable overlay that lives over whatever the
 * user is recording, so recording controls never require leaving the
 * app being recorded. Tap the ball to expand it into Pause/Resume,
 * Mic on/off, Mute and Stop; tap again to collapse; drag to move it.
 *
 * Hosted by [ScreenRecordService] (not the activity) so it survives
 * leaving the app. Needs "Display over other apps" permission; if the
 * user denies it, recording still works via the notification.
 */
class FloatingControls(
    private val context: Context,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onPauseToggle()
        fun onMicToggle()
        fun onMuteToggle()
        fun onStop()
        fun onHide()
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 100
        y = 400
    }

    private var root: View? = null
    private var ball: View? = null
    private var ballDot: View? = null
    private var ballPaused: TextView? = null
    private var panel: LinearLayout? = null
    private var pauseButton: Button? = null
    private var micButton: Button? = null
    private var muteButton: Button? = null

    private var expanded = false
    private var paused = false
    private var micMuted = false
    private var muted = false

    fun show() {
        if (root != null) return
        val view = LayoutInflater.from(context)
            .inflate(R.layout.overlay_controls, null)
        ball = view.findViewById(R.id.ball)
        ballDot = view.findViewById(R.id.ballDot)
        ballPaused = view.findViewById(R.id.ballPaused)
        panel = view.findViewById(R.id.panel)
        pauseButton = view.findViewById(R.id.btnPause)
        micButton = view.findViewById(R.id.btnMic)
        muteButton = view.findViewById(R.id.btnMute)
        view.findViewById<Button>(R.id.btnStop).setOnClickListener {
            callbacks.onStop()
        }
        view.findViewById<Button>(R.id.btnHide).setOnClickListener {
            callbacks.onHide()
        }
        pauseButton?.setOnClickListener { callbacks.onPauseToggle() }
        micButton?.setOnClickListener { callbacks.onMicToggle() }
        muteButton?.setOnClickListener { callbacks.onMuteToggle() }

        ball?.setOnTouchListener(DragToggleListener())
        root = view
        windowManager.addView(view, params)
    }

    fun hide() {
        val view = root ?: return
        root = null
        try {
            windowManager.removeView(view)
        } catch (ignored: Exception) {
        }
    }

    fun setPaused(p: Boolean) {
        paused = p
        pauseButton?.text = if (p) "Resume" else "Pause"
        ballDot?.visibility = if (p) View.GONE else View.VISIBLE
        ballPaused?.visibility = if (p) View.VISIBLE else View.GONE
    }

    fun setMicMuted(m: Boolean) {
        micMuted = m
        micButton?.text = if (m) "Mic: off" else "Mic: on"
    }

    fun setMuted(m: Boolean) {
        muted = m
        muteButton?.text = if (m) "Unmute" else "Mute"
    }

    private fun toggleExpand() {
        expanded = !expanded
        panel?.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    /** Drag moves the ball; a tap (no real movement) expands/collapses. */
    private inner class DragToggleListener : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var downX = 0
        private var downY = 0
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                        moved = true
                    }
                    if (moved) {
                        params.x = downX + dx
                        params.y = downY + dy
                        try {
                            windowManager.updateViewLayout(root, params)
                        } catch (ignored: Exception) {
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleExpand()
                    return true
                }
            }
            return false
        }
    }
}
