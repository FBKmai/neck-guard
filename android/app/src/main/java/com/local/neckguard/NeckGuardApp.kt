package com.local.neckguard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class NeckGuardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val alert = NotificationChannel(
            CHANNEL_ALERT,
            getString(R.string.channel_alert_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.channel_alert_desc)
            enableVibration(true)
        }
        val status = NotificationChannel(
            CHANNEL_STATUS,
            getString(R.string.channel_status_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_status_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(alert)
        manager.createNotificationChannel(status)
    }

    companion object {
        const val CHANNEL_ALERT = "neckguard.alert"
        const val CHANNEL_STATUS = "neckguard.status"
    }
}
