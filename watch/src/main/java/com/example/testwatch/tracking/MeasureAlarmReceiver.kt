package com.example.testwatch.tracking

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Exact alarm-clock tick for the on-demand round: fires even in doze with the
 *  screen off, and restarts the tracking service if it has died. */
class MeasureAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // The alarm grants a temporary allowlist window, so a foreground-service
        // start is permitted here even from deep doze.
        context.startForegroundService(
            Intent(context, HrTrackingService::class.java)
                .setAction(HrTrackingService.ACTION_RUN_ROUND),
        )
    }

    companion object {
        fun scheduleNext(context: Context, delayMinutes: Long) {
            val am = context.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, MeasureAlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val at = System.currentTimeMillis() + delayMinutes * 60_000L
            // setAlarmClock is exempt from doze deferral — the delay()-based loop
            // this replaces stalled for hours overnight.
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, pi), pi)
        }
    }
}
