/*
 * Copyright 2023 Samsung Electronics Co., Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.samsung.health.hrtracker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.health.connect.HealthPermissions;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import com.samsung.health.hrtracker.databinding.ActivityHeartrateBinding;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class HeartRateActivity extends Activity implements TrackerObserver, SensorEventListener {
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean initialMeasurement = new AtomicBoolean(true);
    private final AtomicBoolean deviceWorn = new AtomicBoolean(false);
    private final AtomicBoolean isMeasurementRunning = new AtomicBoolean(false);
    private TrackerDataSubject trackerDataSubject = null;
    private boolean heartRateAvailable = false;
    private HeartRateListener heartRateListener = null;
    private ConnectionManager connectionManager = null;
    private SensorManager mSensorManager;
    private Sensor offBodySensor;
    private ActivityHeartrateBinding activityHeartRateBinding = null;
    private final ConnectionObserver connectionObserver = new ConnectionObserver() {
        @Override
        public void onConnectionResult(boolean isConnected) {
            connected.set(isConnected);
        }

        @Override
        public void onHeartRateAvailability(boolean isAvailable) {
            heartRateAvailable = isAvailable;
            if (isAvailable) {
                heartRateListener = new HeartRateListener();
                heartRateListener.setTrackerDataSubject(trackerDataSubject);
                connectionManager.initHeartRate(heartRateListener);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        activityHeartRateBinding = ActivityHeartrateBinding.inflate(getLayoutInflater());
        setContentView(activityHeartRateBinding.getRoot());

        String permission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            permission = HealthPermissions.READ_HEART_RATE;
        } else {
            permission = Manifest.permission.BODY_SENSORS;
        }

        if (checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED) {
            requestPermissions(new String[]{permission}, 0);
        }
        trackerDataSubject = new TrackerDataSubject();
        trackerDataSubject.addObserver(this);
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        offBodySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT);
        if (offBodySensor == null) {
            prepareAlertWindow(R.string.no_off_body_sensor_title, R.string.no_off_body_sensor_message).create().show();
        }
        createConnectionManager();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (offBodySensor != null) {
            mSensorManager.registerListener(HeartRateActivity.this,
                    offBodySensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (offBodySensor != null) {
            mSensorManager.unregisterListener(this);
        }
    }

    void createConnectionManager() {
        try {
            connectionManager = new ConnectionManager(connectionObserver);
            connectionManager.connect(this, getApplicationContext());
        } catch (Throwable t) {
            final String errMsg = t.getMessage() == null ? "Error in creating connection manager" : t.getMessage();
            Log.e(getString(R.string.app_name), errMsg);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (connected.get()) {
            connectionManager.disconnect();
        }
        trackerDataSubject.removeObserver(this);
    }

    public void onMeasurementButtonClick(View view) {
        if (isMeasurementRunning.get()) {
            endMeasurement();
        } else {
            startMeasurement();
        }
    }

    public void startMeasurement() {
        if (!connected.get()) {
            Toast.makeText(this, R.string.no_connection_message, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!heartRateAvailable) {
            prepareAlertWindow(R.string.no_heart_rate_available_title, R.string.no_heart_rate_available_message).create().show();
            return;
        }
        if (!deviceWorn.get()) {
            Toast.makeText(this, R.string.device_not_worn, Toast.LENGTH_SHORT).show();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        runOnUiThread(() -> {
            activityHeartRateBinding.txtHeartRateBPMValue.setText(R.string.status_default_value);
            activityHeartRateBinding.txtHeartRateStatusValue.setText(R.string.heart_rate_default_value);
            activityHeartRateBinding.butStart.setText(R.string.button_stop);
            activityHeartRateBinding.pgMeasurement.setVisibility(View.VISIBLE);
        });
        initialMeasurement.set(true);
        heartRateListener.startTracker();
        isMeasurementRunning.set(true);
    }

    @Override
    public void onHeartRateChanged(int status, int heartRateValue) {
        runOnUiThread(() -> {
            if (initialMeasurement.get()) {
                activityHeartRateBinding.pgMeasurement.setVisibility(View.INVISIBLE);
                initialMeasurement.set(false);
            }
            activityHeartRateBinding.txtHeartRateBPMValue.setText(
                    String.format(Locale.getDefault(), "%d", heartRateValue));
            activityHeartRateBinding.txtHeartRateStatusValue.setText(
                    String.format(Locale.getDefault(), "%d", status));
        });
    }

    void endMeasurement() {
        if (heartRateListener != null) {
            heartRateListener.stopTracker();
            isMeasurementRunning.set(false);
            runOnUiThread(() -> {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                activityHeartRateBinding.pgMeasurement.setVisibility(View.INVISIBLE);
                activityHeartRateBinding.butStart.setText(R.string.button_start);
            });
        }
    }

    @Override
    public void notifyTrackerError(int errorResourceId) {
        endMeasurement();
        runOnUiThread(() -> {
            if (errorResourceId == R.string.no_permission_message) {
                final AlertDialog.Builder alertBuilder = prepareAlertWindow(R.string.no_permission_title, R.string.no_permission_message);
                alertBuilder.setPositiveButton("Settings", (dialog, which) -> openAppSettings(getPackageName()));
                alertBuilder.setNegativeButton("Not now", null);
                final AlertDialog alertDialog = alertBuilder.create();
                alertDialog.show();
            }
            if (errorResourceId == R.string.sdk_policy_error) {
                Toast.makeText(this, R.string.sdk_policy_error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        final float offBodyDataFloat = sensorEvent.values[0];
        final int offBodyData = Math.round(offBodyDataFloat);
        if (offBodyData == OffBodyStatus.DEVICE_ON_BODY) {
            deviceWorn.set(true);
        }
        if (offBodyData == OffBodyStatus.DEVICE_OFF_BODY) {
            deviceWorn.set(false);
            if (isMeasurementRunning.get()) {
                endMeasurement();
                Toast.makeText(this, R.string.device_removed_during_measurement, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    void openAppSettings(String packageName) {
        final Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        final Uri uri = Uri.fromParts("package", packageName, null);
        intent.setData(uri);
        startActivity(intent);
    }

    AlertDialog.Builder prepareAlertWindow(int titleResourceId, int messageResourceId) {
        final AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);
        alertBuilder.setMessage(messageResourceId);
        alertBuilder.setTitle(titleResourceId);
        return alertBuilder;
    }
}
