package com.laert.qrscanner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BatchList {

    static final class Entry {
        final String content;
        final String format;
        final long firstScannedMillis;
        int count = 1;

        Entry(String content, String format, long firstScannedMillis) {
            this.content = content;
            this.format = format;
            this.firstScannedMillis = firstScannedMillis;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    boolean add(String content, String format, long nowMillis) {
        Entry existing = entries.get(content);
        if (existing != null) {
            existing.count++;
            return false;
        }
        entries.put(content, new Entry(content, format, nowMillis));
        return true;
    }
    List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }

    boolean remove(String content) {
        return entries.remove(content) != null;
    }

    void clear() {
        entries.clear();
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    int uniqueCount() {
        return entries.size();
    }

    int totalCount() {
        int total = 0;
        for (Entry e : entries.values()) total += e.count;
        return total;
    }
}
