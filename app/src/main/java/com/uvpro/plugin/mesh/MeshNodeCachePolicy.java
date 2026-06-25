package com.uvpro.plugin.mesh;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.uvpro.plugin.UVProContactHandler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;

/** Rolling cap and TTL rules for persisted mesh node / repeater map cache rows. */
public final class MeshNodeCachePolicy {

    public static final int ROLLING_MAX = 75;
    public static final String JSON_FAVORITE = "favorite";

    private MeshNodeCachePolicy() {
    }

    public static boolean isProtected(@Nullable JSONObject row, @Nullable String pubKeyHex,
                                      @Nullable Context context) {
        if (row != null && row.optBoolean(JSON_FAVORITE, false)) {
            return true;
        }
        return UVProContactHandler.isMeshFavoriteByPubKey(pubKeyHex);
    }

    public static boolean shouldEvictByNodeTtl(@Nullable JSONObject row, @Nullable String pubKeyHex,
                                               long nowMs, long ttlMs,
                                               @Nullable Context context) {
        if (isProtected(row, pubKeyHex, context)) {
            return false;
        }
        if (row == null) {
            return true;
        }
        long lastSeenMs = row.optLong("lastSeenMs", row.optLong("firstSeenMs", 0L));
        return lastSeenMs > 0L && (nowMs - lastSeenMs) > ttlMs;
    }

    public static boolean shouldEvictRepeaterByTtl(@Nullable JSONObject row,
                                                   @Nullable String pubKeyHex,
                                                   long nowMs, long ttlMs,
                                                   @Nullable Context context) {
        if (isProtected(row, pubKeyHex, context)) {
            return false;
        }
        if (row == null) {
            return true;
        }
        long firstSeenMs = row.optLong("firstSeenMs", 0L);
        return firstSeenMs > 0L && (nowMs - firstSeenMs) > ttlMs;
    }

    @Nullable
    public static String findOldestEvictableKey(@NonNull Map<String, JSONObject> byKey,
                                                @Nullable Context context) {
        String oldestKey = null;
        long oldestSeenMs = Long.MAX_VALUE;
        for (Map.Entry<String, JSONObject> entry : byKey.entrySet()) {
            JSONObject candidate = entry.getValue();
            if (isProtected(candidate, entry.getKey(), context)) {
                continue;
            }
            long seenMs = candidate.optLong("lastSeenMs",
                    candidate.optLong("firstSeenMs", 0L));
            if (seenMs <= 0L) {
                seenMs = Long.MIN_VALUE;
            }
            if (seenMs < oldestSeenMs) {
                oldestSeenMs = seenMs;
                oldestKey = entry.getKey();
            }
        }
        return oldestKey;
    }

    public static void trimToRollingMax(@NonNull Map<String, JSONObject> byKey,
                                        @Nullable Context context) {
        while (byKey.size() > ROLLING_MAX) {
            String key = findOldestEvictableKey(byKey, context);
            if (key == null) {
                break;
            }
            byKey.remove(key);
        }
    }

    public static void markFavorite(@Nullable Context context, @NonNull String prefKey,
                                    @Nullable String pubKeyHex, boolean favorite) {
        if (context == null || pubKeyHex == null || pubKeyHex.trim().isEmpty()) {
            return;
        }
        String key = pubKeyHex.trim().toUpperCase(Locale.US);
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            String raw = prefs.getString(prefKey, "[]");
            JSONArray arr = new JSONArray(raw != null ? raw : "[]");
            JSONArray out = new JSONArray();
            boolean updated = false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String rowKey = o.optString("pubKeyHex", "").trim().toUpperCase(Locale.US);
                if (pubKeysMatch(key, rowKey)) {
                    o.put(JSON_FAVORITE, favorite);
                    updated = true;
                }
                out.put(o);
            }
            if (updated) {
                prefs.edit().putString(prefKey, out.toString()).apply();
            }
        } catch (JSONException ignored) {
        }
    }

    private static boolean pubKeysMatch(@NonNull String a, @NonNull String b) {
        if (a.equals(b)) {
            return true;
        }
        if (a.length() >= 12 && b.length() >= 12) {
            return a.regionMatches(0, b, 0, 12);
        }
        return false;
    }
}
