package com.example.testwatch.presentation

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.example.testwatch.R
import com.example.testwatch.kiosk.PinManager
import com.example.testwatch.presentation.theme.TestWatchTheme
import com.example.testwatch.tracking.HrTrackingService
import com.example.testwatch.tracking.TrackingState

private enum class KioskScreen { Main, PinUnlock }

class MainActivity : ComponentActivity() {

    private var kioskPaused = false
    private var screen by mutableStateOf(KioskScreen.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissions = buildList {
            add(Manifest.permission.BODY_SENSORS)
            if (Build.VERSION.SDK_INT >= 36) add("android.permission.health.READ_HEART_RATE")
            if (Build.VERSION.SDK_INT >= 33) add("android.permission.POST_NOTIFICATIONS")
        }.toTypedArray()
        if (permissions.any { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_DENIED }) {
            requestPermissions(permissions, 0)
        } else {
            startTrackingService()
        }

        setContent {
            when (screen) {
                KioskScreen.Main -> WearApp(
                    onLogoutClick = { screen = KioskScreen.PinUnlock },
                )
                KioskScreen.PinUnlock -> PinUnlockScreen(
                    verify = { PinManager.verify(it) },
                    onCorrectPin = {
                        exitKioskMode()
                        screen = KioskScreen.Main
                    },
                    onCancel = { screen = KioskScreen.Main },
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            startTrackingService()
        } else {
            Toast.makeText(this, R.string.no_permission_message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!kioskPaused) enterKioskMode()
    }

    private fun startTrackingService() {
        val intent = Intent(this, HrTrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun enterKioskMode() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isLockTaskPermitted(packageName)) {
            Log.w(TAG, "Lock task not permitted — app is not Device Owner. Skipping.")
            return
        }
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            try {
                startLockTask()
            } catch (t: Throwable) {
                Log.w(TAG, "startLockTask failed: ${t.message}")
            }
        }
    }

    private fun exitKioskMode() {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
            try {
                stopLockTask()
                kioskPaused = true
                Toast.makeText(this, R.string.kiosk_paused, Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                Log.w(TAG, "stopLockTask failed: ${t.message}")
            }
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}

@Composable
fun WearApp(onLogoutClick: () -> Unit) {
    val bpm by TrackingState.latestBpm.collectAsState()
    val status by TrackingState.latestStatus.collectAsState()
    val ready by TrackingState.ready.collectAsState()
    val worn by TrackingState.deviceWorn.collectAsState()

    val bpmText = if (bpm > 0) bpm.toString() else "--"
    val statusText = when {
        !ready -> "Connecting..."
        !worn -> "Off wrist"
        status == 1 -> "Tracking"
        status == -1 -> "Off wrist"
        status == -2 -> "Motion"
        status == -3 -> "Low signal"
        else -> "Status $status"
    }

    TestWatchTheme {
        AppScaffold {
            val listState = rememberTransformingLazyColumnState()
            val transformationSpec = rememberTransformationSpec()
            ScreenScaffold(
                scrollState = listState,
                edgeButton = {
                    EdgeButton(
                        onClick = onLogoutClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Text("Log out")
                    }
                },
            ) { contentPadding ->
                TransformingLazyColumn(contentPadding = contentPadding, state = listState) {
                    item {
                        ListHeader(
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        ) {
                            Text("Heart Rate")
                        }
                    }
                    item {
                        Text(
                            text = "$bpmText bpm",
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.displayLarge,
                        )
                    }
                    item {
                        Text(
                            text = statusText,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}
