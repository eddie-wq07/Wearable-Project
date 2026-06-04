package com.example.testwatch.presentation

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.example.testwatch.ConnectionManager
import com.example.testwatch.ConnectionObserver
import com.example.testwatch.HeartRateListener
import com.example.testwatch.OffBodyStatus
import com.example.testwatch.R
import com.example.testwatch.TrackerDataSubject
import com.example.testwatch.TrackerObserver
import com.example.testwatch.admin.HrDeviceAdminReceiver
import com.example.testwatch.kiosk.PinManager
import com.example.testwatch.presentation.theme.TestWatchTheme
import java.util.concurrent.atomic.AtomicBoolean

private enum class KioskScreen { Main, PinSetup, PinUnlock }

class MainActivity : ComponentActivity(), TrackerObserver, SensorEventListener {

    private val connected = AtomicBoolean(false)
    private val isMeasurementRunning = AtomicBoolean(false)
    private val deviceWorn = AtomicBoolean(true)

    private var heartRateAvailable = false
    private var heartRateListener: HeartRateListener? = null
    private var connectionManager: ConnectionManager? = null
    private var trackerDataSubject: TrackerDataSubject? = null
    private var mSensorManager: SensorManager? = null
    private var offBodySensor: Sensor? = null

    private lateinit var pinManager: PinManager
    private var kioskPaused = false

    private var hrValue by mutableStateOf("--")
    private var hrStatus by mutableStateOf("--")
    private var isMeasuring by mutableStateOf(false)
    private var isReady by mutableStateOf(false)
    private var screen by mutableStateOf(KioskScreen.Main)

    private val connectionObserver = object : ConnectionObserver {
        override fun onConnectionResult(isConnected: Boolean) {
            connected.set(isConnected)
            if (!isConnected) runOnUiThread { isReady = false }
        }

        override fun onHeartRateAvailability(isAvailable: Boolean) {
            heartRateAvailable = isAvailable
            if (isAvailable) {
                heartRateListener = HeartRateListener()
                heartRateListener!!.setTrackerDataSubject(trackerDataSubject)
                connectionManager!!.initHeartRate(heartRateListener)
                runOnUiThread { isReady = true }
            } else {
                runOnUiThread { isReady = false }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pinManager = PinManager(this)

        val permissions = if (Build.VERSION.SDK_INT >= 36) {
            arrayOf("android.permission.health.READ_HEART_RATE", Manifest.permission.BODY_SENSORS)
        } else {
            arrayOf(Manifest.permission.BODY_SENSORS)
        }
        if (permissions.any { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_DENIED }) {
            requestPermissions(permissions, 0)
        }

        trackerDataSubject = TrackerDataSubject()
        trackerDataSubject!!.addObserver(this)

        mSensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        offBodySensor = mSensorManager!!.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        if (offBodySensor == null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.no_off_body_sensor_title)
                .setMessage(R.string.no_off_body_sensor_message)
                .create().show()
        }

        createConnectionManager()

        screen = if (pinManager.isPinSet()) KioskScreen.Main else KioskScreen.PinSetup

        setContent {
            when (screen) {
                KioskScreen.Main -> WearApp(
                    hrValue = hrValue,
                    hrStatus = hrStatus,
                    isMeasuring = isMeasuring,
                    isReady = isReady,
                    onStartClick = { startMeasurement() },
                    onStopClick = { endMeasurement() },
                    onResetClick = { resetValues() },
                    onLogoutClick = { screen = KioskScreen.PinUnlock },
                )
                KioskScreen.PinSetup -> PinSetupScreen(
                    onPinChosen = { pin ->
                        pinManager.setPin(pin)
                        screen = KioskScreen.Main
                    },
                )
                KioskScreen.PinUnlock -> PinUnlockScreen(
                    verify = { pinManager.verify(it) },
                    onCorrectPin = {
                        exitKioskMode()
                        screen = KioskScreen.Main
                    },
                    onCancel = { screen = KioskScreen.Main },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!kioskPaused) enterKioskMode()
        offBodySensor?.let {
            mSensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
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

    override fun onPause() {
        super.onPause()
        offBodySensor?.let { mSensorManager?.unregisterListener(this) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (connected.get()) connectionManager?.disconnect()
        trackerDataSubject?.removeObserver(this)
    }

    private fun createConnectionManager() {
        try {
            connectionManager = ConnectionManager(connectionObserver)
            connectionManager!!.connect(this, applicationContext)
        } catch (t: Throwable) {
            Log.e(TAG, t.message ?: "Connection error")
        }
    }

    private fun startMeasurement() {
        if (!connected.get()) {
            Toast.makeText(this, R.string.no_connection_message, Toast.LENGTH_SHORT).show()
            return
        }
        if (!heartRateAvailable) {
            AlertDialog.Builder(this)
                .setTitle(R.string.no_heart_rate_available_title)
                .setMessage(R.string.no_heart_rate_available_message)
                .create().show()
            return
        }
        if (!deviceWorn.get()) {
            Toast.makeText(this, R.string.device_not_worn, Toast.LENGTH_SHORT).show()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        heartRateListener?.startTracker()
        isMeasurementRunning.set(true)
        isMeasuring = true
    }

    private fun endMeasurement() {
        heartRateListener?.stopTracker()
        isMeasurementRunning.set(false)
        runOnUiThread {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            isMeasuring = false
        }
    }

    private fun resetValues() {
        hrValue = "--"
        hrStatus = "--"
    }

    private fun openAppSettings(pkg: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = Uri.fromParts("package", pkg, null)
        startActivity(intent)
    }

    override fun onHeartRateChanged(status: Int, heartRateValue: Int) {
        runOnUiThread {
            hrValue = heartRateValue.toString()
            hrStatus = status.toString()
        }
    }

    override fun notifyTrackerError(errorResourceId: Int) {
        endMeasurement()
        runOnUiThread {
            if (errorResourceId == R.string.no_permission_message) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.no_permission_title)
                    .setMessage(R.string.no_permission_message)
                    .setPositiveButton("Settings") { _, _ -> openAppSettings(packageName) }
                    .setNegativeButton("Not now", null)
                    .create().show()
            }
            if (errorResourceId == R.string.sdk_policy_error) {
                Toast.makeText(this, R.string.sdk_policy_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val offBodyData = Math.round(event.values[0])
        if (offBodyData == OffBodyStatus.DEVICE_ON_BODY) {
            deviceWorn.set(true)
        }
        if (offBodyData == OffBodyStatus.DEVICE_OFF_BODY) {
            deviceWorn.set(false)
            if (isMeasurementRunning.get()) {
                endMeasurement()
                Toast.makeText(this, R.string.device_removed_during_measurement, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    private companion object {
        const val TAG = "MainActivity"
    }
}

@Composable
fun WearApp(
    hrValue: String,
    hrStatus: String,
    isMeasuring: Boolean,
    isReady: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onResetClick: () -> Unit,
    onLogoutClick: () -> Unit,
) {
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
                            text = "$hrValue bpm",
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.displayLarge,
                        )
                    }
                    item {
                        Text(
                            text = if (!isReady) "Connecting..." else "Status: $hrStatus",
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    item {
                        Button(
                            onClick = onStartClick,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = isReady && !isMeasuring,
                        ) {
                            Text("Start")
                        }
                    }
                    item {
                        Button(
                            onClick = onStopClick,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = isMeasuring,
                        ) {
                            Text("Stop")
                        }
                    }
                    item {
                        Button(
                            onClick = onResetClick,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Reset")
                        }
                    }
                }
            }
        }
    }
}
