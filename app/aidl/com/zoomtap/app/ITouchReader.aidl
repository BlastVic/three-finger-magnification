package com.zoomtap.app;

import com.zoomtap.app.ITouchCallback;

interface ITouchReader {
    int start(String nodeHint, String language, ITouchCallback callback);
    void stop();
    int status();
    String probe();
}
