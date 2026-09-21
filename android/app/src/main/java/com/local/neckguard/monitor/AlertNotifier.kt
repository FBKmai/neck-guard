package com.local.neckguard.monitor

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.local.neckguard.MainActivity
import com.local.neckguard.NeckGuardApp
import com.local.neckguard.R
import java.io.File
import java.util.Locale

/** 通知构建与发送。前倾提醒用 BigPictureStyle 附带快照。 */
class AlertNotifier(private val context: Context) {

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun buildStatusNotification(text: String): Notification =
        NotificationCompat.Builder(context, NeckGuardApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_status_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent(MainActivity.EXTRA_SCREEN_MONITOR))
            .addAction(0, "停止监测", stopServiceIntent())
            .build()

    fun updateStatus(text: String) {
        if (!canPost()) return
        try {
            NotificationManagerCompat.from(context).notify(STATUS_ID, buildStatusNotification(text))
        } catch (e: SecurityException) {
            Log.w(TAG, "notify status denied", e)
        }
    }

    fun postAlert(neckDeg: Float?, thresholdDeg: Float?, snapshot: File?) {
        if (!canPost()) {
            Log.w(TAG, "notification permission missing, alert dropped")
            return
        }
        val body = buildString {
            append("颈部倾角 ")
            append(neckDeg?.let { String.format(Locale.US, "%.1f°", it) } ?: "--")
            thresholdDeg?.let { append("，阈值 ").append(String.format(Locale.US, "%.0f°", it)) }
            append("，请抬头收下巴。")
        }
        val builder = NotificationCompat.Builder(context, NeckGuardApp.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_alert_title))
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 300, 150, 300))
            .setContentIntent(openAppIntent(MainActivity.EXTRA_SCREEN_MONITOR))

        if (snapshot != null && snapshot.exists()) {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
                val bmp = BitmapFactory.decodeFile(snapshot.absolutePath, opts)
                if (bmp != null) {
                    builder.setLargeIcon(bmp)
                        .setStyle(
                            NotificationCompat.BigPictureStyle()
                                .bigPicture(bmp)
                                .bigLargeIcon(null as android.graphics.Bitmap?)
                                .setSummaryText(body),
                        )
                }
            } catch (e: Exception) {
                Log.w(TAG, "decode snapshot for notification failed", e)
            }
        }
        try {
            NotificationManagerCompat.from(context).notify(ALERT_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "notify alert denied", e)
        }
    }

    /**
     * 恢复端正的提醒：静音、低优先级、可自动消失，同时收掉还挂着的前倾提醒。
     * 目的是让用户知道"系统看到你坐正了"，而不是再打扰一次。
     */
    fun postRecovered(neckDeg: Float?, forwardHeadMillis: Long) {
        try {
            NotificationManagerCompat.from(context).cancel(ALERT_ID)
        } catch (e: Exception) {
            Log.w(TAG, "cancel alert failed", e)
        }
        if (!canPost()) return
        val seconds = (forwardHeadMillis / 1000L).coerceAtLeast(0L)
        val body = buildString {
            append("颈部倾角 ")
            append(neckDeg?.let { String.format(Locale.US, "%.1f°", it) } ?: "--")
            if (seconds > 0) append("，前倾持续 ").append(seconds).append(" 秒")
            append("。")
        }
        val n = NotificationCompat.Builder(context, NeckGuardApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("已恢复端正坐姿")
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(RECOVERED_TIMEOUT_MILLIS)
            .setContentIntent(openAppIntent(MainActivity.EXTRA_SCREEN_MONITOR))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(RECOVERED_ID, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "notify recovered denied", e)
        }
    }

    fun postError(message: String) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, NeckGuardApp.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("颈椎卫士出错")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(MainActivity.EXTRA_SCREEN_MONITOR))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ERROR_ID, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "notify error denied", e)
        }
    }

    private fun openAppIntent(screen: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_SCREEN, screen)
        }
        return PendingIntent.getActivity(
            context,
            REQ_OPEN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopServiceIntent(): PendingIntent {
        val intent = Intent(context, MonitorService::class.java).apply { action = MonitorService.ACTION_STOP }
        return PendingIntent.getService(
            context,
            REQ_STOP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "AlertNotifier"
        const val STATUS_ID = 1001
        const val ALERT_ID = 2001
        const val ERROR_ID = 3001
        const val RECOVERED_ID = 4001
        private const val RECOVERED_TIMEOUT_MILLIS = 30_000L
        private const val REQ_OPEN = 10
        private const val REQ_STOP = 11
    }
}
