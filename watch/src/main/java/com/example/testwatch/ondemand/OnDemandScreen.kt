package com.example.testwatch.ondemand

/** The daily check-in block on the watch face: done/not-done line + a big Measure button.
 *  Embedded below the status line in MainActivity. */

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            when {
                inProgress -> "Measuring…"
                doneToday -> "✓ Done today"
                else -> "Not done yet today"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (doneToday) DoneGreen else TextSecondary,
        )
        Button(
            onClick = { OnDemandController.startRound(context) },
            enabled = !inProgress,
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(0.85f)
                .height(56.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (doneToday) "Measure again" else "Measure",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
