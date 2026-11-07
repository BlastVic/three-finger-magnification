package com.zoomtap.app;

interface ITouchCallback {
    void onTripleDoubleTap(long id, float normalizedX, float normalizedY, int peak);
    void onLog(String message);
}
