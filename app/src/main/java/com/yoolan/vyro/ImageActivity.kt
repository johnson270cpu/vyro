package com.yoolan.vyro

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/** Picture viewer: swipe to change picture, double-tap to zoom, drag to move around when zoomed. */
class ImageActivity : Activity() {

    private var uris: List<Uri> = emptyList()
    private var titles: List<String> = emptyList()
    private var index = 0
    private lateinit var imageView: ImageView
    private lateinit var counter: TextView
    private var zoomed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        blackBars()
        build()
        showImage(index)
    }

    @Suppress("DEPRECATION")
    private fun blackBars() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = 0
    }

    private fun titleOf(i: Int): String = if (i in titles.indices) titles[i] else "Picture"

    @SuppressLint("ClickableViewAccessibility")
    private fun build() {
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        imageView = ImageView(this)
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        frame.addView(imageView, FrameLayout.LayoutParams(MATCH, MATCH))

        counter = TextView(this)
        counter.setTextColor(Color.WHITE)
        counter.textSize = 14f
        counter.maxLines = 1
        counter.setPadding(dp(16), dp(10), dp(16), dp(10))
        counter.background = rounded(Color.parseColor("#99000000"), 16)
        val clp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        clp.bottomMargin = dp(24)
        frame.addView(counter, clp)

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoomed) {
                    resetZoom()
                } else {
                    zoomed = true
                    imageView.pivotX = e.x
                    imageView.pivotY = e.y
                    imageView.scaleX = 2.6f
                    imageView.scaleY = 2.6f
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (zoomed) {
                    imageView.translationX = imageView.translationX - dx
                    imageView.translationY = imageView.translationY - dy
                }
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (zoomed || e1 == null) return false
                val d = e2.x - e1.x
                if (abs(d) > 150f && abs(velocityX) > 200f) {
                    if (d < 0) showImage((index + 1) % uris.size)
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
    }

    private fun resetZoom() {
        zoomed = false
        imageView.scaleX = 1f
        imageView.scaleY = 1f
        imageView.translationX = 0f
        imageView.translationY = 0f
    }

    private fun showImage(i: Int) {
        index = i
        resetZoom()
        counter.text = titleOf(i) + "   " + (i + 1) + " / " + uris.size
        val target = uris[i]
        Thread {
            val bmp: Bitmap? = try {
                ImageLoader.decode(this, target, 2048)
            } catch (e: Throwable) {
                null
            }
            runOnUiThread {
                if (!isDestroyed && index == i) {
                    if (bmp != null) {
                        imageView.setImageBitmap(bmp)
                    } else {
                        Toast.makeText(this, "Can't open this picture", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }.start()
    }
}
