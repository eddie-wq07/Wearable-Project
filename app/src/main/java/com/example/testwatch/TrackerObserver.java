package com.example.testwatch;

public interface TrackerObserver {
    void onHeartRateChanged(int status, int heartRateValue);
    void notifyTrackerError(int errorResourceId);
}
