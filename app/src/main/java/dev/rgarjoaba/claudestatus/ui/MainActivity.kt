package dev.rgarjoaba.claudestatus.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import dev.rgarjoaba.claudestatus.rear.ShizukuBridge

// Plain and additive only — Task 17 replaces this with the real UI.
class MainActivity : ComponentActivity() {

    private val bridge by lazy { ShizukuBridge(this) }
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displaysText = dm.displays.joinToString("\n") { d ->
            "display ${d.displayId}  ${d.mode.physicalWidth}x${d.mode.physicalHeight}  state=${d.state}  \"${d.name}\""
        }

        val displaysView = TextView(this).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setText(displaysText)
        }
        statusView = TextView(this).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val grantButton = Button(this).apply {
            text = "Grant Shizuku"
            setOnClickListener {
                bridge.requestPermission(this@MainActivity)
                refreshStatus()
            }
        }
        val showButton = Button(this).apply {
            text = "Show on back"
            // Renders the calibration grid — the size/payload text dump this used to
            // show is superseded. Mode travels inside the payload (no AIDL change);
            // RearActivity parses it and selects CalibrationView.
            setOnClickListener { bridge.show("""{"mode":"calibrate"}""") }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            addView(displaysView)
            addView(statusView)
            addView(grantButton)
            addView(showButton)
        }
        setContentView(ScrollView(this).apply { addView(layout) })

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val s = bridge.status()
        statusView.text = "running=${s.running} granted=${s.granted} rearDisplayId=${s.rearDisplayId}"
    }
}
