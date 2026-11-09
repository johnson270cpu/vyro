package com.yoolan.vyro

import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Downloads public news feeds (RSS) in parallel and turns them into one JSON string for the home page. */
object News {

    private val FEEDS = arrayOf(
        Triple("top", "BBC News", "https://feeds.bbci.co.uk/news/rss.xml"),
        Triple("africa", "BBC Africa", "https://feeds.bbci.co.uk/news/world/africa/rss.xml"),
        Triple("swahili", "BBC Swahili", "https://feeds.bbci.co.uk/swahili/rss.xml"),
        Triple("business", "BBC Business", "https://feeds.bbci.co.uk/news/business/rss.xml"),
        Triple("tech", "BBC Technology", "https://feeds.bbci.co.uk/news/technology/rss.xml"),
        Triple("sport", "BBC Sport", "https://feeds.bbci.co.uk/sport/rss.xml"),
        Triple("fun", "BBC Entertainment", "https://feeds.bbci.co.uk/news/entertainment_and_arts/rss.xml")
    )

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
    private val OG_A = Regex("<meta[^>]+property=[\"']og:image[\"'][^>]*content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val OG_B = Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]*property=[\"']og:image[\"']", RegexOption.IGNORE_CASE)

    /** Returns {"top":[...],"africa":[...],...} or null if nothing could be loaded. */
    fun fetchAll(): String? {
        val pool = Executors.newFixedThreadPool(6)
        try {
            val jobs = FEEDS.map { f ->
                pool.submit(Callable<JSONArray> {
                    try {
                        val arr = fetchFeed(f.third, f.second)
                        fillMissingImages(arr, pool)
                        arr
                    } catch (e: Exception) {
                        JSONArray()
                    }
                })
            }
            val root = JSONObject()
            var any = false
            for (i in FEEDS.indices) {
                val arr: JSONArray = try {
                    jobs[i].get(25, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    JSONArray()
                }
                if (arr.length() > 0) {
                    root.put(FEEDS[i].first, arr)
                    any = true
                }
            }
            return if (any) root.toString() else null
        } finally {
            pool.shutdown()
        }
    }

    /** The first story of the top feed (used by the daily notification). */
    fun fetchTop(): JSONObject? {
        return try {
            val arr = fetchFeed(FEEDS[0].third, FEEDS[0].second)
            val n = minOf(arr.length(), 6)
            for (i in 0 until n) {
                val o = arr.getJSONObject(i)
                if (o.optString("i").isEmpty()) {
                    val og = ogImage(o.optString("l"))
                    if (og.isNotEmpty()) o.put("i", og)
                }
                if (o.optString("i").isNotEmpty()) return o
            }
            if (arr.length() > 0) arr.getJSONObject(0) else null
        } catch (e: Exception) {
            null
        }
    }

    /** Downloads a picture for the notification. */
    fun fetchBitmap(url: String): android.graphics.Bitmap? {
        if (url.isEmpty()) return null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", UA)
            try {
                conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        return conn
    }

    private fun fetchFeed(url: String, source: String): JSONArray {
        val conn = open(url)
        try {
            return conn.inputStream.use { parse(it, source) }
        } finally {
            conn.disconnect()
        }
    }

    /** Stories whose feed has no picture get one from the article page (og:image). */
    private fun fillMissingImages(arr: JSONArray, pool: java.util.concurrent.ExecutorService) {
        val jobs = ArrayList<Pair<JSONObject, java.util.concurrent.Future<String>>>()
        val n = minOf(arr.length(), 10)
        for (i in 0 until n) {
            val o = arr.getJSONObject(i)
            if (o.optString("i").isEmpty()) {
                val link = o.optString("l")
                jobs.add(Pair(o, pool.submit(Callable<String> { ogImage(link) })))
            }
        }
        for ((o, f) in jobs) {
            try {
                val u = f.get(12, TimeUnit.SECONDS)
                if (u.isNotEmpty()) {
                    o.put("i", u)
                    o.put("i0", u)
                }
            } catch (e: Exception) {
                // leave it without a picture; the page draws a colour card instead
            }
        }
    }

    private fun ogImage(page: String): String {
        if (!page.startsWith("http")) return ""
        return try {
            val conn = open(page)
            try {
                val html = conn.inputStream.use { s ->
                    val buf = ByteArray(60 * 1024)
                    var total = 0
                    while (total < buf.size) {
                        val r = s.read(buf, total, buf.size - total)
                        if (r < 0) break
                        total += r
                    }
                    String(buf, 0, total, Charsets.UTF_8)
                }
                val m = OG_A.find(html) ?: OG_B.find(html)
                (m?.groupValues?.get(1) ?: "").replace("&amp;", "&").replace("http://", "https://")
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun parse(stream: InputStream, source: String): JSONArray {
        val out = JSONArray()
        val p = Xml.newPullParser()
        p.setInput(stream, null)

        var inItem = false
        var title = ""
        var link = ""
        var desc = ""
        var date = ""
        var img = ""
        var imgW = -1

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT && out.length() < 14) {
            if (ev == XmlPullParser.START_TAG) {
                val n = p.name
                if (n == "item") {
                    inItem = true
                    title = ""
                    link = ""
                    desc = ""
                    date = ""
                    img = ""
                    imgW = -1
                } else if (inItem) {
                    when (n) {
                        "title" -> title = p.nextText().trim()
                        "link" -> link = p.nextText().trim()
                        "description" -> desc = p.nextText().trim()
                        "pubDate" -> date = p.nextText().trim()
                        "media:thumbnail", "media:content" -> {
                            val u = p.getAttributeValue(null, "url") ?: ""
                            val w = (p.getAttributeValue(null, "width") ?: "0").toIntOrNull() ?: 0
                            if (u.isNotEmpty() && (img.isEmpty() || w > imgW)) {
                                img = u
                                imgW = w
                            }
                        }
                        "enclosure" -> {
                            val type = p.getAttributeValue(null, "type") ?: ""
                            if (img.isEmpty() && type.startsWith("image")) {
                                img = p.getAttributeValue(null, "url") ?: ""
                            }
                        }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "item") {
                inItem = false
                if (title.isNotEmpty() && link.startsWith("http")) {
                    val small = img.replace("http://", "https://")
                    val o = JSONObject()
                    o.put("t", title)
                    o.put("l", link)
                    // BBC thumbnails are tiny (/240/); ask for a bigger one, the page falls back to i0 if missing
                    o.put("i", small.replace("/240/", "/800/"))
                    o.put("i0", small)
                    o.put("d", desc.replace(Regex("<[^>]*>"), "").trim().take(140))
                    o.put("a", ago(date))
                    o.put("s", source)
                    out.put(o)
                }
            }
            ev = p.next()
        }
        return out
    }

    private fun ago(date: String): String {
        if (date.isEmpty()) return ""
        return try {
            val d = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH).parse(date)
            if (d == null) {
                ""
            } else {
                val mins = (System.currentTimeMillis() - d.time) / 60000
                when {
                    mins < 1 -> "just now"
                    mins < 60 -> mins.toString() + "m ago"
                    mins < 1440 -> (mins / 60).toString() + "h ago"
                    else -> (mins / 1440).toString() + "d ago"
                }
            }
        } catch (e: Exception) {
            ""
        }
    }
}
