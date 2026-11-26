package com.laert.qrscanner;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * While the camera stays on a code it reads it dozens of times a second. This lets a code count
 * once, and count again only after it has been out of view for gapMs. Tracks every recently seen
 * code, so two codes in view at the same time can't take turns and inflate each other's count.
 */
final class ScanDebouncer {

    private final long gapMs;
    private final Map<String, Long> lastSeen = new HashMap<>();

    ScanDebouncer(long gapMs) {
        this.gapMs = gapMs;
    }

    /** @return true if this sighting should be counted. */
    synchronized boolean shouldAccept(String content, long nowMillis) {
        Iterator<Map.Entry<String, Long>> it = lastSeen.entrySet().iterator();
        while (it.hasNext()) {
            if (nowMillis - it.next().getValue() >= gapMs) it.remove();
        }
        boolean recentlySeen = lastSeen.containsKey(content);
        // Updated even when ignored: a code that stays in view never expires.
        lastSeen.put(content, nowMillis);
        return !recentlySeen;
    }

    synchronized void reset() {
        lastSeen.clear();
    }
}
