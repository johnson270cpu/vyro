package com.yoolan.vyro

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The vault hides files and folders by moving them into Vyro's private storage,
 * where gallery and file apps can't see them. It is protected by a PIN or pattern.
 */
class VaultActivity : Activity() {

    private lateinit var p: Palette
    private lateinit var store: LockStore
    private lateinit var vaultRoot: File
    private lateinit var currentDir: File
    private lateinit var content: LinearLayout
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var pathView: TextView
    private val adapter = VaultAdapter()
    private var entries: List<File> = emptyList()

    private var unlocked = false
    private var lockShowing = false
    private var skipRelock = false
    private var needRelock = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        p = Themes.resolve(this)
        applyBars(p, p.bg)
        store = LockStore(this)
        vaultRoot = File(filesDir, "vault")
        vaultRoot.mkdirs()
        currentDir = vaultRoot
        buildUi()
        requestUnlock()
    }

    // ---------- locking ----------

    private fun requestUnlock() {
        lockShowing = true
        val i = Intent(this, LockActivity::class.java)
        i.putExtra("mode", if (store.isSet) "unlock" else "setup")
        startActivityForResult(i, REQ_LOCK)
    }

    override fun onStart() {
        super.onStart()
        if (skipRelock) {
            skipRelock = false
        } else if (needRelock && !lockShowing) {
            needRelock = false
            requestUnlock()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!skipRelock && !lockShowing && !isFinishing) {
            unlocked = false
            needRelock = true
            content.visibility = View.INVISIBLE
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_LOCK -> {
                lockShowing = false
                if (resultCode == RESULT_OK) {
                    unlocked = true
                    content.visibility = View.VISIBLE
                    refresh()
                } else {
                    finish()
                }
            }
            REQ_CHANGE -> lockShowing = false
            REQ_FILES -> if (resultCode == RESULT_OK && data != null) importFiles(data)
            REQ_FOLDER -> {
                val tree = data?.data
                if (resultCode == RESULT_OK && tree != null) importFolder(tree)
            }
        }
    }

    // ---------- UI ----------

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(p.bg)
        root.addView(buildHeader(p, "Vault"), LinearLayout.LayoutParams(MATCH, WRAP))

        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.visibility = View.INVISIBLE

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.setPadding(dp(12), dp(12), dp(12), dp(4))
        actions.addView(action("Hide files") { pickFiles() }, actionParams())
        actions.addView(action("Hide folder") { pickFolder() }, actionParams())
        actions.addView(action("Change lock") { changeLock() }, actionParams())
        content.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP))

        val note = TextView(this)
        note.text = "Hidden items live privately inside Vyro and are locked with your PIN or pattern. " +
            "They are not encrypted. Uninstalling Vyro or clearing its data deletes them, so restore anything important first."
        note.textSize = 12f
        note.setTextColor(p.hint)
        note.setPadding(dp(16), dp(4), dp(16), dp(4))
        content.addView(note, LinearLayout.LayoutParams(MATCH, WRAP))

        pathView = TextView(this)
        pathView.textSize = 13f
        pathView.setTextColor(p.accent)
        pathView.setPadding(dp(16), dp(8), dp(16), dp(8))
        content.addView(pathView, LinearLayout.LayoutParams(MATCH, WRAP))

        listView = ListView(this)
        listView.divider = null
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ ->
            val f = entries[pos]
            if (f.isDirectory) {
                currentDir = f
                refresh()
            } else {
                openFile(f)
            }
        }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            showActions(entries[pos])
            true
        }
        content.addView(listView, LinearLayout.LayoutParams(MATCH, 0, 1f))

        emptyView = TextView(this)
        emptyView.text = "Nothing hidden here yet.\nTap \"Hide files\" or \"Hide folder\" to add some."
        emptyView.textSize = 15f
        emptyView.setTextColor(p.hint)
        emptyView.gravity = Gravity.CENTER
        emptyView.setPadding(dp(24), dp(32), dp(24), dp(32))
        content.addView(emptyView, LinearLayout.LayoutParams(MATCH, WRAP))

        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(root)
    }

    private fun action(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 13f
        b.isAllCaps = false
        b.setTextColor(p.onAccent)
        b.background = rounded(p.accent, 14)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun actionParams(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, dp(44), 1f)
        lp.setMargins(dp(4), 0, dp(4), 0)
        return lp
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun refresh() {
        val kids = currentDir.listFiles()
        entries = if (kids == null) {
            emptyList()
        } else {
            kids.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
        }
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        val rel = currentDir.relativeTo(vaultRoot).path
        pathView.text = if (rel.isEmpty()) "Vault" else "Vault / " + rel.replace("/", " / ")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentDir != vaultRoot) {
            currentDir = currentDir.parentFile ?: vaultRoot
            refresh()
        } else {
            super.onBackPressed()
        }
    }

    private fun mimeOf(f: File): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())

    private fun kindOf(f: File): Int {
        val m = mimeOf(f) ?: return -1
        return when {
            m.startsWith("audio/") -> 0
            m.startsWith("video/") -> 1
            m.startsWith("image/") -> 2
            else -> -1
        }
    }

    private inner class VaultAdapter : BaseAdapter() {
        override fun getCount(): Int = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val f = entries[position]
            val row = LinearLayout(this@VaultActivity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(16), dp(10), dp(16), dp(10))

            val icon = ImageView(this@VaultActivity)
            val res = if (f.isDirectory) R.drawable.ic_folder else when (kindOf(f)) {
                0 -> R.drawable.ic_music
                1 -> R.drawable.ic_video
                2 -> R.drawable.ic_image
                else -> R.drawable.ic_file
            }
            icon.setImageResource(res)
            icon.setColorFilter(if (f.isDirectory) p.accent else p.icon)
            icon.setPadding(dp(10), dp(10), dp(10), dp(10))
            icon.background = rounded(p.pill, 12)
            row.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))

            val col = LinearLayout(this@VaultActivity)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(14), 0, 0, 0)
            val t = TextView(this@VaultActivity)
            t.text = f.name
            t.textSize = 16f
            t.setTextColor(p.text)
            t.maxLines = 1
            t.ellipsize = TextUtils.TruncateAt.END
            val s = TextView(this@VaultActivity)
            s.textSize = 13f
            s.setTextColor(p.hint)
            s.text = if (f.isDirectory) {
                (f.listFiles()?.size ?: 0).toString() + " items"
            } else {
                Formatter.formatShortFileSize(this@VaultActivity, f.length())
            }
            col.addView(t)
            col.addView(s)
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            return row
        }
    }

    // ---------- opening ----------

    private fun openFile(f: File) {
        val kind = kindOf(f)
        if (kind < 0) {
            toast("Vyro can't open this file type. Restore it to open it with another app.")
            return
        }
        val sibs = entries.filter { !it.isDirectory && kindOf(it) == kind }
        skipRelock = true
        openMedia(
            kind,
            sibs.map { Uri.fromFile(it).toString() },
            sibs.map { it.nameWithoutExtension },
            sibs.indexOf(f),
            true
        )
    }

    // ---------- hiding ----------

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        skipRelock = true
        startActivityForResult(i, REQ_FILES)
    }

    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        skipRelock = true
        startActivityForResult(i, REQ_FOLDER)
    }

    private fun changeLock() {
        lockShowing = true
        val i = Intent(this, LockActivity::class.java)
        i.putExtra("mode", "setup")
        startActivityForResult(i, REQ_CHANGE)
    }

    private fun safeName(n: String): String = n.replace("/", "_").ifEmpty { "file" }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, safeName(name))
        if (!f.exists()) return f
        val base = f.nameWithoutExtension
        val ext = if (f.extension.isEmpty()) "" else "." + f.extension
        var n = 1
        while (f.exists()) {
            f = File(dir, base + " (" + n + ")" + ext)
            n++
        }
        return f
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun deleteDoc(u: Uri): Boolean = try {
        DocumentsContract.deleteDocument(contentResolver, u)
    } catch (e: Exception) {
        false
    }

    private fun copyIn(from: Uri, to: File) {
        val input = contentResolver.openInputStream(from) ?: throw IOException("Can't open file")
        input.use { i ->
            FileOutputStream(to).use { o -> i.copyTo(o) }
        }
    }

    private fun importFiles(data: Intent) {
        val picked = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (k in 0 until clip.itemCount) picked.add(clip.getItemAt(k).uri)
        } else {
            val single = data.data
            if (single != null) picked.add(single)
        }
        if (picked.isEmpty()) return
        toast("Hiding " + picked.size + " file(s)...")
        val target = currentDir
        Thread {
            var hidden = 0
            var originalsLeft = 0
            for (u in picked) {
                val dest = uniqueFile(target, queryName(u) ?: ("file_" + System.currentTimeMillis()))
                try {
                    copyIn(u, dest)
                    hidden++
                    if (!deleteDoc(u)) originalsLeft++
                } catch (e: Exception) {
                    dest.delete()
                }
            }
            runOnUiThread {
                refresh()
                var msg = "Hidden $hidden of ${picked.size} file(s)."
                if (originalsLeft > 0) {
                    msg += " $originalsLeft original(s) couldn't be removed, so delete them in your Files or Gallery app."
                }
                toast(msg)
            }
        }.start()
    }

    private fun importFolder(tree: Uri) {
        toast("Hiding folder...")
        val target = currentDir
        Thread {
            // counts: [0] copied, [1] failed, [2] originals removed
            var counts = intArrayOf(0, 0, 0)
            var rootRemoved = false
            try {
                val rootId = DocumentsContract.getTreeDocumentId(tree)
                val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
                val dest = uniqueFile(target, queryName(rootUri) ?: "Folder")
                dest.mkdirs()
                counts = copyDir(tree, rootId, dest)
                if (counts[1] == 0) rootRemoved = deleteDoc(rootUri)
            } catch (e: Exception) {
                counts[1] = counts[1] + 1
            }
            val result = counts
            val removedRoot = rootRemoved
            runOnUiThread {
                refresh()
                var msg = "Hidden " + result[0] + " file(s)."
                if (result[1] > 0) msg += " " + result[1] + " failed."
                if (!removedRoot) {
                    msg += " Check the original folder: if anything is left, delete it yourself."
                }
                toast(msg)
            }
        }.start()
    }

    /** Copies a folder from the system picker into the vault. Returns [copied, failed, originalsRemoved]. */
    private fun copyDir(tree: Uri, parentId: String, dest: File): IntArray {
        val res = intArrayOf(0, 0, 0)
        val kids = ArrayList<Array<String>>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        contentResolver.query(childrenUri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                kids.add(arrayOf(c.getString(0), c.getString(1) ?: "file", c.getString(2) ?: ""))
            }
        }
        for (k in kids) {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, k[0])
            if (k[2] == DocumentsContract.Document.MIME_TYPE_DIR) {
                val sub = uniqueFile(dest, k[1])
                sub.mkdirs()
                val r = copyDir(tree, k[0], sub)
                res[0] += r[0]
                res[1] += r[1]
                res[2] += r[2]
                if (r[1] == 0 && deleteDoc(docUri)) res[2] += 1
            } else {
                val f = uniqueFile(dest, k[1])
                try {
                    copyIn(docUri, f)
                    res[0] += 1
                    if (deleteDoc(docUri)) res[2] += 1
                } catch (e: Exception) {
                    f.delete()
                    res[1] += 1
                }
            }
        }
        return res
    }

    // ---------- restore and delete ----------

    private fun showActions(f: File) {
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(arrayOf("Restore to Downloads/Vyro Restored", "Delete forever")) { _, which ->
                if (which == 0) restore(f) else confirmDelete(f)
            }
            .show()
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete forever?")
            .setMessage("\"" + f.name + "\" will be permanently deleted. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                f.deleteRecursively()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun restore(f: File) {
        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            skipRelock = true
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            toast("Allow storage access, then try again.")
            return
        }
        toast("Restoring...")
        Thread {
            val total = countFiles(f)
            val done = restoreAny(f, "Vyro Restored")
            if (done == total) f.deleteRecursively()
            runOnUiThread {
                refresh()
                toast(
                    if (done == total) "Restored to Downloads/Vyro Restored"
                    else "Restored $done of $total file(s)."
                )
            }
        }.start()
    }

    private fun countFiles(f: File): Int {
        if (!f.isDirectory) return 1
        var n = 0
        f.listFiles()?.forEach { n += countFiles(it) }
        return n
    }

    private fun restoreAny(f: File, relDir: String): Int {
        if (f.isDirectory) {
            var n = 0
            f.listFiles()?.forEach { n += restoreAny(it, relDir + "/" + f.name) }
            return n
        }
        return if (restoreFile(f, relDir)) 1 else 0
    }

    private fun restoreFile(f: File, relDir: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues()
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                v.put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(f) ?: "application/octet-stream")
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + relDir)
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                if (uri == null) {
                    false
                } else {
                    val out = contentResolver.openOutputStream(uri)
                    if (out == null) {
                        false
                    } else {
                        out.use { o -> f.inputStream().use { i -> i.copyTo(o) } }
                        true
                    }
                }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), relDir)
                dir.mkdirs()
                f.copyTo(File(dir, f.name), false)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val REQ_LOCK = 1
        private const val REQ_CHANGE = 2
        private const val REQ_FILES = 3
        private const val REQ_FOLDER = 4
        private const val REQ_STORAGE = 5
    }
}
