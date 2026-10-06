package dev.kb896.aiusage.ui.video

import android.app.Activity
import android.app.AlertDialog
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import dev.kb896.aiusage.Panel
import dev.kb896.aiusage.data.VideoConfig
import dev.kb896.aiusage.ui.Modal
import dev.kb896.aiusage.ui.SettingsRows
import dev.kb896.aiusage.ui.UiType
import dev.kb896.aiusage.ui.glass.GlassSlider
import dev.kb896.aiusage.ui.glass.Ink

/**
 * The video background's editor: the frame (drag, pinch), the trim bar, opacity and the
 * colour behind — all live in the frame — and Use it, which cuts the clip and only then
 * hands it back. Nothing is saved before that; a failure keeps the dialog open with the
 * reason, and closing it mid-cut cancels the cut and deletes what it had written.
 */
object VideoEditor {
    fun show(activity: Activity, source: Uri, initial: VideoConfig, behind: Int, onUsed: (VideoConfig, Int) -> Unit) {
        val d = activity.resources.displayMetrics.density
        val duration = runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(activity, source)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
            } finally {
                r.release()
            }
        }.getOrNull() ?: 0L
        if (duration <= 0L) {
            Toast.makeText(activity, "Could not read that video", Toast.LENGTH_SHORT).show()
            return
        }

        var opacity = initial.opacity
        var ground = behind
        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (4 * d).toInt(), (20 * d).toInt(), 0)
        }
        val frame = VideoCropView(activity).apply {
            this.ground = ground
            videoAlpha = opacity
            setVideo(source, initial.panX, initial.panY, initial.zoom)
        }
        sheet.addView(frame)
        val trim = TrimBar(activity).apply {
            setVideo(source, duration, initial.trimStartMs, initial.trimEndMs)
            onScrub = { frame.seek(it) }
            onRange = { s, e -> frame.loopRange(s, e) }
        }
        sheet.addView(trim, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * d).toInt() })
        frame.loopRange(trim.startMs, trim.endMs)

        val opacityLabel = TextView(activity).apply {
            textSize = 11f
            setTextColor(Panel.Palette.FG)
            typeface = UiType.of(activity)
            text = "OPACITY  ${(opacity * 100).toInt()}%"
        }
        sheet.addView(opacityLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (14 * d).toInt() })
        val slider = GlassSlider(activity).apply {
            background = null
            max = 100
            progress = (opacity * 100).toInt()
        }
        sheet.addView(slider, LinearLayout.LayoutParams(-1, -2))
        // The colour behind: only while there is something to see through to.
        val behindRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        SettingsRows.colorRow(behindRow, "Colour behind", ground) { picked ->
            ground = picked
            frame.ground = picked
        }
        sheet.addView(behindRow)
        behindRow.visibility = if (opacity < 1f) View.VISIBLE else View.GONE
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                opacity = progress / 100f
                frame.videoAlpha = opacity
                opacityLabel.text = "OPACITY  $progress%"
                behindRow.visibility = if (progress < 100) View.VISIBLE else View.GONE
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(Ink.ACCENT)
        }
        sheet.addView(progress, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * d).toInt() })
        val message = TextView(activity).apply {
            textSize = 12f
            typeface = UiType.of(activity)
            visibility = View.GONE
        }
        sheet.addView(message)
        fun say(why: String) {
            // Coloured here, not at creation: the sheet inks every text once it is laid out.
            message.setTextColor(Panel.Palette.CRIT)
            message.text = why
            message.visibility = View.VISIBLE
        }

        val export = ClipExport(activity)
        var exporting = false
        val dialog = Modal.show(Modal.builder(activity)
            .setTitle("Drag to move, pinch to zoom")
            .setView(sheet)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Use it", null)
            .setOnDismissListener {
                if (exporting) export.cancel()
                frame.release()
            })
        // A video the phone cannot play here cannot be cut either: said, and Use it refused.
        frame.onFailed = {
            say("This video can't be played on this phone")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        }
        // Use it does not close the editor: the clip is cut first, with its progress here.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
            val size = frame.videoSize ?: return@setOnClickListener
            exporting = true
            button.isEnabled = false
            message.visibility = View.GONE
            progress.visibility = View.VISIBLE
            progress.progress = 0
            val crop = VideoCrop.forPan(frame.region(), size.first, size.second, frame.panX, frame.panY, frame.zoom)
            export.start(source, trim.startMs, trim.endMs, crop,
                onProgress = { progress.progress = it },
                onDone = { clip, cells ->
                    exporting = false
                    onUsed(initial.copy(
                        originalUri = source.toString(), clipPath = clip.path,
                        trimStartMs = trim.startMs, trimEndMs = trim.endMs,
                        panX = frame.panX, panY = frame.panY, zoom = frame.zoom,
                        opacity = opacity, luminance = cells,
                    ), ground)
                    dialog.dismiss()
                },
                onError = { why ->
                    exporting = false
                    progress.visibility = View.GONE
                    button.isEnabled = true
                    say(why)
                })
        }
    }
}
