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

import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;

import com.samsung.android.service.health.tracking.HealthTracker;
import com.samsung.android.service.health.tracking.HealthTrackingService;
import com.samsung.android.service.health.tracking.data.DataPoint;
import com.samsung.android.service.health.tracking.data.HealthTrackerType;
import com.samsung.android.service.health.tracking.data.ValueKey;

import java.util.List;

public class HeartRateListener {
    private final String TAG = "HeartRateListener";
    private TrackerDataSubject trackerDataSubject;
    private final HealthTracker.TrackerEventListener heartRateListener = new HealthTracker.TrackerEventListener() {
        @Override
        public void onDataReceived(@NonNull List<DataPoint> list) {
            for (DataPoint data : list) {
                updateHeartRate(data);
            }
        }

        @Override
        public void onFlushCompleted() {
            Log.i(TAG, "Flush completed");
        }

        @Override
        public void onError(HealthTracker.TrackerError trackerError) {
            Log.i(TAG, "Heart Rate Tracker error: " + trackerError.toString());
            if (trackerError == HealthTracker.TrackerError.PERMISSION_ERROR) {
                trackerDataSubject.notifyError(R.string.no_permission_message);
            }
            if (trackerError == HealthTracker.TrackerError.SDK_POLICY_ERROR) {
                trackerDataSubject.notifyError(R.string.sdk_policy_error);
            }
        }
    };
    private Handler heartRateHandler;
    private boolean isHandlerRunning = false;
    private HealthTracker heartRateTracker;

    void setTrackerDataSubject(TrackerDataSubject trackerDataSubject) {
        this.trackerDataSubject = trackerDataSubject;
    }

    void setHeartRateTracker(HealthTrackingService healthTrackingService) {
        heartRateTracker = healthTrackingService.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS);
    }

    void setHeartRateHandler(Handler handler) {
        heartRateHandler = handler;
    }

    void startTracker() {
        if (!isHandlerRunning) {
            heartRateHandler.post(() -> heartRateTracker.setEventListener(heartRateListener));
            isHandlerRunning = true;
        }
    }

    void stopTracker() {
        if (heartRateTracker != null) {
            heartRateTracker.unsetEventListener();
        }
        heartRateHandler.removeCallbacksAndMessages(null);
        isHandlerRunning = false;
    }

    void updateHeartRate(DataPoint data) {
        final int status = data.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS);
        int heartRateValue = data.getValue(ValueKey.HeartRateSet.HEART_RATE);
        trackerDataSubject.notifyHeartRateTrackerObservers(status, heartRateValue);
    }
}
