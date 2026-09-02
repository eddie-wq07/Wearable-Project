package com.example.testwatch.ondemand

/** Daily nudge at REMINDER_HOUR: if today's round isn't done, buzz + notification that opens the
 *  app. Never starts the round itself — only the participant's Measure tap does that. */

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.example.testwatch.R
import com.example.testwatch.presentation.MainActivity
import java.util.Calendar

class DailyReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Reload persisted state (the process may be fresh) and arm tomorrow's alarm.
        OnDemandController.init(context)
        if (OnDemandController.doneToday()) return

        context.getSystemService(Vibrator::class.java)?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1),
        )
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Daily measurement reminder", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val openApp = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            context, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIF_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.splash_icon)
                .setContentTitle("Daily measurement")
                .setContentText("Tap Measure when you're ready")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        /** Arms the next REMINDER_HOUR occurrence; safe to call repeatedly (same PendingIntent). */
        fun scheduleNext(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, DailyReminderReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val at = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, OnDemandConfig.REMINDER_HOUR)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            // A nudge, not an alarm clock: exact-while-idle is enough, and no
            // alarm icon appears on the watch face.
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }

        private const val CHANNEL_ID = "measure_reminder"
        private const val NOTIF_ID = 1003
    }
}
