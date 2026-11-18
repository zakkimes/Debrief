package com.kimes.daq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.kimes.daq.bt.ObdLink
import com.kimes.daq.ui.MainActivity
import java.io.File

/** Keeps the process and CPU alive while a session is logging, even with the screen off. */
class LoggerService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Hub.stopSession()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Logging", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setContentTitle("Debrief is recording")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "daq:log").apply { acquire() }
        }
        val cont = intent?.getStringExtra(EXTRA_CONTINUE)?.let { File(it) }
        val mode = ObdLink.Mode.valueOf(intent?.getStringExtra(EXTRA_MODE) ?: "AUTO")
        Hub.startSession(cont, mode)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Hub.stopSession()
        wake?.release()
        wake = null
        super.onDestroy()
    }

    companion object {
        private const val CH = "log"
        const val ACTION_STOP = "stop"
        const val EXTRA_CONTINUE = "continue"
        const val EXTRA_MODE = "mode"

        fun start(ctx: Context, continueDir: File?, mode: ObdLink.Mode) {
            ctx.startForegroundService(Intent(ctx, LoggerService::class.java).apply {
                continueDir?.let { putExtra(EXTRA_CONTINUE, it.absolutePath) }
                putExtra(EXTRA_MODE, mode.name)
            })
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, LoggerService::class.java).setAction(ACTION_STOP))
        }
    }
}
