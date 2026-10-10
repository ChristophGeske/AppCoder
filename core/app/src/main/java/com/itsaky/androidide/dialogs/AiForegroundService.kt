package com.itsaky.androidide.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.itsaky.androidide.R

class AiForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        // CRITICAL FIX: Android will crash the app if startForeground is not called
        // within 5 seconds of the service being created. Calling it immediately
        // in onCreate guarantees it beats the timer.
        startInForeground("Initializing AI engine...")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "AI code generation in progress"
        // Calling this again updates the existing notification with the actual text
        startInForeground(text)
        return START_STICKY
    }

    private fun startInForeground(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI Jobs",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher) // use existing app icon
            .setContentTitle("AndroidIDE")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            startForeground(NOTIF_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        private const val CHANNEL_ID = "ai_jobs_channel"
        private const val NOTIF_ID = 4202
        private const val EXTRA_TEXT = "extra_text"

        fun start(context: Context, text: String) {
            try {
                val intent = Intent(context, AiForegroundService::class.java).apply {
                    putExtra(EXTRA_TEXT, text)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace() // Catch background-launch restrictions
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, AiForegroundService::class.java))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}