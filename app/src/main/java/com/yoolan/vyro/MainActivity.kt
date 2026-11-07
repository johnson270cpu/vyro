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
import android.view.View
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
    private lateinit var vaultBtn: ImageButton
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
        vaultBtn = findViewById(R.id.vaultBtn)

        val reloadBtn = findViewById<ImageButton>(R.id.reloadBtn)
        val backBtn = findViewById<ImageButton>(R.id.backBtn)
        val fwdBtn = findViewById<ImageButton>(R.id.fwdBtn)
        val homeBtn = findViewById<ImageButton>(R.id.homeBtn)
        val mediaBtn = findViewById<ImageButton>(R.id.mediaBtn)
        val dlBtn = findViewById<ImageButton>(R.id.dlBtn)
        val settingsBtn = findViewById<ImageButton>(R.id.settingsBtn)
        tintedIcons = listOf(lockIcon, reloadBtn, backBtn, fwdBtn, homeBtn, mediaBtn, dlBtn, settingsBtn)

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
                if (!urlBar.hasFocus()) {
                    urlBar.setText(if (url.startsWith("file:///android_asset")) "" else url)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
                if (url.contains("/home.html")) loadNews()
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
        fwdBtn.setOnClickListener { if (web.canGoForward()) web.goForward() }
        homeBtn.setOnClickListener { web.loadUrl(assetUrl("home.html")) }
        mediaBtn.setOnClickListener { startActivity(Intent(this, MediaActivity::class.java)) }
        vaultBtn.setOnClickListener { startActivity(Intent(this, VaultActivity::class.java)) }
        dlBtn.setOnClickListener { downloadVideo() }
        settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        lastKey = prefs.snapshot()
        applyAll(reload = false)

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
        return "file:///android_asset/$page?theme=" + (if (p.dark) "dark" else "light") +
            "&news=" + (if (prefs.news) "1" else "0") +
            "&engine=" + Uri.encode(Prefs.SEARCH_URLS[prefs.engine])
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
        vaultBtn.setColorFilter(p.accent)
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
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
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
