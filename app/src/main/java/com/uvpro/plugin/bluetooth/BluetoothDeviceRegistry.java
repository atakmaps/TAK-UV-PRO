package com.uvpro.plugin.bluetooth;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Persists Bluetooth radios the user has connected to: display name, favorite,
 * and connection history. Stored in ATAK default SharedPreferences as JSON.
 */
public final class BluetoothDeviceRegistry {

    public static final String PREF_BT_DEVICES_JSON = "uvpro_bt_devices_json";
    public static final String PREF_BT_CONNECT_TARGET = "uvpro_bt_connect_target";

    private static final int MAX_DEVICES = 128;

    private BluetoothDeviceRegistry() {
    }

    public static final class BtDeviceRecord {
        @NonNull
        public final String address;
        @Nullable
        public String customName;
        public boolean favorite;
        public long lastConnectedMs;
        @Nullable
        public String lastSystemName;

        BtDeviceRecord(@NonNull String address) {
            this.address = normalizeAddress(address);
        }
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

    @NonNull
    public static String normalizeAddress(@NonNull String mac) {
        return mac.trim().toUpperCase(Locale.US);
    }

    /** Record or update entry after a successful connection. */
    public static void recordConnection(Context context, BluetoothDevice device) {
        if (device == null) return;
        String addr = normalizeAddress(device.getAddress());
        synchronized (BluetoothDeviceRegistry.class) {
            List<BtDeviceRecord> list = loadAll(context);
            BtDeviceRecord found = findByAddress(list, addr);
            if (found == null) {
                found = new BtDeviceRecord(addr);
                list.add(found);
            }
            found.lastConnectedMs = System.currentTimeMillis();
            String nm = device.getName();
            found.lastSystemName = nm != null ? nm.trim() : null;
            trimOverflow(list);
            saveAll(context, list);
        }
    }

    @NonNull
    public static List<BtDeviceRecord> getAllSortedForDisplay(Context context) {
        List<BtDeviceRecord> copy = loadAll(context);
        Collections.sort(copy, DISPLAY_ORDER);
        return copy;
    }

    public static boolean hasFavorites(Context context) {
        for (BtDeviceRecord r : loadAll(context)) {
            if (r.favorite) return true;
        }
        return false;
    }

    /**
     * Favorite devices first (same relative order), then chronological by lastConnectedMs.
     */
    @NonNull
    public static List<BtDeviceRecord> getFavoritesSorted(Context context) {
        List<BtDeviceRecord> fav = new ArrayList<>();
        for (BtDeviceRecord r : loadAll(context)) {
            if (r.favorite) {
                fav.add(r);
            }
        }
        Collections.sort(fav, BY_LAST_CONNECTED_DESC);
        return fav;
    }

    @Nullable
    public static BtDeviceRecord find(Context context, @NonNull String address) {
        return findByAddress(loadAll(context), normalizeAddress(address));
    }

    public static void setFavorite(Context context,
                                   @NonNull String address,
                                   boolean favorite) {
        synchronized (BluetoothDeviceRegistry.class) {
            List<BtDeviceRecord> list = loadAll(context);
            BtDeviceRecord r = findByAddress(list, normalizeAddress(address));
            if (r == null) return;
            r.favorite = favorite;
            saveAll(context, list);
        }
    }

    public static void setCustomName(Context context,
                                     @NonNull String address,
                                     @Nullable String customName) {
        synchronized (BluetoothDeviceRegistry.class) {
            List<BtDeviceRecord> list = loadAll(context);
            BtDeviceRecord r = findByAddress(list, normalizeAddress(address));
            if (r == null) return;
            if (customName != null) {
                customName = customName.trim();
                if (customName.isEmpty()) {
                    customName = null;
                }
            }
            r.customName = customName;
            saveAll(context, list);
        }
    }

    public static void remove(Context context, @NonNull String address) {
        String norm = normalizeAddress(address);
        synchronized (BluetoothDeviceRegistry.class) {
            List<BtDeviceRecord> list = loadAll(context);
            for (Iterator<BtDeviceRecord> it = list.iterator(); it.hasNext(); ) {
                if (norm.equals(it.next().address)) {
                    it.remove();
                    break;
                }
            }
            saveAll(context, list);
            String tgt = getConnectTargetAddress(context);
            if (tgt != null && norm.equals(normalizeAddress(tgt))) {
                setConnectTargetAddress(context, "");
            }
        }
    }

    /** Display title: custom name, else last system name, else address. */
    @NonNull
    public static String getDisplayTitle(@NonNull BtDeviceRecord r) {
        if (r.customName != null && !r.customName.isEmpty()) {
            return r.customName;
        }
        if (r.lastSystemName != null && !r.lastSystemName.isEmpty()) {
            return r.lastSystemName;
        }
        return r.address;
    }

    @Nullable
    public static String getConnectTargetAddress(Context context) {
        String s = prefs(context).getString(PREF_BT_CONNECT_TARGET, "").trim();
        return s.isEmpty() ? null : normalizeAddress(s);
    }

    public static void setConnectTargetAddress(Context context,
                                               @Nullable String macOrNull) {
        SharedPreferences.Editor ed = prefs(context).edit();
        if (macOrNull == null || macOrNull.trim().isEmpty()) {
            ed.putString(PREF_BT_CONNECT_TARGET, "");
        } else {
            ed.putString(PREF_BT_CONNECT_TARGET, normalizeAddress(macOrNull));
        }
        ed.apply();
    }

    private static void trimOverflow(List<BtDeviceRecord> list) {
        if (list.size() <= MAX_DEVICES) return;
        Collections.sort(list, BY_LAST_CONNECTED_DESC);
        while (list.size() > MAX_DEVICES) {
            list.remove(list.size() - 1);
        }
    }

    private static BtDeviceRecord findByAddress(List<BtDeviceRecord> list, String addr) {
        for (BtDeviceRecord r : list) {
            if (addr.equals(r.address)) return r;
        }
        return null;
    }

    private static final Comparator<BtDeviceRecord> BY_LAST_CONNECTED_DESC =
            (a, b) -> Long.compare(b.lastConnectedMs, a.lastConnectedMs);

    private static final Comparator<BtDeviceRecord> DISPLAY_ORDER =
            (a, b) -> {
                if (a.favorite != b.favorite) return a.favorite ? -1 : 1;
                return Long.compare(b.lastConnectedMs, a.lastConnectedMs);
            };

    @NonNull
    private static List<BtDeviceRecord> loadAll(Context context) {
        String json = prefs(context).getString(PREF_BT_DEVICES_JSON, "");
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            JSONArray arr = new JSONArray(json);
            List<BtDeviceRecord> list = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String addr = o.optString("address", "").trim();
                if (addr.isEmpty()) continue;
                BtDeviceRecord r = new BtDeviceRecord(addr);
                r.customName = o.has("customName") && !o.isNull("customName")
                        ? o.optString("customName") : null;
                if (r.customName != null && r.customName.trim().isEmpty()) {
                    r.customName = null;
                }
                r.favorite = o.optBoolean("favorite", false);
                r.lastConnectedMs = o.optLong("lastConnectedMs", 0L);
                r.lastSystemName = o.has("lastSystemName") && !o.isNull("lastSystemName")
                        ? o.optString("lastSystemName") : null;
                list.add(r);
            }
            return list;
        } catch (JSONException e) {
            return new ArrayList<>();
        }
    }

    private static void saveAll(Context context, List<BtDeviceRecord> list) {
        JSONArray arr = new JSONArray();
        for (BtDeviceRecord r : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("address", r.address);
                o.put("customName", r.customName == null ? JSONObject.NULL : r.customName);
                o.put("favorite", r.favorite);
                o.put("lastConnectedMs", r.lastConnectedMs);
                o.put("lastSystemName",
                        r.lastSystemName == null ? JSONObject.NULL : r.lastSystemName);
                arr.put(o);
            } catch (JSONException ignored) {
            }
        }
        prefs(context).edit().putString(PREF_BT_DEVICES_JSON, arr.toString()).apply();
    }
}
