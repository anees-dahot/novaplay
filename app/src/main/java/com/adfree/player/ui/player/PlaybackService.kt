package com.adfree.player.ui.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.adfree.player.R

/** What the background notification needs from the player. Set by PlayerActivity. */
interface BackgroundPlayer {
    val videoTitle: String
    val isPlaying: Boolean
    val positionMs: Long
    val lengthMs: Long
    fun togglePlay()
    fun playPrev()
    fun playNext()
    fun seekTo(ms: Long)
}

/**
 * Keeps audio going when the screen is off / app is in the background, and shows
 * a media notification with lock-screen controls. The player itself stays in PlayerActivity.
 */
class PlaybackService : Service() {

    companion object {
        var player: BackgroundPlayer? = null
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 42
        private const val ACTION_TOGGLE = "toggle"
        private const val ACTION_PREV = "prev"
        private const val ACTION_NEXT = "next"
        private const val ACTION_UPDATE = "update"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))

        /** Refresh notification after play/pause/track changes; no-op if not running. */
        fun update(context: Context) {
            if (running) context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_UPDATE))
        }

        fun stop(context: Context) = context.stopService(Intent(context, PlaybackService::class.java))

        @Volatile private var running = false
    }

    private lateinit var session: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
            )
        }
        session = MediaSessionCompat(this, "NovaPlay").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = act { if (!it.isPlaying) it.togglePlay() }
                override fun onPause() = act { if (it.isPlaying) it.togglePlay() }
                override fun onSkipToNext() = act { it.playNext() }
                override fun onSkipToPrevious() = act { it.playPrev() }
                override fun onSeekTo(pos: Long) = act { it.seekTo(pos) }
            })
            isActive = true
        }
    }

    private fun act(block: (BackgroundPlayer) -> Unit) {
        player?.let(block)
        refresh()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> player?.togglePlay()
            ACTION_PREV -> player?.playPrev()
            ACTION_NEXT -> player?.playNext()
        }
        refresh()
        return START_NOT_STICKY
    }

    private fun refresh() {
        val p = player ?: run { stopSelf(); return }
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, p.videoTitle)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, p.lengthMs)
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(
                    if (p.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    p.positionMs, if (p.isPlaying) 1f else 0f,
                )
                .build()
        )

        fun action(icon: Int, title: String, act: String) = NotificationCompat.Action(
            icon, title,
            PendingIntent.getService(
                this, act.hashCode(),
                Intent(this, PlaybackService::class.java).setAction(act),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(p.videoTitle)
            .setContentText("NovaPlay")
            .setContentIntent(open)
            .setOngoing(p.isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous", ACTION_PREV))
            .addAction(
                if (p.isPlaying) action(android.R.drawable.ic_media_pause, "Pause", ACTION_TOGGLE)
                else action(android.R.drawable.ic_media_play, "Play", ACTION_TOGGLE)
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next", ACTION_NEXT))
            .setStyle(MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()

        ServiceCompat.startForeground(
            this, NOTIF_ID, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
    }

    override fun onDestroy() {
        running = false
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
