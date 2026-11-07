package com.yoolan.vyro

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Lock screen for the vault.
 * Extra "mode": "setup" (create or change the lock) or "unlock".
 * Returns RESULT_OK when the lock was created or the right secret was entered.
 */
class LockActivity : Activity() {

    private lateinit var p: Palette
    private lateinit var store: LockStore
    private lateinit var titleView: TextView
    private lateinit var hintView: TextView
    private lateinit var dotsView: TextView
    private lateinit var area: FrameLayout
    private lateinit var switchRow: LinearLayout

    private var mode = "unlock"
    private var type = "pin"
    private var first: String? = null
    private val pin = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        p = Themes.resolve(this)
        applyBars(p, p.bg)
        store = LockStore(this)
        mode = intent.getStringExtra("mode") ?: "unlock"
        type = if (mode == "unlock") store.type else "pin"

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setBackgroundColor(p.bg)
        root.setPadding(dp(24), dp(32), dp(24), dp(24))

        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_vault)
        icon.setColorFilter(p.accent)
        root.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))

        titleView = TextView(this)
        titleView.textSize = 22f
        titleView.setTypeface(titleView.typeface, Typeface.BOLD)
        titleView.setTextColor(p.text)
        titleView.gravity = Gravity.CENTER
        titleView.setPadding(0, dp(14), 0, dp(4))
        root.addView(titleView, LinearLayout.LayoutParams(MATCH, WRAP))

        hintView = TextView(this)
        hintView.textSize = 14f
        hintView.setTextColor(p.hint)
        hintView.gravity = Gravity.CENTER
        root.addView(hintView, LinearLayout.LayoutParams(MATCH, WRAP))

        dotsView = TextView(this)
        dotsView.textSize = 22f
        dotsView.setTextColor(p.accent)
        dotsView.gravity = Gravity.CENTER
        dotsView.minHeight = dp(44)
        dotsView.setPadding(0, dp(12), 0, dp(8))
        root.addView(dotsView, LinearLayout.LayoutParams(MATCH, WRAP))

        area = FrameLayout(this)
        root.addView(area, LinearLayout.LayoutParams(MATCH, 0, 1f))

        switchRow = LinearLayout(this)
        switchRow.orientation = LinearLayout.HORIZONTAL
        switchRow.gravity = Gravity.CENTER
        if (mode == "setup") {
            switchRow.addView(chip("Use PIN") { switchType("pin") })
            switchRow.addView(chip("Use pattern") { switchType("pattern") })
        }
        root.addView(switchRow, LinearLayout.LayoutParams(MATCH, WRAP))

        setContentView(root)
        render()
    }

    private fun chip(label: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.textSize = 15f
        t.setTextColor(p.accent)
        t.setPadding(dp(18), dp(12), dp(18), dp(12))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun switchType(newType: String) {
        type = newType
        first = null
        render()
    }

    private fun typeName(): String = if (type == "pin") "PIN" else "pattern"

    private fun render() {
        area.removeAllViews()
        pin.setLength(0)
        updateDots()
        if (mode == "unlock") {
            titleView.text = "Enter your " + typeName()
            setHint("Vault is locked", false)
        } else if (first == null) {
            titleView.text = "Create a " + typeName()
            setHint(
                if (type == "pin") "Choose 4 to 8 digits. If you forget it, hidden files can't be recovered."
                else "Connect at least 4 dots. If you forget it, hidden files can't be recovered.",
                false
            )
        } else {
            titleView.text = "Confirm your " + typeName()
            setHint("Enter it once more", false)
        }
        if (type == "pin") area.addView(pinPad()) else area.addView(patternPad())
    }

    private fun setHint(text: String, error: Boolean) {
        hintView.text = text
        hintView.setTextColor(if (error) Color.parseColor("#FF6B6B") else p.hint)
    }

    private fun updateDots() {
        dotsView.text = if (pin.isEmpty()) " " else (0 until pin.length).joinToString(" ") { "●" }
    }

    // ---------- PIN ----------

    private fun key(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 20f
        b.isAllCaps = false
        b.setTextColor(p.text)
        b.background = rounded(p.pill, 16)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun pinPad(): View {
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        grid.gravity = Gravity.CENTER
        val labels = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "DEL", "0", "OK")
        for (r in 0 until 4) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER
            for (c in 0 until 3) {
                val label = labels[r * 3 + c]
                val b = key(label) { onPinKey(label) }
                val lp = LinearLayout.LayoutParams(dp(84), dp(58))
                lp.setMargins(dp(6), dp(5), dp(6), dp(5))
                row.addView(b, lp)
            }
            grid.addView(row)
        }
        grid.layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)
        return grid
    }

    private fun onPinKey(label: String) {
        when (label) {
            "DEL" -> if (pin.isNotEmpty()) pin.deleteCharAt(pin.length - 1)
            "OK" -> {
                if (pin.length < 4) {
                    setHint("Use at least 4 digits", true)
                } else {
                    submit(pin.toString())
                    return
                }
            }
            else -> if (pin.length < 8) pin.append(label)
        }
        updateDots()
    }

    // ---------- pattern ----------

    private fun patternPad(): View {
        val v = PatternView(this, p)
        v.onDone = { list ->
            if (list.size < 4) {
                setHint("Connect at least 4 dots", true)
            } else {
                submit(list.joinToString("-"))
            }
        }
        v.layoutParams = FrameLayout.LayoutParams(dp(280), dp(280), Gravity.CENTER)
        return v
    }

    // ---------- checking ----------

    private fun submit(secret: String) {
        if (mode == "unlock") {
            val wait = store.remainingLockMs()
            if (wait > 0) {
                setHint("Too many wrong tries. Wait " + (wait / 1000 + 1) + " seconds.", true)
                pin.setLength(0)
                updateDots()
                return
            }
            if (store.check(secret)) {
                setResult(RESULT_OK)
                finish()
            } else {
                store.recordFailure()
                setHint("Wrong " + typeName() + ". Try again.", true)
                pin.setLength(0)
                updateDots()
            }
        } else {
            val f = first
            if (f == null) {
                first = secret
                pin.setLength(0)
                titleView.text = "Confirm your " + typeName()
                setHint("Enter it once more", false)
                updateDots()
            } else if (f == secret) {
                store.set(type, secret)
                setResult(RESULT_OK)
                finish()
            } else {
                first = null
                render()
                setHint("They didn't match. Start again.", true)
            }
        }
    }
}
