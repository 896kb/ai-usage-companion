package dev.rgarjoaba.claudestatus.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val text = dm.displays.joinToString("\n") { d ->
            "display ${d.displayId}  ${d.mode.physicalWidth}x${d.mode.physicalHeight}  state=${d.state}  \"${d.name}\""
        }
        val tv = TextView(this).apply {
            setPadding(48, 96, 48, 48)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setText(text)
        }
        setContentView(ScrollView(this).apply { addView(tv) })
    }
}
