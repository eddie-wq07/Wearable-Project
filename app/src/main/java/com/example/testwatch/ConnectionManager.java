package com.example.testwatch;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.samsung.android.service.health.tracking.ConnectionListener;
import com.samsung.android.service.health.tracking.HealthTrackerException;
import com.samsung.android.service.health.tracking.HealthTrackingService;
import com.samsung.android.service.health.tracking.data.HealthTrackerType;

import java.util.List;

public class ConnectionManager {

    private final String TAG = "ConnectionManager";
    private final ConnectionObserver connectionObserver;
    private HealthTrackingService healthTrackingService = null;
    private Activity callingActivity = null;
    private final ConnectionListener connectionListener = new ConnectionListener() {
        @Override
        public void onConnectionSuccess() {
            Log.i(TAG, "Connected");
            final boolean availability = isHeartTrackingAvailable(healthTrackingService);
            connectionObserver.onHeartRateAvailability(availability);
            connectionObserver.onConnectionResult(true);
        }

        @Override
        public void onConnectionEnded() {
            Log.i(TAG, "Disconnected");
        }

        @Override
        public void onConnectionFailed(HealthTrackerException e) {
            if (e.hasResolution() && callingActivity != null) {
                e.resolve(callingActivity);
            }
            connectionObserver.onConnectionResult(false);
            final String errMsg = e.getMessage() == null ? "" : e.getMessage();
            Log.i(TAG, "Could not connect to Health Tracking Service: " + errMsg);
        }
    };

    public ConnectionManager(ConnectionObserver connectionObserver) {
        this.connectionObserver = connectionObserver;
    }

    public void connect(Activity activity, Context context) {
        callingActivity = activity;
        healthTrackingService = new HealthTrackingService(connectionListener, context);
        healthTrackingService.connectService();
    }

    public void disconnect() {
        if (healthTrackingService != null) {
            healthTrackingService.disconnectService();
        }
    }

    boolean isHeartTrackingAvailable(HealthTrackingService healthTrackingService) {
        if (healthTrackingService == null) {
            return false;
        }
        final List<HealthTrackerType> availableTrackers = healthTrackingService
                .getTrackingCapability()
                .getSupportHealthTrackerTypes();
        return availableTrackers.contains(HealthTrackerType.HEART_RATE_CONTINUOUS);
    }

    public HealthTrackingService getTrackingService() {
        return healthTrackingService;
    }

    public void initHeartRate(HeartRateListener heartRateListener) {
        heartRateListener.setHeartRateTracker(healthTrackingService);
        heartRateListener.setHeartRateHandler(new Handler(Looper.getMainLooper()));
    }
}
