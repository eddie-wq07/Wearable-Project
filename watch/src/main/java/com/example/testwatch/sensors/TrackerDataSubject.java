package com.example.testwatch.sensors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TrackerDataSubject {

    private final List<TrackerObserver> trackerObservers = Collections.synchronizedList(new ArrayList<>());

    public void addObserver(TrackerObserver observer) {
        trackerObservers.add(observer);
    }

    public void removeObserver(TrackerObserver observer) {
        trackerObservers.remove(observer);
    }

    public void notifyHeartRateTrackerObservers(long timestampMs, int status, int heartRateValue) {
        trackerObservers.forEach(observer -> observer.onHeartRateChanged(timestampMs, status, heartRateValue));
    }

    public void notifyError(int errorResourceId) {
        trackerObservers.forEach(observer -> observer.notifyTrackerError(errorResourceId));
    }
}
