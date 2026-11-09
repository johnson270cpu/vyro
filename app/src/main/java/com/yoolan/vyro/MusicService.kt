package com.yoolan.vyro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class Track(val uri: String, val title: String, val secret: Boolean)

/**
 * Plays music in the background. It keeps running when the app is closed, shows a
 * notification with controls, and works with lock-screen and headset buttons.
 */
class MusicService : Service() {

    companion object {
        const val CHANNEL_ID = "vyro_music"
        private const val NOTIF_ID = 101
        const val ACTION_LOAD = "com.yoolan.vyro.LOAD"
        const val ACTION_TOGGLE = "com.yoolan.vyro.TOGGLE"
        const val ACTION_NEXT = "com.yoolan.vyro.NEXT"
        const val ACTION_PREV = "com.yoolan.vyro.PREV"
        const val ACTION_STOP = "com.yoolan.vyro.STOP"

        val PRESET_NAMES = arrayOf("Normal", "Bass boost", "Rock", "Pop", "Vocal", "Treble")
        private val PRESETS = arrayOf(
            floatArrayOf(0f, 0f, 0f, 0f, 0f),
            floatArrayOf(0.8f, 0.5f, 0f, 0f, 0f),
            floatArrayOf(0.5f, 0.2f, -0.2f, 0.2f, 0.5f),
            floatArrayOf(-0.1f, 0.3f, 0.5f, 0.2f, -0.2f),
            floatArrayOf(-0.3f, 0f, 0.5f, 0.5f, 0f),
            floatArrayOf(0f, 0f, 0.2f, 0.5f, 0.8f)
        )

        @Volatile
        var instance: MusicService? = null

        private var pendingTracks: List<Track>? = null
        private var pendingIndex = 0
        private var pendingShuffle = false

        /** Starts (or restarts) playback of a queue. */
        fun start(ctx: Context, tracks: List<Track>, index: Int, shuffle: Boolean) {
            pendingTracks = tracks
            pendingIndex = index
            pendingShuffle = shuffle
            val i = Intent(ctx, MusicService::class.java)
            i.action = ACTION_LOAD
            ctx.startForegroundService(i)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private var session: MediaSession? = null
    private var eq: Equalizer? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocus = false
    private var foreground = false
    private var stopped = false
    private var token = 0
    private var errors = 0
    private var sleepAt = 0L

    var prepared = false
        private set
    var tracks: List<Track> = emptyList()
        private set
    var index = 0
        private set
    var title = ""
        private set
    var artist = ""
        private set
    var artBitmap: Bitmap? = null
        private set
    var preset = 0
        private set
    var shuffle = false
    var repeat = 0   // 0 = off, 1 = repeat all, 2 = repeat one

    val isPlaying: Boolean
        get() {
            if (!prepared) return false
            return try {
                mp?.isPlaying == true
            } catch (e: Exception) {
                false
            }
        }

    val position: Int
        get() {
            if (!prepared) return 0
            return try {
                mp?.currentPosition ?: 0
            } catch (e: Exception) {
                0
            }
        }

    val duration: Int
        get() {
            if (!prepared) return 0
            return try {
                mp?.duration ?: 0
            } catch (e: Exception) {
                0
            }
        }

    val currentTrack: Track?
        get() = tracks.getOrNull(index)

    fun sleepLeftMs(): Long =
        if (sleepAt == 0L) 0L else maxOf(0L, sleepAt - System.currentTimeMillis())

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            pause()
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocus = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                resumeOnFocus = isPlaying
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mp?.setVolume(0.25f, 0.25f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                mp?.setVolume(1f, 1f)
                if (resumeOnFocus) {
                    resumeOnFocus = false
                    resume()
                }
            }
            else -> {
            }
        }
    }

    private val sleepRunnable = Runnable {
        sleepAt = 0L
        pause()
    }

    // ---------- service lifecycle ----------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ch = NotificationChannel(CHANNEL_ID, "Music playback", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)

        val s = MediaSession(this, "VyroMusic")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                resume()
            }

            override fun onPause() {
                pause()
            }

            override fun onSkipToNext() {
                next()
            }

            override fun onSkipToPrevious() {
                prev()
            }

            override fun onSeekTo(pos: Long) {
                seekTo(pos.toInt())
            }

            override fun onStop() {
                stopAll()
            }
        })
        s.isActive = true
        session = s

        val f = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(noisy, f)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LOAD -> {
                stopped = false
                startFg()
                val t = pendingTracks
                pendingTracks = null
                if (t != null && t.isNotEmpty()) {
                    tracks = t
                    shuffle = pendingShuffle
                    errors = 0
                    val first = if (pendingShuffle) t.indices.random() else pendingIndex
                    play(first)
                } else if (tracks.isEmpty()) {
                    stopAll()
                }
            }
            ACTION_TOGGLE -> toggle()
            ACTION_NEXT -> next()
            ACTION_PREV -> prev()
            ACTION_STOP -> stopAll()
            else -> {
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        session?.release()
        session = null
        try {
            unregisterReceiver(noisy)
        } catch (e: Exception) {
            // already gone
        }
        super.onDestroy()
    }

    // ---------- playback ----------

    private fun releasePlayer() {
        try {
            eq?.release()
        } catch (e: Exception) {
            // ignore
        }
        eq = null
        try {
            mp?.release()
        } catch (e: Exception) {
            // ignore
        }
        mp = null
        prepared = false
    }

    fun play(i: Int) {
        if (tracks.isEmpty()) return
        index = ((i % tracks.size) + tracks.size) % tracks.size
        val t = tracks[index]
        token++
        val my = token
        releasePlayer()
        title = if (t.secret) "Vault" else t.title
        artist = ""
        artBitmap = null

        val m = MediaPlayer()
        mp = m
        try {
            m.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            m.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            m.setDataSource(applicationContext, Uri.parse(t.uri))
            m.setOnPreparedListener { pl ->
                if (my == token) {
                    prepared = true
                    errors = 0
                    if (requestFocus()) pl.start()
                    setupEqualizer(pl)
                    if (!foreground && isPlaying) startFg()
                    updateSession()
                    refreshNotification()
                    loadMeta(t, my)
                }
            }
            m.setOnCompletionListener {
                if (my == token) onTrackEnd()
            }
            m.setOnErrorListener { _, _, _ ->
                if (my == token) {
                    prepared = false
                    errors++
                    if (errors >= tracks.size) {
                        handler.post { stopAll() }
                    } else {
                        handler.postDelayed({ next() }, 300)
                    }
                }
                true
            }
            m.prepareAsync()
        } catch (e: Exception) {
            errors++
            if (errors >= tracks.size) stopAll() else handler.postDelayed({ next() }, 300)
        }
        updateSession()
        refreshNotification()
    }

    private fun randomOther(): Int {
        var r = tracks.indices.random()
        if (r == index && tracks.size > 1) r = (r + 1) % tracks.size
        return r
    }

    private fun onTrackEnd() {
        when {
            repeat == 2 -> play(index)
            shuffle && tracks.size > 1 -> play(randomOther())
            index < tracks.size - 1 -> play(index + 1)
            repeat == 1 -> play(0)
            else -> {
                pause()
                seekTo(0)
            }
        }
    }

    fun next() {
        if (tracks.isEmpty()) return
        if (shuffle && tracks.size > 1) play(randomOther()) else play(index + 1)
    }

    fun prev() {
        if (tracks.isEmpty()) return
        if (position > 3000) seekTo(0) else play(if (index > 0) index - 1 else tracks.size - 1)
    }

    fun toggle() {
        if (isPlaying) pause() else resume()
    }

    fun pause() {
        try {
            mp?.pause()
        } catch (e: Exception) {
            // ignore
        }
        if (foreground) {
            try {
                stopForeground(STOP_FOREGROUND_DETACH)
            } catch (e: Exception) {
                // ignore
            }
            foreground = false
        }
        updateSession()
        refreshNotification()
    }

    fun resume() {
        if (!prepared || stopped) return
        if (requestFocus()) {
            try {
                mp?.start()
            } catch (e: Exception) {
                // ignore
            }
        }
        startFg()
        updateSession()
        refreshNotification()
    }

    fun seekTo(ms: Int) {
        if (!prepared) return
        try {
            mp?.seekTo(ms)
        } catch (e: Exception) {
            // ignore
        }
        updateSession()
    }

    fun setSleepTimer(minutes: Int) {
        handler.removeCallbacks(sleepRunnable)
        if (minutes <= 0) {
            sleepAt = 0L
            return
        }
        val ms = minutes * 60_000L
        sleepAt = System.currentTimeMillis() + ms
        handler.postDelayed(sleepRunnable, ms)
    }

    fun queueTitles(): List<String> = tracks.map { if (it.secret) "(hidden)" else it.title }

    fun stopAll() {
        stopped = true
        token++
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        session?.isActive = false
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // ignore
        }
        foreground = false
        stopSelf()
    }

    // ---------- audio focus and equalizer ----------

    private fun requestFocus(): Boolean {
        val am = audioManager ?: return true
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = req
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun setupEqualizer(p: MediaPlayer) {
        try {
            eq?.release()
            val e = Equalizer(0, p.audioSessionId)
            e.enabled = true
            eq = e
            applyPreset(preset)
        } catch (ex: Exception) {
            eq = null
        }
    }

    fun applyPreset(pr: Int) {
        preset = pr.coerceIn(0, PRESETS.size - 1)
        val e = eq ?: return
        try {
            val bands = e.numberOfBands.toInt()
            val range = e.bandLevelRange
            val min = range[0].toInt()
            val max = range[1].toInt()
            val shape = PRESETS[preset]
            for (b in 0 until bands) {
                val pos = if (bands <= 1) 0 else b * (shape.size - 1) / (bands - 1)
                val f = shape[pos]
                val level = if (f >= 0f) f * max else -f * min
                e.setBandLevel(b.toShort(), level.toInt().toShort())
            }
        } catch (ex: Exception) {
            // equalizer not supported on this phone
        }
    }

    // ---------- cover art, session and notification ----------

    private fun loadMeta(t: Track, my: Int) {
        Thread {
            var mTitle: String? = null
            var mArtist: String? = null
            var bmp: Bitmap? = null
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(applicationContext, Uri.parse(t.uri))
                mTitle = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                mArtist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val pic = r.embeddedPicture
                if (pic != null) {
                    val o = BitmapFactory.Options()
                    o.inSampleSize = 2
                    bmp = BitmapFactory.decodeByteArray(pic, 0, pic.size, o)
                }
            } catch (e: Exception) {
                // no tags: keep the file name
            } finally {
                try {
                    r.release()
                } catch (e: Exception) {
                    // ignore
                }
            }
            val finalTitle = mTitle
            val finalArtist = mArtist
            val finalBmp = bmp
            handler.post {
                if (my == token) {
                    if (!t.secret && !finalTitle.isNullOrBlank()) title = finalTitle.orEmpty()
                    if (!t.secret) artist = finalArtist.orEmpty()
                    artBitmap = finalBmp
                    updateSession()
                    refreshNotification()
                }
            }
        }.start()
    }

    private fun updateSession() {
        val s = session ?: return
        val secret = currentTrack?.secret == true
        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, if (secret) "Vyro" else title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration.toLong())
        val art = artBitmap
        if (art != null && !secret) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
        s.setMetadata(md.build())

        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_STOP
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, position.toLong(), 1f)
                .build()
        )
    }

    private fun svcPi(act: String, code: Int): PendingIntent {
        val i = Intent(this, MusicService::class.java)
        i.action = act
        return PendingIntent.getService(
            this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun noteAction(icon: Int, label: String, act: String, code: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), label, svcPi(act, code)).build()

    private fun buildNotification(): Notification {
        val secret = currentTrack?.secret == true
        val playing = isPlaying
        val open = Intent(this, MusicActivity::class.java)
        open.putExtra("resume", true)
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentPi = PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val b = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(if (secret || title.isEmpty()) "Vyro Music" else title)
            .setContentText(if (secret) "Playing" else if (artist.isNotEmpty()) artist else "Vyro")
            .setContentIntent(contentPi)
            .setDeleteIntent(svcPi(ACTION_STOP, 5))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(if (secret) Notification.VISIBILITY_SECRET else Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
        val art = artBitmap
        if (art != null && !secret) b.setLargeIcon(art)

        b.addAction(noteAction(R.drawable.ic_prev, "Previous", ACTION_PREV, 1))
        b.addAction(
            noteAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pause" else "Play",
                ACTION_TOGGLE,
                2
            )
        )
        b.addAction(noteAction(R.drawable.ic_next, "Next", ACTION_NEXT, 3))
        b.addAction(noteAction(R.drawable.ic_close, "Stop", ACTION_STOP, 4))

        val st = Notification.MediaStyle()
        val tok = session?.sessionToken
        if (tok != null) st.setMediaSession(tok)
        st.setShowActionsInCompactView(0, 1, 2)
        b.setStyle(st)
        return b.build()
    }

    private fun startFg() {
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, n)
            }
            foreground = true
        } catch (e: Exception) {
            foreground = false
        }
    }

    private fun refreshNotification() {
        if (stopped) return
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            // ignore
        }
    }
}
