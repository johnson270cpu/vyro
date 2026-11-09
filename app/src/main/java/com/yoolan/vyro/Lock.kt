package com.yoolan.vyro

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import java.security.MessageDigest
import java.security.SecureRandom

/** Stores the vault lock (PIN or pattern) as a salted hash. The secret itself is never saved. */
class LockStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("vyro_lock", Context.MODE_PRIVATE)

    val isSet: Boolean
        get() = sp.getString("hash", null) != null

    /** "pin" or "pattern" */
    val type: String
        get() = sp.getString("type", "pin") ?: "pin"

    fun set(type: String, secret: String) {
        val salt = randomHex()
        sp.edit()
            .putString("type", type)
            .putString("salt", salt)
            .putString("hash", hash(salt, secret))
            .putInt("fails", 0)
            .putLong("lockedUntil", 0L)
            .apply()
    }

    fun check(secret: String): Boolean {
        val salt = sp.getString("salt", null) ?: return false
        val stored = sp.getString("hash", null) ?: return false
        val ok = hash(salt, secret) == stored
        if (ok) {
            sp.edit().putInt("fails", 0).putLong("lockedUntil", 0L).apply()
        }
        return ok
    }

    fun recordFailure() {
        val fails = sp.getInt("fails", 0) + 1
        if (fails >= 5) {
            sp.edit()
                .putInt("fails", 0)
                .putLong("lockedUntil", System.currentTimeMillis() + 30_000L)
                .apply()
        } else {
            sp.edit().putInt("fails", fails).apply()
        }
    }

    fun remainingLockMs(): Long {
        val left = sp.getLong("lockedUntil", 0L) - System.currentTimeMillis()
        return if (left > 0) left else 0L
    }

    private fun randomHex(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun hash(salt: String, secret: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest((salt + secret).toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }
}

/** A 3x3 dot grid. Draw a path through the dots with your finger. */
@SuppressLint("ClickableViewAccessibility")
class PatternView(ctx: Context, private val pal: Palette) : View(ctx) {

    var onDone: ((List<Int>) -> Unit)? = null

    private val sel = ArrayList<Int>()
    private var fx = 0f
    private var fy = 0f
    private var touching = false
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        line.strokeWidth = ctx.dp(6).toFloat()
        line.strokeCap = Paint.Cap.ROUND
        line.color = pal.accent
        dot.style = Paint.Style.FILL
    }

    private fun cell(): Float = width / 3f
    private fun cx(i: Int): Float = (i % 3 + 0.5f) * cell()
    private fun cy(i: Int): Float = (i / 3 + 0.5f) * cell()

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        for (k in 1 until sel.size) {
            c.drawLine(cx(sel[k - 1]), cy(sel[k - 1]), cx(sel[k]), cy(sel[k]), line)
        }
        if (touching && sel.isNotEmpty()) {
            val l = sel[sel.size - 1]
            c.drawLine(cx(l), cy(l), fx, fy, line)
        }
        for (i in 0..8) {
            val on = sel.contains(i)
            dot.color = if (on) pal.accent else pal.hint
            c.drawCircle(cx(i), cy(i), context.dp(if (on) 12 else 8).toFloat(), dot)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sel.clear()
                touching = true
                fx = e.x
                fy = e.y
                hit(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                fx = e.x
                fy = e.y
                hit(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                val result = ArrayList(sel)
                invalidate()
                if (e.actionMasked == MotionEvent.ACTION_UP && result.isNotEmpty()) {
                    onDone?.invoke(result)
                }
                postDelayed({
                    sel.clear()
                    invalidate()
                }, 350)
            }
        }
        return true
    }

    private fun hit(x: Float, y: Float) {
        val radius = cell() * 0.3f
        for (i in 0..8) {
            if (sel.contains(i)) continue
            val dx = x - cx(i)
            val dy = y - cy(i)
            if (dx * dx + dy * dy < radius * radius) {
                sel.add(i)
                return
            }
        }
    }
}
