package com.uvpro.plugin.bluetooth;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Persists room-server joins keyed by companion radio BT MAC so tabs survive ATAK restarts.
 */
public final class MeshJoinedRoomStore {

    private static final String PREFS = "uvpro_joined_rooms_v1";
    private static final String KEY_LIST_PREFIX = "joined_rooms_";
    private static final String KEY_ACTIVE_PREFIX = "active_room_";

    private MeshJoinedRoomStore() {
    }

    public static final class JoinedRoom {
        public final String pubKeyHex;
        public final String displayName;
        public final long joinedAtMs;

        public JoinedRoom(String pubKeyHex, String displayName, long joinedAtMs) {
            this.pubKeyHex = pubKeyHex;
            this.displayName = displayName;
            this.joinedAtMs = joinedAtMs;
        }
    }

    public static void saveJoinedRoom(Context context, @Nullable String deviceAddress,
                                      @Nullable String pubKeyHex, @Nullable String displayName) {
        String addr = normalizeDeviceAddress(deviceAddress);
        String pubKey = normalizePubKeyHex(pubKeyHex);
        if (context == null || addr == null || pubKey == null) {
            return;
        }
        List<JoinedRoom> rooms = loadJoinedRooms(context, addr);
        String name = displayName != null && !displayName.trim().isEmpty()
                ? displayName.trim() : pubKey.substring(0, 12);
        boolean replaced = false;
        for (int i = 0; i < rooms.size(); i++) {
            JoinedRoom existing = rooms.get(i);
            if (pubKey.equalsIgnoreCase(existing.pubKeyHex)) {
                rooms.set(i, new JoinedRoom(pubKey, name, existing.joinedAtMs));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            rooms.add(new JoinedRoom(pubKey, name, System.currentTimeMillis()));
        }
        writeRooms(context, addr, rooms);
        saveActiveRoom(context, addr, pubKey);
    }

    @NonNull
    public static List<JoinedRoom> loadJoinedRooms(Context context, @Nullable String deviceAddress) {
        String addr = normalizeDeviceAddress(deviceAddress);
        if (context == null || addr == null) {
            return new ArrayList<>();
        }
        String json = prefs(context).getString(KEY_LIST_PREFIX + addr.replace(':', '_'), null);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        List<JoinedRoom> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String pubKey = normalizePubKeyHex(o.optString("pubKeyHex", null));
                if (pubKey == null) {
                    continue;
                }
                String name = o.optString("displayName", pubKey.substring(0, 12));
                long joinedAt = o.optLong("joinedAtMs", 0L);
                out.add(new JoinedRoom(pubKey, name, joinedAt));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public static void saveActiveRoom(Context context, @Nullable String deviceAddress,
                                      @Nullable String pubKeyHex) {
        String addr = normalizeDeviceAddress(deviceAddress);
        String pubKey = normalizePubKeyHex(pubKeyHex);
        if (context == null || addr == null) {
            return;
        }
        SharedPreferences.Editor ed = prefs(context).edit();
        String key = KEY_ACTIVE_PREFIX + addr.replace(':', '_');
        if (pubKey == null) {
            ed.remove(key);
        } else {
            ed.putString(key, pubKey);
        }
        ed.apply();
    }

    public static void removeJoinedRoom(Context context, @Nullable String deviceAddress,
                                        @Nullable String pubKeyHex) {
        String addr = normalizeDeviceAddress(deviceAddress);
        String pubKey = normalizePubKeyHex(pubKeyHex);
        if (context == null || addr == null || pubKey == null) {
            return;
        }
        List<JoinedRoom> rooms = loadJoinedRooms(context, addr);
        boolean removed = false;
        for (int i = rooms.size() - 1; i >= 0; i--) {
            if (pubKey.equalsIgnoreCase(rooms.get(i).pubKeyHex)) {
                rooms.remove(i);
                removed = true;
                break;
            }
        }
        if (!removed) {
            return;
        }
        writeRooms(context, addr, rooms);
        String active = getActiveRoomPubKey(context, addr);
        if (active != null && pubKey.equalsIgnoreCase(active)) {
            saveActiveRoom(context, addr, null);
        }
    }

    @Nullable
    public static String getActiveRoomPubKey(Context context, @Nullable String deviceAddress) {
        String addr = normalizeDeviceAddress(deviceAddress);
        if (context == null || addr == null) {
            return null;
        }
        return normalizePubKeyHex(prefs(context).getString(
                KEY_ACTIVE_PREFIX + addr.replace(':', '_'), null));
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

    private static void writeRooms(Context context, @NonNull String deviceAddress,
                                   @NonNull List<JoinedRoom> rooms) {
        JSONArray arr = new JSONArray();
        for (JoinedRoom room : rooms) {
            try {
                JSONObject o = new JSONObject();
                o.put("pubKeyHex", room.pubKeyHex);
                o.put("displayName", room.displayName);
                o.put("joinedAtMs", room.joinedAtMs);
                arr.put(o);
            } catch (JSONException ignored) {
            }
        }
        prefs(context).edit()
                .putString(KEY_LIST_PREFIX + deviceAddress.replace(':', '_'), arr.toString())
                .apply();
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
