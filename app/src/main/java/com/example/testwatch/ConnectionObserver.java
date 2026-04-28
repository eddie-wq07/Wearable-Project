package com.example.testwatch;

public interface ConnectionObserver {
    void onConnectionResult(boolean isConnected);
    void onHeartRateAvailability(boolean isAvailable);
}
