package com.example.testwatch.ondemand

/** Watch-face section for the daily measurement: done/not-done status line + Measure button.
 *  Embedded as one item in MainActivity's list. */

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TextSecondary = Color(0xFFC3C2B7)
private val DoneGreen = Color(0xFF008300)

@Composable
fun OnDemandSection() {
    val inProgress by OnDemandController.inProgress.collectAsState()
    val lastCompleted by OnDemandController.lastCompletedAt.collectAsState()
    val context = LocalContext.current
    val doneToday = OnDemandController.isToday(lastCompleted)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when {
                inProgress -> "measuring…"
                doneToday -> "✓ done today · " +
                    SimpleDateFormat("HH:mm", Locale.US).format(Date(lastCompleted))
                else -> "not done today"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (doneToday) DoneGreen else TextSecondary,
        )
        Button(
            onClick = { OnDemandController.startRound(context) },
            enabled = !inProgress,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(if (doneToday) "Measure again" else "Measure now")
        }
        if (doneToday && !inProgress) {
            Text(
                "re-run replaces today's",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
    }
}
