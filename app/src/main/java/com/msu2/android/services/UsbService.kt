package com.msu2.android.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.msu2.android.R

/** 连接期间前台保活：后台不被系统冻结/清理。 */
class UsbService : Service() {

    companion object {
        private const val CHANNEL_ID = "usb_keepalive"
        private const val NOTIF_ID = 1002

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, UsbService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UsbService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.usb_keepalive_title))
            .setContentText(getString(R.string.usb_keepalive_text))
            .setOngoing(true)
            .build()
}
