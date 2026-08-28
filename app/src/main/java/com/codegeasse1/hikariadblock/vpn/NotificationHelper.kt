package com.codegeasse1.hikariadblock.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.codegeasse1.hikariadblock.MainActivity
import com.codegeasse1.hikariadblock.R

object NotificationHelper {

    const val CHANNEL_ID = "hikari_vpn_status"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "Ad blocking status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while Hikari AdBlock is filtering traffic"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    fun build(context: Context): Notification {
        val pi = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Hikari AdBlock is active")
            .setContentText("Blocking ads, trackers and malware")
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
