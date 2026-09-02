package com.example.testwatch.ondemand

/** Timing for the participant-triggered daily measurement round. The round itself only ever
 *  starts from the Measure button; the only scheduled thing here is the reminder nudge. */

object OnDemandConfig {
    /** Local hour (24h) of the daily "measurement not done yet" reminder. */
    const val REMINDER_HOUR = 10
}
