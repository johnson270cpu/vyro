package com.yoolan.vyro

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** A VideoView that can show the picture as Fit, Fill (crop) or Stretch. */
class AspectVideoView(ctx: Context) : VideoView(ctx) {
    var vw = 0
    var vh = 0
    var mode = 0   // 0 = fit, 1 = fill, 2 = stretch

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        val h = View.MeasureSpec.getSize(heightMeasureSpec)
        if (vw <= 0 || vh <= 0 || mode == 2) {
            setMeasuredDimension(w, h)
            return
        }
        val sx = w / vw.toFloat()
        val sy = h / vh.toFloat()
        val s = if (mode == 0) minOf(sx, sy) else maxOf(sx, sy)
        setMeasuredDimension((vw * s).toInt(), (vh * s).toInt())
    }
}

/**
 * Vyro's video player: swipe for volume, brightness and seeking, double-tap to skip,
 * speed control, screen lock, picture-in-picture, and it remembers where you stopped.
 */
class VideoActivity : Activity() {

    private lateinit var p: Palette
    private var uris: List<Uri> = emptyList()
    private var titles: List<String> = emptyList()
    private var index = 0

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var video: AspectVideoView
    private lateinit var controls: FrameLayout
    private lateinit var indicator: TextView
    private lateinit var titleView: TextView
    private lateinit var seek: SeekBar
    private lateinit var timeNow: TextView
    private lateinit var timeAll: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var unlockBtn: ImageButton
    private lateinit var gd: GestureDetector
    private lateinit var audio: AudioManager

    private var mp: MediaPlayer? = null
    private var ready = false
    private var speed = 1f
    private var locked = false
    private var controlsShown = true
    private var tracking = false

    // swipe gesture state
    private var scrollMode = 0   // 0 none, 1 seek, 2 brightness, 3 volume
    private var startPos = 0
    private var startBright = 0.5f
    private var startVol = 0
    private var seekTarget = -1

    private val hideControls = Runnable {
        if (ready && video.isPlaying && !locked) setControlsVisible(false)
    }
    private val hideIndicator = Runnable { indicator.visibility = View.GONE }
    private val ticker = object : Runnable {
        override fun run() {
            if (ready && !tracking) {
                try {
                    val pos = video.currentPosition
                    seek.progress = pos
                    timeNow.text = fmtTime(pos)
                } catch (e: Exception) {
                    // between videos
                }
            }
            handler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Themes.resolve(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (intent.getBooleanExtra("secure", false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        uris = (intent.getStringArrayListExtra("uris") ?: ArrayList<String>()).map { Uri.parse(it) }
        titles = intent.getStringArrayListExtra("titles") ?: ArrayList<String>()
        index = intent.getIntExtra("index", 0)
        if (uris.isEmpty() || index !in uris.indices) {
            finish()
            return
        }
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Video takes over the speakers: pause any background music first.
        MusicService.instance?.pause()

        buildUi()
        startVideo(index)
        handler.post(ticker)
    }

    // ---------- UI ----------

    private fun iconBtn(res: Int, sizeDp: Int, padDp: Int): ImageButton {
        val b = ImageButton(this)
        b.setImageResource(res)
        b.setColorFilter(Color.WHITE)
        b.setBackgroundColor(Color.TRANSPARENT)
        b.scaleType = ImageView.ScaleType.FIT_CENTER
        b.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
        b.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return b
    }

    private fun textBtn(label: String): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 14f
        t.setTypeface(t.typeface, Typeface.BOLD)
        t.setTextColor(Color.WHITE)
        t.gravity = Gravity.CENTER
        t.background = rounded(Color.parseColor("#55FFFFFF"), 26)
        t.layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        return t
    }

    private fun fade(top: Boolean): GradientDrawable {
        val c1 = Color.parseColor("#B0000000")
        val c2 = Color.parseColor("#00000000")
        return GradientDrawable(
            if (top) GradientDrawable.Orientation.TOP_BOTTOM else GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(c1, c2)
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        video = AspectVideoView(this)
        root.addView(video, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))

        val touch = View(this)
        root.addView(touch, FrameLayout.LayoutParams(MATCH, MATCH))
        gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (locked) {
                    unlockBtn.visibility = View.VISIBLE
                    handler.postDelayed({ if (locked) unlockBtn.visibility = View.GONE }, 2500)
                } else {
                    setControlsVisible(!controlsShown)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                val w = root.width.toFloat()
                when {
                    e.x < w / 3f -> seekBy(-10000)
                    e.x > w * 2f / 3f -> seekBy(10000)
                    else -> togglePlay()
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                handleScroll(e1, e2)
                return true
            }
        })
        touch.setOnTouchListener { _, ev ->
            gd.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                endScroll()
            }
            true
        }

        // Big hint text in the middle (volume, brightness, seeking)
        indicator = TextView(this)
        indicator.textSize = 22f
        indicator.setTypeface(indicator.typeface, Typeface.BOLD)
        indicator.setTextColor(Color.WHITE)
        indicator.setPadding(dp(22), dp(14), dp(22), dp(14))
        indicator.background = rounded(Color.parseColor("#AA000000"), 18)
        indicator.visibility = View.GONE
        root.addView(indicator, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        controls = FrameLayout(this)
        root.addView(controls, FrameLayout.LayoutParams(MATCH, MATCH))

        // top bar
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.background = fade(true)
        top.setPadding(dp(8), dp(8), dp(8), dp(24))
        val back = iconBtn(R.drawable.ic_back, 48, 12)
        back.setOnClickListener { finish() }
        titleView = TextView(this)
        titleView.setTextColor(Color.WHITE)
        titleView.textSize = 16f
        titleView.maxLines = 1
        titleView.setTypeface(titleView.typeface, Typeface.BOLD)
        val pip = iconBtn(R.drawable.ic_pip, 48, 12)
        pip.setOnClickListener { enterPip() }
        top.addView(back)
        top.addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(pip)
        controls.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        // middle buttons
        val mid = LinearLayout(this)
        mid.orientation = LinearLayout.HORIZONTAL
        mid.gravity = Gravity.CENTER
        val prev = iconBtn(R.drawable.ic_prev, 56, 14)
        val back10 = textBtn("-10")
        playBtn = iconBtn(R.drawable.ic_pause, 76, 22)
        playBtn.background = rounded(Color.parseColor("#66FFFFFF"), 38)
        val fwd10 = textBtn("+10")
        val next = iconBtn(R.drawable.ic_next, 56, 14)
        prev.setOnClickListener { if (index > 0) startVideo(index - 1) else seekBy(-1000000) }
        next.setOnClickListener { if (index < uris.size - 1) startVideo(index + 1) }
        back10.setOnClickListener { seekBy(-10000) }
        fwd10.setOnClickListener { seekBy(10000) }
        playBtn.setOnClickListener { togglePlay() }
        val mlp = LinearLayout.LayoutParams(dp(76), dp(76))
        mlp.setMargins(dp(10), 0, dp(10), 0)
        mid.addView(prev)
        mid.addView(back10)
        mid.addView(playBtn, mlp)
        mid.addView(fwd10)
        mid.addView(next)
        controls.addView(mid, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        // bottom panel
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.background = fade(false)
        bottom.setPadding(dp(12), dp(28), dp(12), dp(8))

        val seekRow = LinearLayout(this)
        seekRow.orientation = LinearLayout.HORIZONTAL
        seekRow.gravity = Gravity.CENTER_VERTICAL
        timeNow = TextView(this)
        timeNow.setTextColor(Color.WHITE)
        timeNow.textSize = 13f
        timeNow.text = "0:00"
        timeAll = TextView(this)
        timeAll.setTextColor(Color.WHITE)
        timeAll.textSize = 13f
        timeAll.text = "0:00"
        seek = SeekBar(this)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) timeNow.text = fmtTime(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                tracking = true
                handler.removeCallbacks(hideControls)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                tracking = false
                video.seekTo(sb.progress)
                scheduleHide()
            }
        })
        seekRow.addView(timeNow)
        seekRow.addView(seek, LinearLayout.LayoutParams(0, WRAP, 1f))
        seekRow.addView(timeAll)
        bottom.addView(seekRow, LinearLayout.LayoutParams(MATCH, WRAP))

        val tools = LinearLayout(this)
        tools.orientation = LinearLayout.HORIZONTAL
        tools.gravity = Gravity.CENTER
        val lockBtn = iconBtn(R.drawable.ic_lock, 52, 14)
        val speedBtn = iconBtn(R.drawable.ic_speed, 52, 14)
        val aspectBtn = iconBtn(R.drawable.ic_aspect, 52, 14)
        val rotateBtn = iconBtn(R.drawable.ic_rotate, 52, 14)
        lockBtn.setOnClickListener { lockScreen() }
        speedBtn.setOnClickListener { showSpeedDialog() }
        aspectBtn.setOnClickListener { cycleAspect() }
        rotateBtn.setOnClickListener { rotate() }
        val tlp = LinearLayout.LayoutParams(0, dp(52), 1f)
        tools.addView(lockBtn, tlp)
        tools.addView(speedBtn, LinearLayout.LayoutParams(0, dp(52), 1f))
        tools.addView(aspectBtn, LinearLayout.LayoutParams(0, dp(52), 1f))
        tools.addView(rotateBtn, LinearLayout.LayoutParams(0, dp(52), 1f))
        bottom.addView(tools, LinearLayout.LayoutParams(MATCH, WRAP))
        controls.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        // unlock button, only visible while the screen is locked
        unlockBtn = iconBtn(R.drawable.ic_lock, 56, 14)
        unlockBtn.setColorFilter(Color.WHITE)
        unlockBtn.background = rounded(Color.parseColor("#88000000"), 28)
        unlockBtn.visibility = View.GONE
        unlockBtn.setOnClickListener { unlockScreen() }
        val ulp = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER_VERTICAL or Gravity.START)
        ulp.leftMargin = dp(16)
        root.addView(unlockBtn, ulp)

        setContentView(root)
    }

    // ---------- playback ----------

    private fun titleOf(i: Int): String = if (i in titles.indices) titles[i] else "Video"

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun startVideo(i: Int) {
        saveProgress()
        index = i
        ready = false
        titleView.text = titleOf(i)
        video.vw = 0
        video.vh = 0
        val resume = readProgress(uris[i])
        video.setOnPreparedListener { m ->
            mp = m
            video.vw = m.videoWidth
            video.vh = m.videoHeight
            video.requestLayout()
            seek.max = m.duration
            timeAll.text = fmtTime(m.duration)
            ready = true
            if (resume > 5000 && resume < m.duration - 5000) {
                m.seekTo(resume)
                toast("Resumed at " + fmtTime(resume))
            }
            if (m.videoWidth > m.videoHeight &&
                resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            ) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            video.start()
            applySpeed()
            setPlayIcon(true)
            setControlsVisible(true)
        }
        video.setOnCompletionListener {
            clearProgress(uris[index])
            if (index < uris.size - 1) {
                startVideo(index + 1)
            } else {
                setPlayIcon(false)
                setControlsVisible(true)
            }
        }
        video.setOnErrorListener { _, _, _ ->
            toast("Can't play this video")
            true
        }
        video.setVideoURI(uris[i])
    }

    private fun setPlayIcon(playing: Boolean) {
        playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun togglePlay() {
        if (!ready) return
        if (video.isPlaying) {
            video.pause()
            setPlayIcon(false)
            setControlsVisible(true)
        } else {
            video.start()
            setPlayIcon(true)
            scheduleHide()
        }
    }

    private fun seekBy(ms: Int) {
        if (!ready) return
        val dur = video.duration
        val np = (video.currentPosition + ms).coerceIn(0, max(dur, 0))
        video.seekTo(np)
        showIndicator((if (ms > 0) "+" else "-") + abs(ms / 1000) + "s")
    }

    private fun applySpeed() {
        val m = mp ?: return
        try {
            val wasPlaying = video.isPlaying
            m.playbackParams = m.playbackParams.setSpeed(speed)
            if (!wasPlaying) video.pause()
        } catch (e: Exception) {
            // this phone can't change speed for this video
        }
    }

    private fun showSpeedDialog() {
        val labels = arrayOf("0.5x", "0.75x", "1x (normal)", "1.25x", "1.5x", "2x")
        val values = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        var checked = 2
        for (k in values.indices) if (values[k] == speed) checked = k
        AlertDialog.Builder(this)
            .setTitle("Playback speed")
            .setSingleChoiceItems(labels, checked) { d, which ->
                speed = values[which]
                applySpeed()
                showIndicator("Speed " + labels[which].substringBefore(' '))
                d.dismiss()
            }
            .show()
    }

    private fun cycleAspect() {
        video.mode = (video.mode + 1) % 3
        video.requestLayout()
        showIndicator(
            when (video.mode) {
                0 -> "Fit"
                1 -> "Fill"
                else -> "Stretch"
            }
        )
    }

    private fun rotate() {
        requestedOrientation =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
    }

    // ---------- lock, controls and indicator ----------

    private fun lockScreen() {
        locked = true
        setControlsVisible(false)
        unlockBtn.visibility = View.VISIBLE
        handler.postDelayed({ if (locked) unlockBtn.visibility = View.GONE }, 2500)
        toast("Screen locked. Tap the screen, then the lock, to unlock")
    }

    private fun unlockScreen() {
        locked = false
        unlockBtn.visibility = View.GONE
        setControlsVisible(true)
    }

    private fun setControlsVisible(show: Boolean) {
        controlsShown = show
        controls.visibility = if (show && !locked) View.VISIBLE else View.GONE
        if (show) scheduleHide() else handler.removeCallbacks(hideControls)
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        handler.postDelayed(hideControls, 3500)
    }

    private fun showIndicator(text: String) {
        indicator.text = text
        indicator.visibility = View.VISIBLE
        handler.removeCallbacks(hideIndicator)
        handler.postDelayed(hideIndicator, 900)
    }

    // ---------- swipe gestures ----------

    private fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0f) return b
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) {
            0.5f
        }
    }

    private fun handleScroll(e1: MotionEvent?, e2: MotionEvent) {
        if (e1 == null || locked || !ready) return
        val dx = e2.x - e1.x
        val dy = e2.y - e1.y
        val w = max(root.width.toFloat(), 1f)
        val h = max(root.height.toFloat(), 1f)

        if (scrollMode == 0) {
            if (abs(dx) < 24f && abs(dy) < 24f) return
            if (abs(dx) > abs(dy)) {
                scrollMode = 1
                startPos = video.currentPosition
            } else if (e1.x < w / 2f) {
                scrollMode = 2
                startBright = currentBrightness()
            } else {
                scrollMode = 3
                startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
        }

        when (scrollMode) {
            1 -> {
                val dur = video.duration
                seekTarget = (startPos + dx / w * 90000f).toInt().coerceIn(0, max(dur, 0))
                showIndicator(fmtTime(seekTarget) + " / " + fmtTime(dur))
            }
            2 -> {
                val b = (startBright - dy / h * 1.2f).coerceIn(0.02f, 1f)
                val lp = window.attributes
                lp.screenBrightness = b
                window.attributes = lp
                showIndicator("Brightness " + (b * 100).roundToInt() + "%")
            }
            3 -> {
                val maxV = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val v = (startVol - dy / h * maxV * 1.2f).roundToInt().coerceIn(0, maxV)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                showIndicator("Volume " + (v * 100 / max(maxV, 1)) + "%")
            }
        }
    }

    private fun endScroll() {
        if (scrollMode == 1 && seekTarget >= 0) video.seekTo(seekTarget)
        scrollMode = 0
        seekTarget = -1
    }

    // ---------- picture in picture ----------

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) {
            toast("Picture-in-picture needs Android 8 or newer")
            return
        }
        try {
            val b = PictureInPictureParams.Builder()
            if (video.vw > 0 && video.vh > 0) {
                val r = (video.vw.toFloat() / video.vh).coerceIn(0.45f, 2.3f)
                b.setAspectRatio(Rational((r * 1000).toInt(), 1000))
            }
            enterPictureInPictureMode(b.build())
        } catch (e: Exception) {
            toast("Picture-in-picture isn't available here")
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (ready && video.isPlaying && !locked && Build.VERSION.SDK_INT >= 26) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            controls.visibility = View.GONE
        } else {
            setControlsVisible(true)
        }
    }

    // ---------- remembering the position ----------

    private fun resumeKey(u: Uri): String = u.toString().hashCode().toString()

    private fun readProgress(u: Uri): Int =
        getSharedPreferences("vyro_resume", Context.MODE_PRIVATE).getInt(resumeKey(u), 0)

    private fun clearProgress(u: Uri) {
        getSharedPreferences("vyro_resume", Context.MODE_PRIVATE).edit().remove(resumeKey(u)).apply()
    }

    private fun saveProgress() {
        if (!ready || index !in uris.indices) return
        try {
            val pos = video.currentPosition
            val dur = video.duration
            val sp = getSharedPreferences("vyro_resume", Context.MODE_PRIVATE)
            if (pos > 5000 && pos < dur - 5000) {
                sp.edit().putInt(resumeKey(uris[index]), pos).apply()
            } else {
                sp.edit().remove(resumeKey(uris[index])).apply()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---------- lifecycle ----------

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
        val inPip = Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode
        if (!inPip && ready) {
            video.pause()
            setPlayIcon(false)
        }
    }

    override fun onStop() {
        super.onStop()
        if (ready) {
            video.pause()
            setPlayIcon(false)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        video.stopPlayback()
        super.onDestroy()
    }
}
