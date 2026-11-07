package com.yoolan.vyro

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.GestureDetector
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import kotlin.math.abs

/** Vyro's own player for music, videos and pictures. */
class PlayerActivity : Activity() {

    private lateinit var p: Palette
    private var uris: List<Uri> = emptyList()
    private var titles: List<String> = emptyList()
    private var index = 0
    private var kind = 0

    private val handler = Handler(Looper.getMainLooper())

    // audio
    private var mp: MediaPlayer? = null
    private var ready = false
    private lateinit var seek: SeekBar
    private lateinit var timeNow: TextView
    private lateinit var timeAll: TextView
    private lateinit var titleView: TextView
    private lateinit var playBtn: ImageButton

    // video
    private var video: VideoView? = null

    // image
    private lateinit var imageView: ImageView
    private lateinit var counter: TextView

    private val ticker = object : Runnable {
        override fun run() {
            val m = mp
            if (m != null && ready) {
                try {
                    seek.progress = m.currentPosition
                    timeNow.text = fmt(m.currentPosition)
                } catch (e: Exception) {
                    // player is changing track
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Themes.resolve(this)
        if (intent.getBooleanExtra("secure", false)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        kind = intent.getIntExtra("kind", 0)
        uris = (intent.getStringArrayListExtra("uris") ?: ArrayList<String>()).map { Uri.parse(it) }
        titles = intent.getStringArrayListExtra("titles") ?: ArrayList<String>()
        index = intent.getIntExtra("index", 0)
        if (uris.isEmpty() || index !in uris.indices) {
            finish()
            return
        }
        when (kind) {
            0 -> buildAudio()
            1 -> buildVideo()
            else -> buildImage()
        }
    }

    private fun titleOf(i: Int): String = if (i in titles.indices) titles[i] else "Untitled"

    private fun fmt(ms: Int): String = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun flatButton(res: Int, size: Int, desc: String): ImageButton {
        val b = ImageButton(this)
        b.setImageResource(res)
        b.setColorFilter(p.text)
        b.setBackgroundColor(Color.TRANSPARENT)
        b.scaleType = ImageView.ScaleType.FIT_CENTER
        b.setPadding(dp(size / 4), dp(size / 4), dp(size / 4), dp(size / 4))
        b.contentDescription = desc
        return b
    }

    @Suppress("DEPRECATION")
    private fun blackBars() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = 0
    }

    // ---------- audio ----------

    private fun buildAudio() {
        applyBars(p, p.bg)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(p.bg)
        root.addView(buildHeader(p, "Now playing"), LinearLayout.LayoutParams(MATCH, WRAP))

        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.gravity = Gravity.CENTER_HORIZONTAL
        body.setPadding(dp(28), dp(36), dp(28), dp(28))

        val art = ImageView(this)
        art.setImageResource(R.drawable.ic_music)
        art.setColorFilter(p.accent)
        art.setPadding(dp(56), dp(56), dp(56), dp(56))
        art.background = rounded(p.pill, 40)
        body.addView(art, LinearLayout.LayoutParams(dp(220), dp(220)))

        titleView = TextView(this)
        titleView.textSize = 20f
        titleView.setTypeface(titleView.typeface, Typeface.BOLD)
        titleView.setTextColor(p.text)
        titleView.gravity = Gravity.CENTER
        titleView.maxLines = 2
        titleView.setPadding(0, dp(28), 0, dp(20))
        body.addView(titleView, LinearLayout.LayoutParams(MATCH, WRAP))

        seek = SeekBar(this)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && ready) mp?.seekTo(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        body.addView(seek, LinearLayout.LayoutParams(MATCH, WRAP))

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
        val prev = flatButton(R.drawable.ic_prev, 72, "Previous")
        playBtn = flatButton(R.drawable.ic_pause, 88, "Play or pause")
        playBtn.setColorFilter(p.onAccent)
        playBtn.background = rounded(p.accent, 44)
        val next = flatButton(R.drawable.ic_next, 72, "Next")
        prev.setOnClickListener { playAudio(if (index > 0) index - 1 else uris.size - 1) }
        next.setOnClickListener { playAudio((index + 1) % uris.size) }
        playBtn.setOnClickListener { togglePlay() }
        controls.addView(prev, LinearLayout.LayoutParams(dp(72), dp(72)))
        val plp = LinearLayout.LayoutParams(dp(88), dp(88))
        plp.setMargins(dp(16), 0, dp(16), 0)
        controls.addView(playBtn, plp)
        controls.addView(next, LinearLayout.LayoutParams(dp(72), dp(72)))
        val clp = LinearLayout.LayoutParams(MATCH, WRAP)
        clp.topMargin = dp(16)
        body.addView(controls, clp)

        root.addView(body, LinearLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        playAudio(index)
        handler.post(ticker)
    }

    private fun playAudio(i: Int) {
        index = i
        titleView.text = titleOf(i)
        ready = false
        seek.progress = 0
        timeNow.text = "0:00"
        mp?.release()
        val m = MediaPlayer()
        mp = m
        try {
            m.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            m.setDataSource(this, uris[i])
            m.setOnPreparedListener { pl ->
                ready = true
                seek.max = pl.duration
                timeAll.text = fmt(pl.duration)
                pl.start()
                playBtn.setImageResource(R.drawable.ic_pause)
            }
            m.setOnCompletionListener { playAudio((index + 1) % uris.size) }
            m.setOnErrorListener { _, _, _ ->
                toast("Can't play this file")
                true
            }
            m.prepareAsync()
        } catch (e: Exception) {
            toast("Can't play this file")
        }
    }

    private fun togglePlay() {
        val m = mp ?: return
        if (!ready) return
        if (m.isPlaying) {
            m.pause()
            playBtn.setImageResource(R.drawable.ic_play)
        } else {
            m.start()
            playBtn.setImageResource(R.drawable.ic_pause)
        }
    }

    // ---------- video ----------

    private fun buildVideo() {
        blackBars()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        val v = VideoView(this)
        video = v
        val mc = MediaController(this)
        mc.setAnchorView(v)
        mc.setPrevNextListeners(
            { startVideo((index + 1) % uris.size) },
            { startVideo(if (index > 0) index - 1 else uris.size - 1) }
        )
        v.setMediaController(mc)
        v.setOnCompletionListener { if (uris.size > 1) startVideo((index + 1) % uris.size) }
        v.setOnErrorListener { _, _, _ ->
            toast("Can't play this video")
            true
        }
        frame.addView(v, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        setContentView(frame)
        startVideo(index)
    }

    private fun startVideo(i: Int) {
        index = i
        val v = video ?: return
        v.setVideoURI(uris[i])
        v.start()
        toast(titleOf(i))
    }

    // ---------- pictures ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun buildImage() {
        blackBars()
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        imageView = ImageView(this)
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        frame.addView(imageView, FrameLayout.LayoutParams(MATCH, MATCH))

        counter = TextView(this)
        counter.setTextColor(Color.WHITE)
        counter.textSize = 14f
        counter.setPadding(dp(16), dp(10), dp(16), dp(10))
        counter.background = rounded(Color.parseColor("#99000000"), 16)
        val clp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        clp.bottomMargin = dp(24)
        frame.addView(counter, clp)

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                if (abs(dx) > 150 && abs(velocityX) > 200) {
                    if (dx < 0) showImage((index + 1) % uris.size)
                    else showImage(if (index > 0) index - 1 else uris.size - 1)
                    return true
                }
                return false
            }
        })
        imageView.setOnTouchListener { _, ev ->
            gd.onTouchEvent(ev)
            true
        }
        setContentView(frame)
        showImage(index)
    }

    private fun showImage(i: Int) {
        index = i
        counter.text = titleOf(i) + "   " + (i + 1) + " / " + uris.size
        val target = uris[i]
        Thread {
            val bmp: Bitmap? = try {
                ImageLoader.decode(this, target, 2048)
            } catch (e: Throwable) {
                null
            }
            runOnUiThread {
                if (isDestroyed || index != i) return@runOnUiThread
                if (bmp != null) imageView.setImageBitmap(bmp) else toast("Can't open this picture")
            }
        }.start()
    }

    // ---------- lifecycle ----------

    override fun onPause() {
        super.onPause()
        try {
            val m = mp
            if (m != null && ready && m.isPlaying) {
                m.pause()
                playBtn.setImageResource(R.drawable.ic_play)
            }
            video?.pause()
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        mp?.release()
        mp = null
        video?.stopPlayback()
        super.onDestroy()
    }
}
