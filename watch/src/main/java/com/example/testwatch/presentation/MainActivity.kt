package com.example.testwatch.presentation

/** Watch UI: requests permissions, starts HrTrackingService, displays live BPM/skin-temp/SpO2. */

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.example.testwatch.R
import com.example.testwatch.presentation.theme.TestWatchTheme
import com.example.testwatch.tracking.HrTrackingService
import com.example.testwatch.tracking.TrackingState

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissions = buildList {
            add(Manifest.permission.BODY_SENSORS)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            add("com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA")
            if (Build.VERSION.SDK_INT >= 36) {
                add("android.permission.health.READ_HEART_RATE")
                add("android.permission.health.READ_OXYGEN_SATURATION")
                add("android.permission.health.READ_SKIN_TEMPERATURE")
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        if (permissions.any { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_DENIED }) {
            requestPermissions(permissions, 0)
        } else {
            startTrackingService()
        }

        setContent { WearApp() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // BODY_SENSORS is the only hard requirement; sensors whose extra permission
        // was denied just fail individually and are logged by their tracker.
        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startTrackingService()
        } else {
            Toast.makeText(this, R.string.no_permission_message, Toast.LENGTH_LONG).show()
        }
    }

    private fun startTrackingService() {
        val intent = Intent(this, HrTrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

// Dark-mode categorical palette (dataviz reference); values wear text tokens,
// the colored dot carries sensor identity.
private val TextSecondary = Color(0xFFC3C2B7)
private val DotHr = Color(0xFF3987E5)
private val DotTemp = Color(0xFFC98500)
private val DotSpo2 = Color(0xFFD55181)
private val DotLive = Color(0xFF008300)

@Composable
fun WearApp() {
    val bpm by TrackingState.latestBpm.collectAsState()
    val status by TrackingState.latestStatus.collectAsState()
    val ready by TrackingState.ready.collectAsState()
    val worn by TrackingState.deviceWorn.collectAsState()
    val measuring by TrackingState.currentMeasurement.collectAsState()
    val skinTemp by TrackingState.latestSkinTemp.collectAsState()
    val spo2 by TrackingState.latestSpo2.collectAsState()

    val statusText = when {
        !ready -> "connecting"
        !worn -> "off wrist"
        status == 1 -> "tracking"
        status == -1 -> "off wrist"
        status == -2 -> "motion"
        status == -3 -> "low signal"
        else -> "status $status"
    }

    TestWatchTheme {
        AppScaffold {
            val listState = rememberTransformingLazyColumnState()
            ScreenScaffold(scrollState = listState) { contentPadding ->
                TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
                    item {
                        val m = measuring
                        if (m != null) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("MEASURING", style = MaterialTheme.typography.labelSmall, color = DotTemp)
                                Text(
                                    m,
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    "Hold position until the next buzz",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                MetricLabel("HEART RATE", DotHr)
                                Text(
                                    if (bpm > 0) "$bpm" else "--",
                                    style = MaterialTheme.typography.displayLarge,
                                )
                                Text("bpm · $statusText", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            }
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            MetricCell("SKIN", if (skinTemp.isNaN()) "--" else "%.1f°".format(skinTemp), DotTemp)
                            MetricCell("SPO2", if (spo2 > 0) "$spo2%" else "--", DotSpo2)
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Dot(if (ready) DotLive else TextSecondary)
                            Text(
                                if (ready) "  ppg · accel · temp live" else "  streams idle",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricLabel(label: String, dot: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(dot)
        Text("  $label", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
private fun MetricCell(label: String, value: String, dot: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MetricLabel(label, dot)
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        modifier = Modifier
            .size(6.dp)
            .background(color, CircleShape),
    )
}
