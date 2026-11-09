package com.yoolan.vyro

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.util.Calendar

/** Daily "top story" notification. Uses an inexact repeating alarm, so no special permission is needed. */
object NewsAlarm {

    private const val CHANNEL = "vyro_news"
    private const val NOTIF_ID = 4201
    private const val REQ = 4202

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, NewsReceiver::class.java)
        return PendingIntent.getBroadcast(
            ctx, REQ, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 8)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
            am.setInexactRepeating(
                AlarmManager.RTC, cal.timeInMillis, AlarmManager.INTERVAL_DAY, pending(ctx)
            )
        } catch (e: Exception) {
            // alarms unavailable: the app still works, just no daily notification
        }
    }

    fun cancel(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(ctx))
        } catch (e: Exception) {
            // ignore
        }
    }

    fun postTopStory(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val story = News.fetchTop() ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Daily news", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "One top story every morning"
            nm.createNotificationChannel(ch)
        }
        val link = story.optString("l")
        val open = Intent(ctx, MainActivity::class.java)
        open.action = Intent.ACTION_VIEW
        open.data = Uri.parse(link)
        open.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val tap = PendingIntent.getActivity(
            ctx, REQ + 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL)
        else Notification.Builder(ctx)
        b.setSmallIcon(R.drawable.ic_bell)
        b.setContentTitle(story.optString("t"))
        val desc = story.optString("d")
        b.setContentText(if (desc.isEmpty()) "Vyro daily news · " + story.optString("s") else desc)
        b.setContentIntent(tap)
        b.setAutoCancel(true)
        b.setCategory(Notification.CATEGORY_RECOMMENDATION)
        val bmp = News.fetchBitmap(story.optString("i"))
        if (bmp != null) {
            b.setLargeIcon(bmp)
            b.setStyle(Notification.BigPictureStyle().bigPicture(bmp).setSummaryText(desc))
        } else {
            b.setStyle(Notification.BigTextStyle().bigText(desc))
        }
        nm.notify(NOTIF_ID, b.build())
    }
}

class NewsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs(context).dailyNews) return
        val pr = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                NewsAlarm.postTopStory(app)
            } catch (e: Exception) {
                // ignore
            } finally {
                pr.finish()
            }
        }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs(context).dailyNews) {
            NewsAlarm.schedule(context)
        }
    }
}
