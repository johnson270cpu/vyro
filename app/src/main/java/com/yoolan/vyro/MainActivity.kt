package com.yoolan.vyro

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: View
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var pill: View
    private lateinit var lockIcon: ImageView
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var web: WebView
    private lateinit var dlBtn: ImageButton
    private val navIcons = ArrayList<ImageView>()
    private val navLabels = ArrayList<TextView>()
    private val navPills = ArrayList<View>()
    private var videoFound = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tintedIcons: List<ImageView>
    private lateinit var defaultUa: String

    private var lastKey = ""
    private var pendingDownload: (() -> Unit)? = null
    private var fetching = false

    @Volatile
    private var adBlock = true

    // Small ad/tracker blocklist. Extend as you like.
    private val blockedHosts = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "adnxs.com",
        "taboola.com",
        "outbrain.com",
        "popads.net",
        "propellerads.com",
        "adsterra.com",
        "ads.pubmatic.com",
        "scorecardresearch.com"
    )

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        root = findViewById(R.id.root)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        pill = findViewById(R.id.pill)
        lockIcon = findViewById(R.id.lockIcon)
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        web = findViewById(R.id.web)

        val reloadBtn = findViewById<ImageButton>(R.id.reloadBtn)
        val backBtn = findViewById<ImageButton>(R.id.backBtn)
        dlBtn = findViewById(R.id.dlBtn)
        tintedIcons = listOf(lockIcon, reloadBtn, backBtn)
        buildNav()

        defaultUa = web.settings.userAgentString
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true            // game saves (localStorage)
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().setAcceptCookie(true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (!adBlock) return null
                val host = request.url.host ?: return null
                if (isBlocked(host)) {
                    return WebResourceResponse(
                        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                    )
                }
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val scheme = request.url.scheme ?: return false
                if (scheme == "http" || scheme == "https" || scheme == "file") return false

                // Links inside Vyro's own home page: vyro://media, vyro://vault, vyro://settings
                if (scheme == "vyro") {
                    when (request.url.host) {
                        "media" -> startActivity(Intent(this@MainActivity, MediaActivity::class.java))
                        "vault" -> startActivity(Intent(this@MainActivity, VaultActivity::class.java))
                        "settings" -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    }
                    return true
                }

                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (e: Exception) {
                    toast("No app can open this link")
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
                lockIcon.alpha = if (url.startsWith("https://")) 1f else 0.35f
                videoFound = false
                updateNav()
                if (!urlBar.hasFocus()) {
                    urlBar.setText(if (url.startsWith("file:///android_asset")) "" else url)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                if (url.contains("/home.html")) loadNews()
                updateNav()
            }
            // SSL errors: onReceivedSslError is NOT overridden, so the default
            // behaviour (cancel the load) applies. Never auto-proceed here.
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }

        // Normal file downloads (links that the server marks as a download)
        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            download(url, mimeType, contentDisposition)
        }

        // Long-press a link or image: Open / Copy / Download
        web.setOnLongClickListener {
            val r = web.hitTestResult
            val extra = r.extra
            val t = r.type
            val linkable = t == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                t == WebView.HitTestResult.IMAGE_TYPE ||
                t == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
            if (extra != null && linkable && extra.startsWith("http")) {
                AlertDialog.Builder(this)
                    .setTitle(extra.take(60))
                    .setItems(arrayOf("Open", "Copy link", "Download")) { _, which ->
                        when (which) {
                            0 -> web.loadUrl(extra)
                            1 -> {
                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("link", extra))
                                toast("Link copied")
                            }
                            else -> download(extra, null, null)
                        }
                    }
                    .show()
                true
            } else {
                false
            }
        }

        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                go(); true
            } else false
        }
        reloadBtn.setOnClickListener { web.reload() }
        backBtn.setOnClickListener { if (web.canGoBack()) web.goBack() }
        dlBtn.setOnClickListener { downloadVideo() }

        lastKey = prefs.snapshot()
        applyAll(reload = false)

        if (prefs.dailyNews) NewsAlarm.schedule(this)
        if (!prefs.askedNotif && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.askedNotif = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }

        val incoming = intent?.data?.toString()
        when {
            savedInstanceState != null -> web.restoreState(savedInstanceState)
            incoming != null -> web.loadUrl(incoming)
            else -> web.loadUrl(assetUrl("home.html"))
        }
    }

    // ---------- theme and settings ----------

    private fun assetUrl(page: String): String {
        val p = Themes.resolve(this)
        val city = Prefs.CITIES[prefs.city.coerceIn(0, Prefs.CITIES.size - 1)]
        return "file:///android_asset/$page?theme=" + (if (p.dark) "dark" else "light") +
            "&news=" + (if (prefs.news) "1" else "0") +
            "&engine=" + Uri.encode(Prefs.SEARCH_URLS[prefs.engine]) +
            "&city=" + Uri.encode(city.first) + "&lat=" + city.second + "&lon=" + city.third
    }

    private fun applyAll(reload: Boolean) {
        applyTheme()
        adBlock = prefs.adBlock
        val ua = if (prefs.desktop) DESKTOP_UA else defaultUa
        val uaChanged = web.settings.userAgentString != ua
        web.settings.userAgentString = ua

        if (reload) {
            val u = web.url
            when {
                u != null && u.contains("/home.html") -> web.loadUrl(assetUrl("home.html"))
                u != null && u.contains("/games.html") -> web.loadUrl(assetUrl("games.html"))
                uaChanged -> web.reload()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        val p = Themes.resolve(this)
        root.setBackgroundColor(p.bg)
        topBar.setBackgroundColor(p.surface)
        bottomBar.setBackgroundColor(p.surface)
        pill.background = rounded(p.pill, 22)
        urlBar.setTextColor(p.text)
        urlBar.setHintTextColor(p.hint)
        tintedIcons.forEach { it.setColorFilter(p.icon) }
        dlBtn.setColorFilter(if (videoFound) p.accent else p.icon)
        updateNav()
        progress.progressTintList = ColorStateList.valueOf(p.accent)
        applyBars(p, p.surface)

        // Ask the web page itself to go dark (works best on pages that support it).
        if (Build.VERSION.SDK_INT >= 33) {
            web.settings.isAlgorithmicDarkeningAllowed = p.dark
        } else if (Build.VERSION.SDK_INT >= 29) {
            web.settings.forceDark =
                if (p.dark) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        }
    }

    // ---------- daily news on the home page ----------

    private fun loadNews() {
        if (!prefs.news) return
        val cached = prefs.newsCache
        if (cached != null) pushNews(cached)
        val fresh = cached != null && System.currentTimeMillis() - prefs.newsTime < 5 * 60 * 1000L
        if (fresh || fetching) return
        fetching = true
        Thread {
            val json: String? = try {
                News.fetchAll()
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                fetching = false
                if (isDestroyed) return@runOnUiThread
                if (json != null) {
                    prefs.newsCache = json
                    prefs.newsTime = System.currentTimeMillis()
                    pushNews(json)
                } else if (cached == null) {
                    web.evaluateJavascript("window.newsFailed && window.newsFailed()", null)
                }
            }
        }.start()
    }

    private fun pushNews(json: String) {
        val u = web.url ?: return
        if (!u.contains("/home.html")) return
        web.evaluateJavascript(
            "window.renderNews && window.renderNews(" + JSONObject.quote(json) + ")", null
        )
    }

    // ---------- bottom navigation (5 big labelled tabs) ----------

    private fun buildNav() {
        val bar = bottomBar as LinearLayout
        val tabs = listOf(
            Triple("Home", R.drawable.ic_home, 0),
            Triple("Media", R.drawable.ic_media, 1),
            Triple("Vault", R.drawable.ic_vault, 2),
            Triple("Games", R.drawable.ic_games, 3),
            Triple("More", R.drawable.ic_more, 4)
        )
        bar.gravity = Gravity.CENTER_VERTICAL
        for (t in tabs) {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL

            val pillBox = android.widget.FrameLayout(this)
            val icon = ImageView(this)
            icon.setImageResource(t.second)
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            pillBox.addView(icon, android.widget.FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            navIcons.add(icon)
            navPills.add(pillBox)
            col.addView(pillBox, LinearLayout.LayoutParams(dp(64), dp(34)))

            val label = TextView(this)
            label.text = t.first
            label.textSize = 12f
            label.gravity = Gravity.CENTER
            label.setPadding(0, dp(2), 0, 0)
            navLabels.add(label)
            col.addView(label, LinearLayout.LayoutParams(WRAP, WRAP))

            col.setOnClickListener { onNav(t.third) }
            bar.addView(col, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
    }

    private fun onNav(i: Int) {
        when (i) {
            0 -> web.loadUrl(assetUrl("home.html"))
            1 -> startActivity(Intent(this, MediaActivity::class.java))
            2 -> startActivity(Intent(this, VaultActivity::class.java))
            3 -> web.loadUrl(assetUrl("games.html"))
            else -> showMore()
        }
    }

    private fun updateNav() {
        if (navIcons.isEmpty()) return
        val p = Themes.resolve(this)
        val u = web.url ?: ""
        val active = when {
            u.contains("/home.html") -> 0
            u.contains("/games.html") || u.contains("/snake.html") || u.contains("/memory.html") -> 3
            else -> -1
        }
        for (i in navIcons.indices) {
            val on = i == active
            navIcons[i].setColorFilter(if (on) p.accent else p.icon)
            navLabels[i].setTextColor(if (on) p.accent else p.hint)
            navLabels[i].setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            navPills[i].background = if (on) rounded((p.accent and 0x00FFFFFF) or 0x33000000, 17) else null
        }
        dlBtn.setColorFilter(if (videoFound) p.accent else p.icon)
    }

    private fun showMore() {
        val items = arrayOf(
            "Forward",
            "Downloads",
            if (prefs.desktop) "Desktop site: on (tap to turn off)" else "Desktop site: off (tap to turn on)",
            "Settings",
            "About Vyro"
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> if (web.canGoForward()) web.goForward()
                    1 -> try {
                        startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                    } catch (e: Exception) {
                        toast("Can't open Downloads")
                    }
                    2 -> {
                        prefs.desktop = !prefs.desktop
                        lastKey = prefs.snapshot()
                        applyAll(reload = true)
                    }
                    3 -> startActivity(Intent(this, SettingsActivity::class.java))
                    else -> toast("Vyro 0.5 · Made by Yoolan")
                }
            }
            .show()
    }

    // Phoenix-style smart detection: highlight the download button when the page has a real video file.
    private val videoCheck = object : Runnable {
        override fun run() {
            val u = web.url ?: ""
            val host = Uri.parse(u).host ?: ""
            val yt = host.endsWith("youtube.com") || host == "youtu.be"
            if (u.startsWith("http") && !yt) {
                web.evaluateJavascript(FIND_VIDEO_JS) { raw ->
                    val s = (raw ?: "").trim('"')
                    val found = s.startsWith("http") && !s.contains(".m3u8") && !s.contains(".mpd")
                    if (found != videoFound) {
                        videoFound = found
                        updateNav()
                    }
                }
            } else if (videoFound) {
                videoFound = false
                updateNav()
            }
            handler.postDelayed(this, 3000)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.toString()?.let { web.loadUrl(it) }
    }

    // ---------- navigation ----------

    private fun isBlocked(host: String): Boolean =
        blockedHosts.any { host == it || host.endsWith(".$it") }

    private fun go() {
        val text = urlBar.text.toString().trim()
        if (text.isEmpty()) return
        web.loadUrl(toUrl(text))
        urlBar.clearFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun toUrl(input: String): String {
        val looksLikeUrl = !input.contains(" ") && (input.contains(".") || input.contains("://"))
        return when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            looksLikeUrl -> "https://$input"
            else -> Prefs.SEARCH_URLS[prefs.engine] + Uri.encode(input)
        }
    }

    // ---------- downloads ----------

    private fun downloadVideo() {
        val page = web.url ?: ""
        val host = Uri.parse(page).host ?: ""
        if (host.endsWith("youtube.com") || host == "youtu.be" || host.endsWith("youtube-nocookie.com")) {
            toast("YouTube doesn't allow saving videos here. Use YouTube Premium's offline feature.")
            return
        }
        web.evaluateJavascript(FIND_VIDEO_JS) { raw ->
            val src: String = try {
                (JSONTokener(raw ?: "null").nextValue() as? String) ?: ""
            } catch (e: Exception) {
                ""
            }
            when {
                src.isEmpty() -> toast("No downloadable video found on this page")
                src.startsWith("blob:") || src.contains(".m3u8") || src.contains(".mpd") ->
                    toast("This site streams video in a protected way, so it can't be saved")
                src.startsWith("http") -> download(src, "video/mp4", null)
                else -> toast("No downloadable video found on this page")
            }
        }
    }

    private fun download(url: String, mimeHint: String?, disposition: String?) {
        val action: () -> Unit = {
            try {
                val name = URLUtil.guessFileName(url, disposition, mimeHint)
                val req = DownloadManager.Request(Uri.parse(url))
                req.addRequestHeader("User-Agent", web.settings.userAgentString)
                CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
                web.url?.let { if (it.startsWith("http")) req.addRequestHeader("Referer", it) }
                if (mimeHint != null) req.setMimeType(mimeHint)
                req.setTitle(name)
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                toast("Downloading $name")
            } catch (e: Exception) {
                toast("Download failed")
            }
        }
        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = action
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
        } else {
            action()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) return
        if (requestCode == REQ_STORAGE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                pendingDownload?.invoke()
            } else {
                toast("Storage permission is needed to save files")
            }
            pendingDownload = null
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---------- lifecycle ----------

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
        handler.removeCallbacks(videoCheck)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        handler.removeCallbacks(videoCheck)
        handler.postDelayed(videoCheck, 2000)
        val key = prefs.snapshot()
        if (key != lastKey) {
            lastKey = key
            applyAll(reload = true)
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val REQ_STORAGE = 77
        private const val REQ_NOTIF = 78

        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"

        // Finds the first <video> on the page that points at a real file.
        private const val FIND_VIDEO_JS =
            "(function(){var vs=document.querySelectorAll('video');" +
                "for(var i=0;i<vs.length;i++){var v=vs[i];var s=v.currentSrc||v.src;" +
                "if(!s){var so=v.querySelector('source');if(so){s=so.src;}}" +
                "if(s){return s;}}return '';})()"
    }
}
