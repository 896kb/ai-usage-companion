package dev.rgarjoaba.claudestatus.rear

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.rgarjoaba.claudestatus.Panel

class RearActivity : Activity() {

    companion object {
        const val ACTION_STATE = "dev.rgarjoaba.claudestatus.STATE"
        const val ACTION_FINISH = "dev.rgarjoaba.claudestatus.FINISH"
        const val EXTRA_STATE = "state"
    }

    private lateinit var dump: TextView

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_FINISH -> finish()
                ACTION_STATE -> render(intent.getStringExtra(EXTRA_STATE).orEmpty())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // The display is LIVE inside the reported cutout — render across all 904px.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()

        dump = TextView(this).apply {
            setTextColor(Panel.Palette.FG)
            typeface = Typeface.MONOSPACE
            textSize = 9f
            gravity = Gravity.CENTER
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Panel.Palette.BG)
            addView(dump, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
        })

        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter().apply { addAction(ACTION_STATE); addAction(ACTION_FINISH) },
            ContextCompat.RECEIVER_EXPORTED,
        )
        render(intent?.getStringExtra(EXTRA_STATE).orEmpty())
    }

    private fun render(payload: String) {
        dump.text = "display=${display?.displayId}  ${width()}x${height()}\n\n$payload"
    }

    private fun width() = window.decorView.width
    private fun height() = window.decorView.height

    private fun hideBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The status bar can reappear after the display move — re-assert.
        if (hasFocus) hideBars()
    }

    override fun onDestroy() {
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
