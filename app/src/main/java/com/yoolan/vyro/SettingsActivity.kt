package com.yoolan.vyro

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var p: Palette

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        p = Themes.resolve(this)
        applyBars(p, p.bg)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(p.bg)

        // Header with back arrow
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setBackgroundColor(p.surface)
        header.setPadding(dp(8), dp(8), dp(16), dp(8))
        val back = ImageButton(this)
        back.setImageResource(R.drawable.ic_back)
        back.setColorFilter(p.icon)
        back.setBackgroundColor(Color.TRANSPARENT)
        back.setPadding(dp(12), dp(12), dp(12), dp(12))
        back.contentDescription = "Back"
        back.setOnClickListener { finish() }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        val title = TextView(this)
        title.text = "Settings"
        title.textSize = 20f
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.setTextColor(p.text)
        header.addView(title)
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(12), dp(20), dp(32))

        col.addView(section("Appearance"))
        col.addView(radios(arrayOf("System default", "Light", "Dark"), prefs.themeMode) {
            prefs.themeMode = it
            recreate()
        })

        col.addView(section("Search engine"))
        col.addView(radios(Prefs.ENGINES, prefs.engine) { prefs.engine = it })

        col.addView(section("Browsing"))
        col.addView(toggle("Block ads and trackers", prefs.adBlock) { prefs.adBlock = it })
        col.addView(toggle("Desktop site", prefs.desktop) { prefs.desktop = it })
        col.addView(toggle("Show daily news on the home page", prefs.news) { prefs.news = it })

        col.addView(section("Privacy"))
        col.addView(actionButton("Clear cookies and site data") {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            Toast.makeText(this, "Cookies and site data cleared", Toast.LENGTH_SHORT).show()
        })
        col.addView(actionButton("Clear cache") {
            val tmp = WebView(this)
            tmp.clearCache(true)
            tmp.destroy()
            Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show()
        })

        col.addView(section("About"))
        col.addView(label("Vyro Browser  ·  version 0.4", 16f, p.text, true))
        col.addView(label("Made by Yoolan", 15f, p.accent, true))
        col.addView(label("Browse the web and play free games.", 14f, p.hint, false))

        val scroll = ScrollView(this)
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        t.setPadding(0, dp(4), 0, dp(4))
        return t
    }

    private fun section(text: String): TextView {
        val t = label(text.uppercase(), 12f, p.accent, true)
        t.letterSpacing = 0.1f
        t.setPadding(0, dp(24), 0, dp(6))
        return t
    }

    private fun radios(labels: Array<String>, selected: Int, onPick: (Int) -> Unit): RadioGroup {
        val g = RadioGroup(this)
        g.orientation = RadioGroup.VERTICAL
        labels.forEachIndexed { i, l ->
            val rb = RadioButton(this)
            rb.id = View.generateViewId()
            rb.text = l
            rb.textSize = 16f
            rb.setTextColor(p.text)
            rb.buttonTintList = ColorStateList.valueOf(p.accent)
            g.addView(rb)
            if (i == selected) rb.isChecked = true
        }
        g.setOnCheckedChangeListener { grp, id ->
            val idx = grp.indexOfChild(grp.findViewById<View>(id))
            if (idx >= 0) onPick(idx)
        }
        return g
    }

    private fun toggle(text: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
        val s = Switch(this)
        s.text = text
        s.textSize = 16f
        s.setTextColor(p.text)
        s.setPadding(0, dp(8), 0, dp(8))
        s.isChecked = checked
        s.setOnCheckedChangeListener { _, c -> onChange(c) }
        return s
    }

    private fun actionButton(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextColor(p.onAccent)
        b.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(p.accent)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        lp.topMargin = dp(8)
        b.layoutParams = lp
        b.setOnClickListener { onClick() }
        return b
    }
}
