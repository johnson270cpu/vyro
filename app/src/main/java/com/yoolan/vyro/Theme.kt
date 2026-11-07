package com.yoolan.vyro

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/** User settings, stored on the phone. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.getSharedPreferences("vyro_prefs", Context.MODE_PRIVATE)

    /** 0 = follow system, 1 = light, 2 = dark */
    var themeMode: Int
        get() = sp.getInt("theme", 0)
        set(v) { sp.edit().putInt("theme", v).apply() }

    /** index into ENGINES / SEARCH_URLS */
    var engine: Int
        get() = sp.getInt("engine", 0)
        set(v) { sp.edit().putInt("engine", v).apply() }

    var adBlock: Boolean
        get() = sp.getBoolean("adblock", true)
        set(v) { sp.edit().putBoolean("adblock", v).apply() }

    var desktop: Boolean
        get() = sp.getBoolean("desktop", false)
        set(v) { sp.edit().putBoolean("desktop", v).apply() }

    var news: Boolean
        get() = sp.getBoolean("news", true)
        set(v) { sp.edit().putBoolean("news", v).apply() }

    var newsCache: String?
        get() = sp.getString("news_cache", null)
        set(v) { sp.edit().putString("news_cache", v).apply() }

    var newsTime: Long
        get() = sp.getLong("news_time", 0L)
        set(v) { sp.edit().putLong("news_time", v).apply() }

    fun snapshot(): String = "$themeMode|$engine|$adBlock|$desktop|$news"

    companion object {
        val ENGINES = arrayOf("DuckDuckGo", "Google", "Bing")
        val SEARCH_URLS = arrayOf(
            "https://duckduckgo.com/?q=",
            "https://www.google.com/search?q=",
            "https://www.bing.com/search?q="
        )
    }
}

class Palette(
    val bg: Int,
    val surface: Int,
    val pill: Int,
    val text: Int,
    val hint: Int,
    val accent: Int,
    val icon: Int,
    val onAccent: Int,
    val dark: Boolean
)

object Themes {
    private val DARK = Palette(
        bg = Color.parseColor("#0E0B1F"),
        surface = Color.parseColor("#181330"),
        pill = Color.parseColor("#261F47"),
        text = Color.parseColor("#F2F3FA"),
        hint = Color.parseColor("#8D90B0"),
        accent = Color.parseColor("#FF5C93"),
        icon = Color.parseColor("#DADCF2"),
        onAccent = Color.parseColor("#1A0510"),
        dark = true
    )
    private val LIGHT = Palette(
        bg = Color.parseColor("#F4F5FB"),
        surface = Color.parseColor("#FFFFFF"),
        pill = Color.parseColor("#E9EBF5"),
        text = Color.parseColor("#14152A"),
        hint = Color.parseColor("#6B6E8C"),
        accent = Color.parseColor("#E0356F"),
        icon = Color.parseColor("#33364F"),
        onAccent = Color.parseColor("#FFFFFF"),
        dark = false
    )

    fun resolve(ctx: Context): Palette {
        val mode = Prefs(ctx).themeMode
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (mode == 2 || (mode == 0 && night)) DARK else LIGHT
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** Colours the status bar and navigation bar to match the theme. */
@Suppress("DEPRECATION")
fun Activity.applyBars(p: Palette, navColor: Int) {
    window.statusBarColor = p.surface
    window.navigationBarColor = navColor
    val mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    var flags = window.decorView.systemUiVisibility
    flags = if (p.dark) flags and mask.inv() else flags or mask
    window.decorView.systemUiVisibility = flags
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.rounded(color: Int, radiusDp: Int): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = dp(radiusDp).toFloat()
    return g
}

/** Top bar with a back arrow and a title, used by the secondary screens. */
fun Activity.buildHeader(p: Palette, title: String): LinearLayout {
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
    val t = TextView(this)
    t.text = title
    t.textSize = 20f
    t.setTypeface(t.typeface, Typeface.BOLD)
    t.setTextColor(p.text)
    header.addView(t)
    return header
}
