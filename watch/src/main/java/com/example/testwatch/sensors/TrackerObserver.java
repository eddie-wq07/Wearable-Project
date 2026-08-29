package com.example.testwatch.sensors;

/** Observer interface paired with TrackerDataSubject — part of the legacy HR lane only. */

public interface TrackerObserver {
    void onHeartRateChanged(long timestampMs, int status, int heartRateValue);
    void notifyTrackerError(int errorResourceId);
}
