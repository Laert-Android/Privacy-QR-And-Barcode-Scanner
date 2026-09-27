package com.laert.qrscanner;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryManager {

    private static final String PREFS_NAME = "scan_history";
    private static final String KEY_HISTORY = "history";
    private static final int MAX_HISTORY = 50;

    public static void save(Context context, String content, String format) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_HISTORY, "[]");
        try {
            JSONArray array = new JSONArray(json);
            JSONObject item = new JSONObject();
            item.put("id", java.util.UUID.randomUUID().toString());
            item.put("content", content);
            item.put("format", format);
            item.put("time", new SimpleDateFormat("dd MMM yyyy HH:mm",
                    Locale.getDefault()).format(new Date()));
            array.put(item);
            if (array.length() > MAX_HISTORY) {
                JSONArray trimmed = new JSONArray();
                for (int i = array.length() - MAX_HISTORY; i < array.length(); i++) {
                    trimmed.put(array.get(i));
                }
                array = trimmed;
            }
            prefs.edit().putString(KEY_HISTORY, array.toString()).apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public static List<JSONObject> load(Context context) {
        List<JSONObject> list = new ArrayList<>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_HISTORY, "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int i = array.length() - 1; i >= 0; i--) {
                list.add(array.getJSONObject(i));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return list;
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_HISTORY).apply();
    }
}