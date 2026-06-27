package com.uvpro.plugin.bluetooth;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Persists the last active MeshCore chat tab (room or channel) per companion radio.
 */
public final class MeshLastChatStore {

    public static final int TYPE_NONE = 0;
    public static final int TYPE_ROOM = 1;
    public static final int TYPE_CHANNEL = 2;

    private static final String PREFS = "uvpro_mesh_last_chat_v1";
    private static final String KEY_TYPE_PREFIX = "last_chat_type_";
    private static final String KEY_ROOM_PREFIX = "last_chat_room_";
    private static final String KEY_CHANNEL_PREFIX = "last_chat_channel_";

    public static final class Snapshot {
        public final int type;
        @Nullable
        public final String roomPubKeyHex;
        public final int channelIndex;

        public Snapshot(int type, @Nullable String roomPubKeyHex, int channelIndex) {
            this.type = type;
            this.roomPubKeyHex = roomPubKeyHex;
            this.channelIndex = channelIndex;
        }
    }

    private MeshLastChatStore() {
    }

    public static void saveRoom(Context context, @Nullable String deviceAddress,
                                @Nullable String pubKeyHex) {
        String addr = normalizeDeviceAddress(deviceAddress);
        String pubKey = normalizePubKeyHex(pubKeyHex);
        if (context == null || addr == null || pubKey == null) {
            return;
        }
        String suffix = addr.replace(':', '_');
        prefs(context).edit()
                .putInt(KEY_TYPE_PREFIX + suffix, TYPE_ROOM)
                .putString(KEY_ROOM_PREFIX + suffix, pubKey)
                .remove(KEY_CHANNEL_PREFIX + suffix)
                .apply();
    }

    public static void saveChannel(Context context, @Nullable String deviceAddress, int channelIndex) {
        String addr = normalizeDeviceAddress(deviceAddress);
        if (context == null || addr == null || channelIndex < 0 || channelIndex > 7) {
            return;
        }
        String suffix = addr.replace(':', '_');
        prefs(context).edit()
                .putInt(KEY_TYPE_PREFIX + suffix, TYPE_CHANNEL)
                .putInt(KEY_CHANNEL_PREFIX + suffix, channelIndex)
                .remove(KEY_ROOM_PREFIX + suffix)
                .apply();
    }

    @Nullable
    public static Snapshot load(Context context, @Nullable String deviceAddress) {
        String addr = normalizeDeviceAddress(deviceAddress);
        if (context == null || addr == null) {
            return null;
        }
        String suffix = addr.replace(':', '_');
        SharedPreferences p = prefs(context);
        int type = p.getInt(KEY_TYPE_PREFIX + suffix, TYPE_NONE);
        if (type == TYPE_ROOM) {
            String pubKey = normalizePubKeyHex(p.getString(KEY_ROOM_PREFIX + suffix, null));
            if (pubKey == null) {
                return null;
            }
            return new Snapshot(TYPE_ROOM, pubKey, -1);
        }
        if (type == TYPE_CHANNEL) {
            if (!p.contains(KEY_CHANNEL_PREFIX + suffix)) {
                return null;
            }
            int channel = p.getInt(KEY_CHANNEL_PREFIX + suffix, -1);
            if (channel < 0 || channel > 7) {
                return null;
            }
            return new Snapshot(TYPE_CHANNEL, null, channel);
        }
        return null;
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
