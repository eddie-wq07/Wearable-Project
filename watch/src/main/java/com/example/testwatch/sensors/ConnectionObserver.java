package com.example.testwatch.sensors;

/** Callback interface for SDK connection state changes. */

public interface ConnectionObserver {
    void onConnectionResult(boolean isConnected);
    void onHeartRateAvailability(boolean isAvailable);
}
