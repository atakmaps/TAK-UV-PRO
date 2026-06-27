package com.uvpro.plugin.bluetooth;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Persists guest/login passwords for room-server contacts, keyed by radio BT MAC + room pubkey.
 * Blank (read-only) passwords are stored with an internal marker so re-login does not re-prompt.
 */
public final class MeshRoomPasswordStore {

    private static final String PREFS = "uvpro_room_passwords_v1";
    /** Distinguishes stored blank password from missing entry. */
    private static final String BLANK_PASSWORD_MARKER = "\u0000";

    private MeshRoomPasswordStore() {
    }

    public static boolean hasPasswordStored(Context context, @Nullable String deviceAddress,
                                            @Nullable String pubKeyHex) {
        String key = storageKey(deviceAddress, pubKeyHex);
        if (key == null || context == null) {
            return false;
        }
        return prefs(context).contains(key);
    }

    @Nullable
    public static String getPassword(Context context, @Nullable String deviceAddress,
                                     @Nullable String pubKeyHex) {
        String key = storageKey(deviceAddress, pubKeyHex);
        if (key == null || context == null || !prefs(context).contains(key)) {
            return null;
        }
        String raw = prefs(context).getString(key, "");
        if (BLANK_PASSWORD_MARKER.equals(raw)) {
            return "";
        }
        return raw;
    }

    public static void savePassword(Context context, @Nullable String deviceAddress,
                                    @Nullable String pubKeyHex, @Nullable String password) {
        String key = storageKey(deviceAddress, pubKeyHex);
        if (key == null || context == null) {
            return;
        }
        SharedPreferences.Editor ed = prefs(context).edit();
        if (password == null) {
            ed.remove(key);
        } else if (password.isEmpty()) {
            ed.putString(key, BLANK_PASSWORD_MARKER);
        } else {
            ed.putString(key, password);
        }
        ed.apply();
    }

    @Nullable
    private static String storageKey(@Nullable String deviceAddress, @Nullable String pubKeyHex) {
        if (pubKeyHex == null || pubKeyHex.trim().length() < 12) {
            return null;
        }
        String addr = deviceAddress != null ? deviceAddress.trim().toUpperCase(Locale.US) : "UNKNOWN";
        String pub = pubKeyHex.trim().toUpperCase(Locale.US);
        return addr + "|" + pub;
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
