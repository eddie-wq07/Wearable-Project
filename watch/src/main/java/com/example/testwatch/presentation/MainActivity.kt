package com.example.testwatch.presentation

/** Watch UI: requests permissions, starts HrTrackingService. Participant-facing screen is
 *  deliberately minimal — a good/not-good status and the daily Measure button; no data
 *  readouts (values go to the server, not the wrist). */

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.example.testwatch.R
import com.example.testwatch.kiosk.KioskGate
import com.example.testwatch.kiosk.KioskManager
import com.example.testwatch.ondemand.OnDemandController
import com.example.testwatch.ondemand.OnDemandSection
import com.example.testwatch.presentation.theme.TestWatchTheme
import com.example.testwatch.tracking.HrTrackingService
import com.example.testwatch.tracking.TrackingState
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OnDemandController.init(this)

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

        setContent { KioskGate { WearApp() } }
    }

    override fun onResume() {
        super.onResume()
        // Enters/leaves lock-task mode to match the kiosk flag (see kiosk/docs/kiosk-setup.md).
        KioskManager.sync(this)
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

// Participant-facing palette: one status color at a time, no data colors needed.
private val TextSecondary = Color(0xFFC3C2B7)
private val StatusGood = Color(0xFF008300)
private val StatusWarn = Color(0xFFC98500)

@Composable
fun WearApp() {
    val ready by TrackingState.ready.collectAsState()
    val worn by TrackingState.deviceWorn.collectAsState()
    val measuring by TrackingState.currentMeasurement.collectAsState()

    TestWatchTheme {
        AppScaffold {
            ScreenScaffold { contentPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    val m = measuring
                    if (m != null) MeasuringNotice(m) else StatusHome(ready = ready, worn = worn)
                }
            }
        }
    }
}

/** Mid-measurement instruction — the one moment the participant must do something. */
@Composable
private fun MeasuringNotice(prompt: String) {
    // Worst-case countdown; most measurements buzz to the next one before it hits 0.
    val deadline by TrackingState.measurementDeadline.collectAsState()
    var remainingSec by remember { mutableLongStateOf(0L) }
    LaunchedEffect(deadline) {
        while (true) {
            remainingSec = ((deadline - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            delay(250)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("MEASURING", style = MaterialTheme.typography.labelSmall, color = StatusWarn)
        Text(
            prompt,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            "${remainingSec}s",
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            "Hold still until the next buzz",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
        val context = LocalContext.current
        OutlinedButton(
            onClick = { OnDemandController.cancelRound(context) },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Cancel")
        }
    }
}

/** Everything the participant needs: is the watch doing its job, and the Measure button. */
@Composable
private fun StatusHome(ready: Boolean, worn: Boolean) {
    val (color, text) = when {
        !ready -> TextSecondary to "Starting up…"
        !worn -> StatusWarn to "Place watch on wrist"
        else -> StatusGood to "All good"
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color, CircleShape),
            )
            Text("  $text", style = MaterialTheme.typography.titleMedium)
        }
        OnDemandSection()
    }
}
