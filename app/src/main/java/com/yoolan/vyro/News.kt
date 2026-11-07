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

/** Downloads a few public news feeds (RSS) and turns them into one JSON string for the home page. */
object News {

    private val FEEDS = arrayOf(
        Triple("top", "BBC News", "https://feeds.bbci.co.uk/news/rss.xml"),
        Triple("africa", "BBC Africa", "https://feeds.bbci.co.uk/news/world/africa/rss.xml"),
        Triple("swahili", "BBC Swahili", "https://feeds.bbci.co.uk/swahili/rss.xml"),
        Triple("tech", "BBC Technology", "https://feeds.bbci.co.uk/news/technology/rss.xml"),
        Triple("sport", "BBC Sport", "https://feeds.bbci.co.uk/sport/rss.xml")
    )

    /** Returns {"top":[...],"africa":[...],...} or null if nothing could be loaded. */
    fun fetchAll(): String? {
        val root = JSONObject()
        var any = false
        for ((key, source, url) in FEEDS) {
            try {
                val arr = fetchFeed(url, source)
                if (arr.length() > 0) {
                    root.put(key, arr)
                    any = true
                }
            } catch (e: Exception) {
                // skip this feed, keep the others
            }
        }
        return if (any) root.toString() else null
    }

    private fun fetchFeed(url: String, source: String): JSONArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Vyro/0.3")
        try {
            return conn.inputStream.use { parse(it, source) }
        } finally {
            conn.disconnect()
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

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT && out.length() < 12) {
            if (ev == XmlPullParser.START_TAG) {
                val n = p.name
                if (n == "item") {
                    inItem = true
                    title = ""
                    link = ""
                    desc = ""
                    date = ""
                    img = ""
                } else if (inItem) {
                    when (n) {
                        "title" -> title = p.nextText().trim()
                        "link" -> link = p.nextText().trim()
                        "description" -> desc = p.nextText().trim()
                        "pubDate" -> date = p.nextText().trim()
                        "media:thumbnail", "media:content" -> {
                            if (img.isEmpty()) img = p.getAttributeValue(null, "url") ?: ""
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
                    val big = small.replace("/240/", "/800/")
                    val o = JSONObject()
                    o.put("t", title)
                    o.put("l", link)
                    o.put("i", big)
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
