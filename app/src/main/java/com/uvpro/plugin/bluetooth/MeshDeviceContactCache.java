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

    public static void upsertFromDeviceContact(Context context, @Nullable String deviceAddress,
                                               @NonNull MeshBtConnectionManager.MeshDeviceContact contact) {
        String addr = normalizeDeviceAddress(deviceAddress);
        if (addr == null || contact.pubKeyHex == null) {
            return;
        }
        String key = contact.pubKeyHex.trim().toUpperCase(Locale.US);
        List<MeshBtConnectionManager.MeshDeviceContact> contacts = load(context, addr);
        boolean replaced = false;
        for (int i = 0; i < contacts.size(); i++) {
            MeshBtConnectionManager.MeshDeviceContact existing = contacts.get(i);
            if (existing.pubKeyHex != null
                    && existing.pubKeyHex.toUpperCase(Locale.US).equals(key)) {
                contacts.set(i, contact);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            contacts.add(contact);
        }
        save(context, addr, contacts);
    }

    public static void removeByPubKey(Context context, @Nullable String deviceAddress,
                                      @Nullable String pubKeyHex) {
        String addr = normalizeDeviceAddress(deviceAddress);
        if (addr == null || pubKeyHex == null || pubKeyHex.trim().isEmpty()) {
            return;
        }
        String key = pubKeyHex.trim().toUpperCase(Locale.US);
        List<MeshBtConnectionManager.MeshDeviceContact> contacts = load(context, addr);
        if (contacts.isEmpty()) {
            return;
        }
        boolean removed = false;
        for (int i = contacts.size() - 1; i >= 0; i--) {
            MeshBtConnectionManager.MeshDeviceContact c = contacts.get(i);
            if (c.pubKeyHex != null && pubKeysMatch(key, c.pubKeyHex.toUpperCase(Locale.US))) {
                contacts.remove(i);
                removed = true;
                break;
            }
        }
        if (removed) {
            save(context, addr, contacts);
        }
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
            if (c.pubKeyHex != null && pubKeysMatch(key, c.pubKeyHex.toUpperCase(Locale.US))) {
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

    @Nullable
    public static MeshBtConnectionManager.MeshDeviceContact findByPubKeyPrefix(
            Context context, @Nullable String deviceAddress, @Nullable String prefix12) {
        if (prefix12 == null || prefix12.length() < 12) {
            return null;
        }
        String prefix = prefix12.trim().toUpperCase(Locale.US).substring(0, 12);
        for (MeshBtConnectionManager.MeshDeviceContact contact : load(context, deviceAddress)) {
            if (contact.pubKeyHex != null
                    && contact.pubKeyHex.toUpperCase(Locale.US).startsWith(prefix)) {
                return contact;
            }
        }
        return null;
    }

    @Nullable
    public static String resolvePubKeyHexFromNodeCaches(Context context,
                                                        @Nullable String prefix12) {
        if (prefix12 == null || prefix12.length() < 12) {
            return null;
        }
        String prefix = prefix12.trim().toUpperCase(Locale.US).substring(0, 12);
        String[] prefKeys = {
                "uvpro_mesh_node_cache_v1",
                "uvpro_mesh_repeater_cache_v1"
        };
        SharedPreferences prefs = prefs(context);
        for (String prefKey : prefKeys) {
            String raw = prefs.getString(prefKey, "[]");
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    String pubKey = o.optString("pubKeyHex", "").trim().toUpperCase(Locale.US);
                    if (pubKey.startsWith(prefix)) {
                        return pubKey;
                    }
                }
            } catch (JSONException ignored) {
            }
        }
        return null;
    }

    public static void syncFavoriteFromUid(Context context, @Nullable String deviceAddress,
                                             @Nullable String pubKeyPrefix12,
                                             @Nullable String displayName, int contactType,
                                             @Nullable String fullPubKeyHex) {
        String addr = normalizeDeviceAddress(deviceAddress);
        if (addr == null || pubKeyPrefix12 == null || pubKeyPrefix12.length() < 12) {
            return;
        }
        String prefix = pubKeyPrefix12.trim().toUpperCase(Locale.US).substring(0, 12);
        MeshBtConnectionManager.MeshDeviceContact existing =
                findByPubKeyPrefix(context, addr, prefix);
        if (existing != null) {
            updateFavoriteFlag(context, addr, existing.pubKeyHex, true);
            return;
        }
        String pubKey = fullPubKeyHex;
        if (pubKey == null || pubKey.length() < 64) {
            pubKey = resolvePubKeyHexFromNodeCaches(context, prefix);
        }
        if (pubKey == null || pubKey.length() < 64) {
            return;
        }
        String name = displayName != null && !displayName.trim().isEmpty()
                ? displayName.trim() : prefix;
        List<MeshBtConnectionManager.MeshDeviceContact> contacts = load(context, addr);
        contacts.add(new MeshBtConnectionManager.MeshDeviceContact(
                pubKey, contactType, MeshBtConnectionManager.CONTACT_FLAG_FAVORITE,
                0, name, 0, 0.0, 0.0,
                (int) (System.currentTimeMillis() / 1000L)));
        save(context, addr, contacts);
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
