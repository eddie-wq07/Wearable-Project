package com.example.testwatch.sensors;

public interface ConnectionObserver {
    void onConnectionResult(boolean isConnected);
    void onHeartRateAvailability(boolean isAvailable);
}
