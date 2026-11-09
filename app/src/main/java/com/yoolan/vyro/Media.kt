package com.yoolan.vyro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.Executors
import kotlin.math.max

/** kind: 0 = audio, 1 = video, 2 = image */
class MediaItem(val uri: Uri, val title: String, val subtitle: String, val kind: Int)

object ImageLoader {
    /** Decodes an image so that its longest side is about maxPx, and fixes its rotation. */
    fun decode(ctx: Context, uri: Uri, maxPx: Int): Bitmap? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxPx) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        var degrees = 0
        try {
            cr.openInputStream(uri)?.use { s ->
                val o = ExifInterface(s).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
                degrees = when (o) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (e: Exception) {
            degrees = 0
        }
        if (degrees == 0) return bmp
        val m = Matrix()
        m.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}

/** Loads thumbnails in the background and puts them into ImageViews. */
object Thumbs {
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Bitmap>(80)

    fun load(ctx: Context, item: MediaItem, sizePx: Int, view: ImageView, placeholder: Int, tint: Int) {
        val key = item.uri.toString()
        view.tag = key
        val hit = cache.get(key)
        if (hit != null) {
            showBitmap(view, hit)
            return
        }
        showPlaceholder(view, placeholder, tint)
        val app = ctx.applicationContext
        pool.execute {
            val bmp: Bitmap? = try {
                if (Build.VERSION.SDK_INT >= 29) {
                    app.contentResolver.loadThumbnail(item.uri, Size(sizePx, sizePx), null)
                } else if (item.kind == 2) {
                    ImageLoader.decode(app, item.uri, sizePx)
                } else {
                    null
                }
            } catch (e: Throwable) {
                null
            }
            if (bmp != null) {
                cache.put(key, bmp)
                main.post { if (view.tag == key) showBitmap(view, bmp) }
            }
        }
    }

    private fun showBitmap(v: ImageView, b: Bitmap) {
        v.clearColorFilter()
        v.setPadding(0, 0, 0, 0)
        v.scaleType = ImageView.ScaleType.CENTER_CROP
        v.setImageBitmap(b)
    }

    private fun showPlaceholder(v: ImageView, res: Int, tint: Int) {
        val pad = (v.resources.displayMetrics.density * 14).toInt()
        v.setPadding(pad, pad, pad, pad)
        v.scaleType = ImageView.ScaleType.FIT_CENTER
        v.setColorFilter(tint)
        v.setImageResource(res)
    }
}

/**
 * Opens the right Vyro player for a list of media.
 * kind: 0 = music, 1 = video, 2 = picture.
 */
fun Context.openMedia(
    kind: Int,
    uris: List<String>,
    titles: List<String>,
    index: Int,
    secure: Boolean,
    shuffle: Boolean = false
) {
    val target = when (kind) {
        0 -> MusicActivity::class.java
        1 -> VideoActivity::class.java
        else -> ImageActivity::class.java
    }
    val i = android.content.Intent(this, target)
    i.putStringArrayListExtra("uris", ArrayList(uris))
    i.putStringArrayListExtra("titles", ArrayList(titles))
    i.putExtra("index", index)
    i.putExtra("secure", secure)
    i.putExtra("shuffle", shuffle)
    startActivity(i)
}
