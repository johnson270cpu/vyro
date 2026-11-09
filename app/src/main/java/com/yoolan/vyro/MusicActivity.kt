package com.yoolan.vyro

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** The "Now playing" screen. The music itself plays in MusicService, so it keeps going when this closes. */
class MusicActivity : Activity() {

    private class Chip(val root: LinearLayout, val label: TextView)

    private lateinit var p: Palette
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var artView: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var seek: SeekBar
    private lateinit var timeNow: TextView
    private lateinit var timeAll: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var shuffleBtn: ImageButton
    private lateinit var repeatBtn: ImageButton
    private lateinit var sleepChip: Chip
    private lateinit var eqChip: Chip

    private var tracking = false
    private var shownArt: Bitmap? = null
    private var artIsPlaceholder = false

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Themes.resolve(this)
        val secure = intent.getBooleanExtra("secure", false)
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        applyBars(p, p.bg)
        buildUi()

        val uris = intent.getStringArrayListExtra("uris")
        val titles = intent.getStringArrayListExtra("titles")
        if (!intent.getBooleanExtra("resume", false) && uris != null && uris.isNotEmpty()) {
            val tracks = uris.mapIndexed { i, u ->
                Track(u, if (titles != null && i < titles.size) titles[i] else "Untitled", secure)
            }
            MusicService.start(
                this, tracks, intent.getIntExtra("index", 0), intent.getBooleanExtra("shuffle", false)
            )
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 31)
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
    }

    // ---------- UI ----------

    private fun iconBtn(res: Int, sizeDp: Int, padDp: Int, tint: Int): ImageButton {
        val b = ImageButton(this)
        b.setImageResource(res)
        b.setColorFilter(tint)
        b.setBackgroundColor(Color.TRANSPARENT)
        b.scaleType = ImageView.ScaleType.FIT_CENTER
        b.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
        b.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return b
    }

    private fun chip(icon: Int, text: String, onClick: () -> Unit): Chip {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.background = rounded(p.pill, 20)
        row.setPadding(dp(14), dp(10), dp(14), dp(10))
        val iv = ImageView(this)
        iv.setImageResource(icon)
        iv.setColorFilter(p.accent)
        row.addView(iv, LinearLayout.LayoutParams(dp(18), dp(18)))
        val t = TextView(this)
        t.text = text
        t.textSize = 13f
        t.setTextColor(p.text)
        t.setPadding(dp(8), 0, 0, 0)
        row.addView(t)
        row.setOnClickListener { onClick() }
        return Chip(row, t)
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(p.bg)
        root.addView(buildHeader(p, "Now playing"), LinearLayout.LayoutParams(MATCH, WRAP))

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.gravity = Gravity.CENTER_HORIZONTAL
        body.setPadding(dp(24), dp(24), dp(24), dp(16))

        artView = ImageView(this)
        artView.background = rounded(p.pill, 32)
        artView.clipToOutline = true
        showPlaceholderArt()
        body.addView(artView, LinearLayout.LayoutParams(dp(250), dp(250)))

        titleView = TextView(this)
        titleView.textSize = 22f
        titleView.setTypeface(titleView.typeface, Typeface.BOLD)
        titleView.setTextColor(p.text)
        titleView.gravity = Gravity.CENTER
        titleView.maxLines = 2
        titleView.setPadding(0, dp(22), 0, dp(2))
        titleView.text = "Starting..."
        body.addView(titleView, LinearLayout.LayoutParams(MATCH, WRAP))

        artistView = TextView(this)
        artistView.textSize = 15f
        artistView.setTextColor(p.hint)
        artistView.gravity = Gravity.CENTER
        artistView.maxLines = 1
        body.addView(artistView, LinearLayout.LayoutParams(MATCH, WRAP))

        seek = SeekBar(this)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) timeNow.text = fmtTime(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                tracking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                tracking = false
                MusicService.instance?.seekTo(sb.progress)
            }
        })
        val slp = LinearLayout.LayoutParams(MATCH, WRAP)
        slp.topMargin = dp(18)
        body.addView(seek, slp)

        val times = LinearLayout(this)
        times.orientation = LinearLayout.HORIZONTAL
        timeNow = TextView(this)
        timeNow.setTextColor(p.hint)
        timeNow.text = "0:00"
        timeAll = TextView(this)
        timeAll.setTextColor(p.hint)
        timeAll.text = "0:00"
        timeAll.gravity = Gravity.END
        times.addView(timeNow, LinearLayout.LayoutParams(0, WRAP, 1f))
        times.addView(timeAll, LinearLayout.LayoutParams(0, WRAP, 1f))
        body.addView(times, LinearLayout.LayoutParams(MATCH, WRAP))

        val controls = LinearLayout(this)
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER
        shuffleBtn = iconBtn(R.drawable.ic_shuffle, 52, 14, p.hint)
        val prev = iconBtn(R.drawable.ic_prev, 60, 16, p.text)
        playBtn = iconBtn(R.drawable.ic_pause, 80, 22, p.onAccent)
        playBtn.background = rounded(p.accent, 40)
        val next = iconBtn(R.drawable.ic_next, 60, 16, p.text)
        repeatBtn = iconBtn(R.drawable.ic_repeat, 52, 14, p.hint)
        shuffleBtn.setOnClickListener {
            val s = MusicService.instance
            if (s != null) {
                s.shuffle = !s.shuffle
                toast(if (s.shuffle) "Shuffle on" else "Shuffle off")
                refresh()
            }
        }
        prev.setOnClickListener { MusicService.instance?.prev() }
        next.setOnClickListener { MusicService.instance?.next() }
        playBtn.setOnClickListener {
            MusicService.instance?.toggle()
            refresh()
        }
        repeatBtn.setOnClickListener {
            val s = MusicService.instance
            if (s != null) {
                s.repeat = (s.repeat + 1) % 3
                toast(
                    when (s.repeat) {
                        0 -> "Repeat off"
                        1 -> "Repeat all"
                        else -> "Repeat this song"
                    }
                )
                refresh()
            }
        }
        controls.addView(shuffleBtn)
        controls.addView(prev)
        val plp = LinearLayout.LayoutParams(dp(80), dp(80))
        plp.setMargins(dp(10), 0, dp(10), 0)
        controls.addView(playBtn, plp)
        controls.addView(next)
        controls.addView(repeatBtn)
        val clp = LinearLayout.LayoutParams(MATCH, WRAP)
        clp.topMargin = dp(14)
        body.addView(controls, clp)

        val extras = LinearLayout(this)
        extras.orientation = LinearLayout.HORIZONTAL
        extras.gravity = Gravity.CENTER
        sleepChip = chip(R.drawable.ic_timer, "Sleep timer") { showSleepDialog() }
        eqChip = chip(R.drawable.ic_equalizer, "Equalizer") { showEqDialog() }
        val queueChip = chip(R.drawable.ic_queue, "Queue") { showQueueDialog() }
        val xlp = LinearLayout.LayoutParams(WRAP, WRAP)
        xlp.setMargins(dp(4), 0, dp(4), 0)
        extras.addView(sleepChip.root, xlp)
        extras.addView(eqChip.root, xlp)
        extras.addView(queueChip.root, xlp)
        val elp = LinearLayout.LayoutParams(MATCH, WRAP)
        elp.topMargin = dp(18)
        body.addView(extras, elp)

        root.addView(body, LinearLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
    }

    private fun showPlaceholderArt() {
        artIsPlaceholder = true
        artView.setPadding(dp(64), dp(64), dp(64), dp(64))
        artView.scaleType = ImageView.ScaleType.FIT_CENTER
        artView.setColorFilter(p.accent)
        artView.setImageResource(R.drawable.ic_music)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- live updates ----------

    private fun refresh() {
        val s = MusicService.instance
        if (s == null) {
            titleView.text = "Starting..."
            return
        }
        titleView.text = if (s.title.isEmpty()) "Loading..." else s.title
        artistView.text = s.artist

        val art = s.artBitmap
        if (art !== shownArt) {
            shownArt = art
            if (art != null) {
                artIsPlaceholder = false
                artView.clearColorFilter()
                artView.setPadding(0, 0, 0, 0)
                artView.scaleType = ImageView.ScaleType.CENTER_CROP
                artView.setImageBitmap(art)
            } else {
                showPlaceholderArt()
            }
        }

        val dur = s.duration
        if (seek.max != dur) seek.max = dur
        if (!tracking) {
            seek.progress = s.position
            timeNow.text = fmtTime(s.position)
        }
        timeAll.text = fmtTime(dur)

        playBtn.setImageResource(if (s.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        shuffleBtn.setColorFilter(if (s.shuffle) p.accent else p.hint)
        repeatBtn.setColorFilter(if (s.repeat != 0) p.accent else p.hint)

        val left = s.sleepLeftMs()
        sleepChip.label.text = if (left > 0) "Sleep " + fmtTime(left.toInt()) else "Sleep timer"
        eqChip.label.text = MusicService.PRESET_NAMES[s.preset]
    }

    // ---------- dialogs ----------

    private fun showSleepDialog() {
        val s = MusicService.instance ?: return
        val labels = arrayOf("Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "90 minutes")
        val mins = intArrayOf(0, 15, 30, 45, 60, 90)
        AlertDialog.Builder(this)
            .setTitle("Stop music after")
            .setItems(labels) { _, which ->
                s.setSleepTimer(mins[which])
                toast(if (mins[which] == 0) "Sleep timer off" else "Music will pause in " + labels[which])
                refresh()
            }
            .show()
    }

    private fun showEqDialog() {
        val s = MusicService.instance ?: return
        AlertDialog.Builder(this)
            .setTitle("Equalizer")
            .setSingleChoiceItems(MusicService.PRESET_NAMES, s.preset) { d, which ->
                s.applyPreset(which)
                d.dismiss()
                refresh()
            }
            .show()
    }

    private fun showQueueDialog() {
        val s = MusicService.instance ?: return
        val names = s.queueTitles().mapIndexed { i, t -> (if (i == s.index) "▶  " else "") + t }
        AlertDialog.Builder(this)
            .setTitle("Up next (" + names.size + " songs)")
            .setItems(names.toTypedArray()) { _, which ->
                s.play(which)
                refresh()
            }
            .show()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
