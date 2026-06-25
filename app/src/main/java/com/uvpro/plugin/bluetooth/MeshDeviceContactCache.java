package com.uvpro.plugin.bluetooth;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Persists mesh node contact lists locally so the Contacts picker can open instantly.
 * Keyed by the connected radio's Bluetooth address; refreshed from the device in background.
 */
public final class MeshDeviceContactCache {

    private static final String PREF_SYNCED_AT_SUFFIX = "_synced_at_ms";
    private static final String PREF_CONTACTS_SUFFIX = "_json";

    private MeshDeviceContactCache() {
    }

    private static SharedPreferences prefs(Context context) {
        Context ctx = context;
        try {
            com.atakmap.android.maps.MapView mv =
                    com.atakmap.android.maps.MapView.getMapView();
            if (mv != null) {
                ctx = mv.getContext();
            }
        } catch (Exception ignored) {
        }
        return PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    @Nullable
    public static String normalizeDeviceAddress(@Nullable String address) {
        if (address == null || address.trim().isEmpty()) {
            return null;
        }
        return address.trim().toUpperCase(Locale.US);
    }

    private static String contactsKey(@NonNull String deviceAddress) {
        return "uvpro_device_contacts" + PREF_CONTACTS_SUFFIX + "_"
                + deviceAddress.replace(':', '_');
    }

    private static String syncedAtKey(@NonNull String deviceAddress) {
        return "uvpro_device_contacts" + PREF_SYNCED_AT_SUFFIX + "_"
                + deviceAddress.replace(':', '_');
    }

    public static long getSyncedAtMs(Context context, @Nullable String deviceAddress) {
        String addr = normalizeDeviceAddress(deviceAddress);
        if (addr == null) {
            return 0L;
        }
        return prefs(context).getLong(syncedAtKey(addr), 0L);
    }

    public static boolean isStale(Context context, @Nullable String deviceAddress, long maxAgeMs) {
        long syncedAt = getSyncedAtMs(context, deviceAddress);
        if (syncedAt <= 0L) {
            return true;
        }
        return System.currentTimeMillis() - syncedAt > maxAgeMs;
    }

    @NonNull
    public static List<MeshBtConnectionManager.MeshDeviceContact> load(
            Context context, @Nullable String deviceAddress) {
        String addr = normalizeDeviceAddress(deviceAddress);
        if (addr == null) {
            return new ArrayList<>();
        }
        String json = prefs(context).getString(contactsKey(addr), null);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        return decodeContacts(json);
    }

    public static void save(Context context, @Nullable String deviceAddress,
                            @NonNull List<MeshBtConnectionManager.MeshDeviceContact> contacts) {
        String addr = normalizeDeviceAddress(deviceAddress);
        if (addr == null) {
            return;
        }
        prefs(context).edit()
                .putString(contactsKey(addr), encodeContacts(contacts))
                .putLong(syncedAtKey(addr), System.currentTimeMillis())
                .apply();
    }

    public static void updateFavoriteFlag(Context context, @Nullable String deviceAddress,
                                          @Nullable String pubKeyHex, boolean favorite) {
        String addr = normalizeDeviceAddress(deviceAddress);
        if (addr == null || pubKeyHex == null || pubKeyHex.trim().isEmpty()) {
            return;
        }
        List<MeshBtConnectionManager.MeshDeviceContact> contacts = load(context, addr);
        if (contacts.isEmpty()) {
            return;
        }
        String key = pubKeyHex.trim().toUpperCase(Locale.US);
        List<MeshBtConnectionManager.MeshDeviceContact> updated = new ArrayList<>(contacts.size());
        boolean changed = false;
        for (MeshBtConnectionManager.MeshDeviceContact c : contacts) {
            if (c.pubKeyHex != null && c.pubKeyHex.toUpperCase(Locale.US).equals(key)) {
                int flags = favorite
                        ? (c.flags | MeshBtConnectionManager.CONTACT_FLAG_FAVORITE)
                        : (c.flags & ~MeshBtConnectionManager.CONTACT_FLAG_FAVORITE);
                updated.add(new MeshBtConnectionManager.MeshDeviceContact(
                        c.pubKeyHex, c.type, flags, c.outPathLen, c.name,
                        c.lastAdvertTimestamp, c.gpsLat, c.gpsLon, c.lastMod));
                changed = true;
            } else {
                updated.add(c);
            }
        }
        if (changed) {
            save(context, addr, updated);
        }
    }

    @NonNull
    private static String encodeContacts(
            @NonNull List<MeshBtConnectionManager.MeshDeviceContact> contacts) {
        JSONArray arr = new JSONArray();
        for (MeshBtConnectionManager.MeshDeviceContact c : contacts) {
            try {
                JSONObject o = new JSONObject();
                o.put("pubKeyHex", c.pubKeyHex);
                o.put("type", c.type);
                o.put("flags", c.flags);
                o.put("outPathLen", c.outPathLen);
                o.put("name", c.name);
                o.put("lastAdvertTimestamp", c.lastAdvertTimestamp);
                o.put("gpsLat", c.gpsLat);
                o.put("gpsLon", c.gpsLon);
                o.put("lastMod", c.lastMod);
                arr.put(o);
            } catch (JSONException ignored) {
            }
        }
        return arr.toString();
    }

    @NonNull
    private static List<MeshBtConnectionManager.MeshDeviceContact> decodeContacts(
            @NonNull String json) {
        List<MeshBtConnectionManager.MeshDeviceContact> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new MeshBtConnectionManager.MeshDeviceContact(
                        o.optString("pubKeyHex", ""),
                        o.optInt("type", 0),
                        o.optInt("flags", 0),
                        o.optInt("outPathLen", 0),
                        o.optString("name", ""),
                        o.optInt("lastAdvertTimestamp", 0),
                        o.optDouble("gpsLat", 0.0),
                        o.optDouble("gpsLon", 0.0),
                        o.optInt("lastMod", 0)));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }
}
