package com.example.testwatch.ondemand

/** State + persistence for the once-daily participant-triggered measurement round. The round runs
 *  in HrTrackingService/SensorEngine; this object owns when it starts and whether today's is done. */

import android.content.Context
import android.content.Intent
import com.example.testwatch.tracking.HrTrackingService
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Calendar

object OnDemandController {

    /** True from the Measure tap until SensorEngine reports the round finished. */
    val inProgress = MutableStateFlow(false)

    /** Epoch ms of the last completed round; 0 = never. */
    val lastCompletedAt = MutableStateFlow(0L)

    /** Idempotent: reloads persisted state and (re)arms the daily reminder. */
    fun init(context: Context) {
        lastCompletedAt.value = prefs(context).getLong(KEY_LAST_COMPLETED, 0L)
        DailyReminderReceiver.scheduleNext(context)
    }

    /** A re-run replaces today's earlier round — the service deletes today's on-demand
     *  rows before starting (see ACTION_RUN_ROUND in HrTrackingService). */
    fun startRound(context: Context) {
        if (inProgress.value) return
        inProgress.value = true
        context.startForegroundService(
            Intent(context, HrTrackingService::class.java)
                .setAction(HrTrackingService.ACTION_RUN_ROUND),
        )
    }

    /** completed=false (cancelled round, engine not connected) keeps today "not done". */
    fun onRoundFinished(context: Context, completed: Boolean) {
        inProgress.value = false
        if (!completed) return
        val now = System.currentTimeMillis()
        lastCompletedAt.value = now
        prefs(context).edit().putLong(KEY_LAST_COMPLETED, now).apply()
    }

    fun doneToday(): Boolean = isToday(lastCompletedAt.value)

    fun isToday(ms: Long): Boolean {
        if (ms == 0L) return false
        val then = Calendar.getInstance().apply { timeInMillis = ms }
        val now = Calendar.getInstance()
        return then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    }

    /** Local midnight — rows at/after this count as "today's round" for replace semantics. */
    fun startOfTodayMs(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun prefs(context: Context) =
        context.getSharedPreferences("ondemand", Context.MODE_PRIVATE)

    private const val KEY_LAST_COMPLETED = "last_completed_ms"
}
