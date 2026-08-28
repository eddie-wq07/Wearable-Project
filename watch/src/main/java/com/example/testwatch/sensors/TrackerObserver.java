package com.example.testwatch.sensors;

public interface TrackerObserver {
    void onHeartRateChanged(long timestampMs, int status, int heartRateValue);
    void notifyTrackerError(int errorResourceId);
}
