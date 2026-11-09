package com.yoolan.vyro

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class MediaActivity : Activity() {

    private lateinit var p: Palette
    private lateinit var container: FrameLayout
    private lateinit var mini: LinearLayout
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ImageButton
    private val tabs = ArrayList<TextView>()
    private var current = 0
    private val handler = Handler(Looper.getMainLooper())

    private class Row(val thumb: ImageView, val title: TextView, val sub: TextView)

    private val miniTicker = object : Runnable {
        override fun run() {
            updateMini()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Themes.resolve(this)
        applyBars(p, p.bg)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(p.bg)
        root.addView(buildHeader(p, "Media"), LinearLayout.LayoutParams(MATCH, WRAP))

        // segmented tabs
        val tabHolder = LinearLayout(this)
        tabHolder.orientation = LinearLayout.HORIZONTAL
        tabHolder.background = rounded(p.pill, 24)
        tabHolder.setPadding(dp(4), dp(4), dp(4), dp(4))
        val names = listOf("Music", "Videos", "Pictures")
        names.forEachIndexed { i, name ->
            val t = TextView(this)
            t.text = name
            t.gravity = Gravity.CENTER
            t.textSize = 15f
            t.setPadding(0, dp(10), 0, dp(10))
            t.setOnClickListener { show(i) }
            tabs.add(t)
            tabHolder.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        val tlp = LinearLayout.LayoutParams(MATCH, WRAP)
        tlp.setMargins(dp(16), dp(12), dp(16), dp(8))
        root.addView(tabHolder, tlp)

        container = FrameLayout(this)
        root.addView(container, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // mini player (shows while music is playing in the background)
        mini = LinearLayout(this)
        mini.orientation = LinearLayout.HORIZONTAL
        mini.gravity = Gravity.CENTER_VERTICAL
        mini.setBackgroundColor(p.surface)
        mini.setPadding(dp(16), dp(10), dp(8), dp(10))
        mini.visibility = View.GONE
        val miniIcon = ImageView(this)
        miniIcon.setImageResource(R.drawable.ic_music)
        miniIcon.setColorFilter(p.accent)
        miniIcon.setPadding(dp(8), dp(8), dp(8), dp(8))
        miniIcon.background = rounded(p.pill, 12)
        mini.addView(miniIcon, LinearLayout.LayoutParams(dp(44), dp(44)))
        val miniCol = LinearLayout(this)
        miniCol.orientation = LinearLayout.VERTICAL
        miniCol.setPadding(dp(12), 0, dp(8), 0)
        miniTitle = TextView(this)
        miniTitle.textSize = 15f
        miniTitle.setTextColor(p.text)
        miniTitle.setTypeface(miniTitle.typeface, Typeface.BOLD)
        miniTitle.maxLines = 1
        miniTitle.ellipsize = TextUtils.TruncateAt.END
        miniSub = TextView(this)
        miniSub.textSize = 12f
        miniSub.setTextColor(p.hint)
        miniSub.maxLines = 1
        miniCol.addView(miniTitle)
        miniCol.addView(miniSub)
        mini.addView(miniCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        miniPlay = ImageButton(this)
        miniPlay.setImageResource(R.drawable.ic_pause)
        miniPlay.setColorFilter(p.text)
        miniPlay.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        miniPlay.setPadding(dp(10), dp(10), dp(10), dp(10))
        miniPlay.setOnClickListener {
            MusicService.instance?.toggle()
            updateMini()
        }
        mini.addView(miniPlay, LinearLayout.LayoutParams(dp(48), dp(48)))
        mini.setOnClickListener {
            val i = Intent(this, MusicActivity::class.java)
            i.putExtra("resume", true)
            startActivity(i)
        }
        root.addView(mini, LinearLayout.LayoutParams(MATCH, WRAP))

        setContentView(root)
        show(0)
    }

    override fun onResume() {
        super.onResume()
        handler.post(miniTicker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(miniTicker)
    }

    private fun updateMini() {
        val s = MusicService.instance
        if (s == null || s.currentTrack == null) {
            mini.visibility = View.GONE
            return
        }
        mini.visibility = View.VISIBLE
        miniTitle.text = if (s.title.isEmpty()) "Loading..." else s.title
        miniSub.text = if (s.artist.isEmpty()) "Vyro Music" else s.artist
        miniPlay.setImageResource(if (s.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    // ---------- permission ----------

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun hasAccess(): Boolean =
        neededPermissions().any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        show(current)
    }

    // ---------- screens ----------

    private fun message(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 16f
        t.setTextColor(p.hint)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(24), dp(24), dp(24), dp(24))
        t.layoutParams = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER)
        return t
    }

    private fun gate(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(28), dp(28), dp(28), dp(28))

        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_media)
        icon.setColorFilter(p.accent)
        col.addView(icon, LinearLayout.LayoutParams(dp(56), dp(56)))

        val title = TextView(this)
        title.text = "Let Vyro see your media"
        title.textSize = 20f
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.setTextColor(p.text)
        title.gravity = Gravity.CENTER
        title.setPadding(0, dp(16), 0, dp(8))
        col.addView(title)

        val body = TextView(this)
        body.text = "To play your music and videos and show your pictures, Vyro needs permission " +
            "to read them. Nothing is uploaded. Your files stay on your phone."
        body.textSize = 15f
        body.setTextColor(p.hint)
        body.gravity = Gravity.CENTER
        col.addView(body)

        val b = Button(this)
        b.text = "Allow access"
        b.isAllCaps = false
        b.setTextColor(p.onAccent)
        b.background = rounded(p.accent, 14)
        b.setOnClickListener { requestPermissions(neededPermissions(), 10) }
        val lp = LinearLayout.LayoutParams(MATCH, dp(48))
        lp.topMargin = dp(20)
        col.addView(b, lp)

        col.layoutParams = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER)
        return col
    }

    private fun show(i: Int) {
        current = i
        tabs.forEachIndexed { idx, t ->
            val on = idx == i
            t.setTextColor(if (on) p.onAccent else p.hint)
            t.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            t.background = if (on) rounded(p.accent, 20) else null
        }
        container.removeAllViews()
        if (!hasAccess()) {
            container.addView(gate())
            return
        }
        container.addView(message("Loading..."))
        Thread {
            val items = query(i)
            runOnUiThread {
                if (current == i && !isFinishing) render(i, items)
            }
        }.start()
    }

    private fun render(kind: Int, items: List<MediaItem>) {
        container.removeAllViews()
        if (items.isEmpty()) {
            val msg = when (kind) {
                0 -> "No music found on this phone"
                1 -> "No videos found on this phone"
                else -> "No pictures found on this phone"
            }
            container.addView(message(msg))
            return
        }
        val adapter = MediaAdapter(items, kind)
        val view: View
        if (kind == 2) {
            val g = GridView(this)
            g.numColumns = 3
            g.horizontalSpacing = dp(2)
            g.verticalSpacing = dp(2)
            g.adapter = adapter
            g.setOnItemClickListener { _, _, pos, _ -> openItem(items, pos, false) }
            view = g
        } else {
            val l = ListView(this)
            l.divider = null
            l.adapter = adapter
            l.setOnItemClickListener { _, _, pos, _ -> openItem(items, pos, false) }
            if (kind == 0) {
                val holder = LinearLayout(this)
                holder.orientation = LinearLayout.VERTICAL
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.setPadding(dp(16), dp(4), dp(16), dp(8))
                row.addView(playAllButton("Shuffle play", true) { openItem(items, 0, true) },
                    LinearLayout.LayoutParams(0, dp(46), 1f))
                val gap = View(this)
                row.addView(gap, LinearLayout.LayoutParams(dp(10), dp(1)))
                row.addView(playAllButton("Play all", false) { openItem(items, 0, false) },
                    LinearLayout.LayoutParams(0, dp(46), 1f))
                holder.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
                holder.addView(l, LinearLayout.LayoutParams(MATCH, 0, 1f))
                view = holder
            } else {
                view = l
            }
        }
        container.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun playAllButton(label: String, filled: Boolean, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 14f
        b.setTextColor(if (filled) p.onAccent else p.text)
        b.background = rounded(if (filled) p.accent else p.pill, 23)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun openItem(items: List<MediaItem>, pos: Int, shuffle: Boolean) {
        openMedia(
            items[pos].kind,
            items.map { it.uri.toString() },
            items.map { it.title },
            pos,
            false,
            shuffle
        )
    }

    // ---------- reading the media library ----------

    private fun query(kind: Int): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val base: Uri
        val sel: String?
        val durCol: String?
        when (kind) {
            0 -> {
                base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                sel = MediaStore.Audio.Media.IS_MUSIC + " != 0"
                durCol = MediaStore.Audio.Media.DURATION
            }
            1 -> {
                base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                sel = null
                durCol = MediaStore.Video.Media.DURATION
            }
            else -> {
                base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                sel = null
                durCol = null
            }
        }
        val proj: Array<String> = if (durCol != null) {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                durCol
            )
        } else {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE
            )
        }
        try {
            contentResolver.query(
                base, proj, sel, null, MediaStore.MediaColumns.DATE_ADDED + " DESC"
            )?.use { c ->
                val idC = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameC = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeC = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val durC = if (durCol != null) c.getColumnIndex(durCol) else -1
                while (c.moveToNext() && out.size < 500) {
                    val id = c.getLong(idC)
                    val name = c.getString(nameC) ?: "Untitled"
                    val size = Formatter.formatShortFileSize(this, c.getLong(sizeC))
                    val sub = if (durC >= 0) fmtTime(c.getInt(durC)) + "  ·  " + size else size
                    out.add(
                        MediaItem(
                            ContentUris.withAppendedId(base, id),
                            name.substringBeforeLast('.', name),
                            sub,
                            kind
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // No permission or a broken media database: show an empty list.
        }
        return out
    }

    // ---------- list adapter ----------

    private inner class MediaAdapter(val items: List<MediaItem>, val kind: Int) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[position]
            return if (kind == 2) gridCell(item, convertView) else listRow(item, convertView)
        }

        private fun gridCell(item: MediaItem, cv: View?): View {
            val iv = (cv as? ImageView) ?: ImageView(this@MediaActivity)
            val size = (resources.displayMetrics.widthPixels - dp(4)) / 3
            iv.layoutParams = android.widget.AbsListView.LayoutParams(size, size)
            iv.setBackgroundColor(p.pill)
            Thumbs.load(this@MediaActivity, item, size, iv, R.drawable.ic_image, p.hint)
            return iv
        }

        private fun newRow(): View {
            val r = LinearLayout(this@MediaActivity)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(16), dp(8), dp(16), dp(8))

            val thumb = ImageView(this@MediaActivity)
            thumb.background = rounded(p.pill, 12)
            thumb.clipToOutline = true
            r.addView(thumb, LinearLayout.LayoutParams(dp(56), dp(56)))

            val col = LinearLayout(this@MediaActivity)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(14), 0, 0, 0)
            val t = TextView(this@MediaActivity)
            t.textSize = 16f
            t.setTextColor(p.text)
            t.maxLines = 1
            t.ellipsize = TextUtils.TruncateAt.END
            val s = TextView(this@MediaActivity)
            s.textSize = 13f
            s.setTextColor(p.hint)
            s.maxLines = 1
            col.addView(t)
            col.addView(s)
            r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            r.tag = Row(thumb, t, s)
            return r
        }

        private fun listRow(item: MediaItem, cv: View?): View {
            val v = cv ?: newRow()
            val h = v.tag as Row
            h.title.text = item.title
            h.sub.text = item.subtitle
            val ph = if (item.kind == 0) R.drawable.ic_music else R.drawable.ic_video
            Thumbs.load(this@MediaActivity, item, dp(112), h.thumb, ph, p.hint)
            return v
        }
    }
}
