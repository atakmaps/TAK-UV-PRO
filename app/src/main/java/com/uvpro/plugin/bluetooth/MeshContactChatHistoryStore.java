package com.uvpro.plugin.bluetooth;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * Persists room-server chat lines on the phone so history survives ATAK restarts.
 * Capped to {@link #MAX_STORED_LINES} (oldest purged first). Ephemeral status lines
 * (login placeholders) are not stored.
 */
public final class MeshContactChatHistoryStore {

    public static final int MAX_STORED_LINES = 120;

    private static final String PREFS = "uvpro_room_chat_history_v1";
    private static final String KEY_PREFIX = "room_lines_";

    private MeshContactChatHistoryStore() {
    }

    public static void save(Context context, @Nullable String deviceAddress,
                            @Nullable String roomPubKeyHex,
                            @NonNull List<String> lines) {
        String addr = normalizeDeviceAddress(deviceAddress);
        String pubKey = normalizePubKeyHex(roomPubKeyHex);
        if (context == null || addr == null || pubKey == null) {
            return;
        }
        JSONArray arr = new JSONArray();
        int start = Math.max(0, lines.size() - MAX_STORED_LINES);
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || line.trim().isEmpty() || isEphemeralLine(line)) {
                continue;
            }
            arr.put(line);
        }
        prefs(context).edit()
                .putString(storageKey(addr, pubKey), arr.toString())
                .apply();
    }

    @NonNull
    public static LinkedList<String> load(Context context, @Nullable String deviceAddress,
                                            @Nullable String roomPubKeyHex) {
        LinkedList<String> out = new LinkedList<>();
        String addr = normalizeDeviceAddress(deviceAddress);
        String pubKey = normalizePubKeyHex(roomPubKeyHex);
        if (context == null || addr == null || pubKey == null) {
            return out;
        }
        String json = prefs(context).getString(storageKey(addr, pubKey), null);
        if (json == null || json.isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                String line = arr.optString(i, null);
                if (line != null && !line.trim().isEmpty() && !isEphemeralLine(line)) {
                    out.add(line);
                }
            }
        } catch (JSONException ignored) {
        }
        while (out.size() > MAX_STORED_LINES) {
            out.removeFirst();
        }
        return out;
    }

    public static void clear(Context context, @Nullable String deviceAddress,
                             @Nullable String roomPubKeyHex) {
        String addr = normalizeDeviceAddress(deviceAddress);
        String pubKey = normalizePubKeyHex(roomPubKeyHex);
        if (context == null || addr == null || pubKey == null) {
            return;
        }
        prefs(context).edit().remove(storageKey(addr, pubKey)).apply();
    }

    /** Status/placeholder lines shown during login sync — not durable history. */
    public static boolean isEphemeralLine(@Nullable String storedLine) {
        if (storedLine == null) {
            return true;
        }
        int pipe = storedLine.indexOf('|');
        String display = pipe >= 0 ? storedLine.substring(pipe + 1) : storedLine;
        return display.contains("(Login")
                || display.contains("(No posts")
                || display.contains("(Retrying")
                || display.contains("syncing posts")
                || display.contains("Login complete")
                || display.contains("Logged in.")
                || display.contains("retrieving posts")
                || display.contains("retrieving full history");
    }

    @NonNull
    private static String storageKey(@NonNull String deviceAddress, @NonNull String pubKeyHex) {
        return KEY_PREFIX + deviceAddress.replace(':', '_') + "_" + pubKeyHex;
    }

    @Nullable
    private static String normalizeDeviceAddress(@Nullable String address) {
        if (address == null || address.trim().isEmpty()) {
            return null;
        }
        return address.trim().toUpperCase(Locale.US);
    }

    @Nullable
    private static String normalizePubKeyHex(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String hex = raw.trim().replace(" ", "").toLowerCase(Locale.US);
        return hex.length() == 64 ? hex : null;
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
