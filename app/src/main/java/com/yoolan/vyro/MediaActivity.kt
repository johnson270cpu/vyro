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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

class MediaActivity : Activity() {

    private lateinit var p: Palette
    private lateinit var container: FrameLayout
    private val tabs = ArrayList<TextView>()
    private var current = 0

    private class Row(val thumb: ImageView, val title: TextView, val sub: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Themes.resolve(this)
        applyBars(p, p.bg)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(p.bg)
        root.addView(buildHeader(p, "Media"), LinearLayout.LayoutParams(MATCH, WRAP))

        val tabRow = LinearLayout(this)
        tabRow.orientation = LinearLayout.HORIZONTAL
        tabRow.setBackgroundColor(p.surface)
        val names = listOf("Music", "Videos", "Pictures")
        names.forEachIndexed { i, name ->
            val t = TextView(this)
            t.text = name
            t.gravity = Gravity.CENTER
            t.textSize = 15f
            t.setPadding(0, dp(12), 0, dp(12))
            t.setOnClickListener { show(i) }
            tabs.add(t)
            tabRow.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        root.addView(tabRow, LinearLayout.LayoutParams(MATCH, WRAP))

        container = FrameLayout(this)
        root.addView(container, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
        show(0)
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
            t.setTextColor(if (idx == i) p.accent else p.hint)
            t.setTypeface(null, if (idx == i) Typeface.BOLD else Typeface.NORMAL)
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
            g.setOnItemClickListener { _, _, pos, _ -> openPlayer(items, pos) }
            view = g
        } else {
            val l = ListView(this)
            l.divider = null
            l.adapter = adapter
            l.setOnItemClickListener { _, _, pos, _ -> openPlayer(items, pos) }
            view = l
        }
        container.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun openPlayer(items: List<MediaItem>, pos: Int) {
        val i = Intent(this, PlayerActivity::class.java)
        i.putExtra("kind", items[pos].kind)
        i.putStringArrayListExtra("uris", ArrayList(items.map { it.uri.toString() }))
        i.putStringArrayListExtra("titles", ArrayList(items.map { it.title }))
        i.putExtra("index", pos)
        startActivity(i)
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
                    val sub = if (durC >= 0) fmtDuration(c.getLong(durC)) + "  ·  " + size else size
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

    private fun fmtDuration(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
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
