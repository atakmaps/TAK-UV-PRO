package com.btechrelay.plugin.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceManager;

import com.atakmap.android.chat.ChatManagerMapComponent;
import com.atakmap.android.preference.PluginPreferenceFragment;

/**
 * Settings screen for the BtechRelay plugin.
 *
 * Provides configuration for:
 * - Callsign
 * - Beacon interval
 * - Chat relay toggle
 * - CoT relay toggle
 * - Auto-reconnect toggle
 */
public class SettingsFragment extends PluginPreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static final String PREF_BEACON_INTERVAL = "btechrelay_beacon_interval";
    public static final String PREF_AUTO_RECONNECT = "btechrelay_auto_reconnect";
    public static final String PREF_ENCRYPTION_ENABLED = "btechrelay_encryption_enabled";
    public static final String PREF_ENCRYPTION_PASSPHRASE = "btechrelay_encryption_passphrase";
    public static final String PREF_RETRY_INTERVAL_MIN = "btechrelay_retry_interval_min";
    public static final String PREF_RETRY_MAX = "btechrelay_retry_max";

    public static final String DEFAULT_BEACON_INTERVAL = "300";
    public static final boolean DEFAULT_AUTO_RECONNECT = true;
    public static final String DEFAULT_RETRY_INTERVAL_MIN = "2";
    public static final String DEFAULT_RETRY_MAX = "3";

    private static Context staticPluginContext;

    /**
     * Zero-arg constructor required by Android fragment system.
     * Only valid after the 1-arg constructor has been called once.
     */
    public SettingsFragment() {
        super(staticPluginContext, getResourceId());
    }

    public SettingsFragment(final Context pluginContext) {
        super(pluginContext, getResourceId());
        staticPluginContext = pluginContext;
    }

    private static int getResourceId() {
        if (staticPluginContext == null) return 0;
        return staticPluginContext.getResources().getIdentifier(
                "preferences", "xml", staticPluginContext.getPackageName());
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
        updateSummaries();
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceManager().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs,
                                          String key) {
        updateSummaries();
    }

    private void updateSummaries() {
        SharedPreferences prefs =
                getPreferenceManager().getSharedPreferences();

        Preference beaconPref = findPreference(PREF_BEACON_INTERVAL);
        if (beaconPref != null) {
            String interval = prefs.getString(
                    PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL);
            beaconPref.setSummary("Every " + interval + " seconds");
        }

        Preference retryIntervalPref = findPreference(PREF_RETRY_INTERVAL_MIN);
        if (retryIntervalPref != null) {
            String mins = prefs.getString(PREF_RETRY_INTERVAL_MIN, DEFAULT_RETRY_INTERVAL_MIN);
            retryIntervalPref.setSummary("Retry after " + mins + " minute(s) with no ACK");
        }

        Preference retryMaxPref = findPreference(PREF_RETRY_MAX);
        if (retryMaxPref != null) {
            String max = prefs.getString(PREF_RETRY_MAX, DEFAULT_RETRY_MAX);
            retryMaxPref.setSummary("Up to " + max + " retransmit attempt(s) before failure");
        }    }

    @Override
    public String getSubTitle() {
        return "BTECH Relay Settings";
    }

    /**
     * Convenience: Get a preference value from any context.
     */
    /**
     * Get the ATAK-process SharedPreferences (uses ATAK context, not plugin context).
     */
    private static android.content.SharedPreferences getPrefs(Context context) {
        // Plugin context can't write to its own shared_prefs dir because it
        // runs inside ATAK's process. Always use ATAK's context.
        Context ctx = com.atakmap.android.maps.MapView.getMapView() != null
                ? com.atakmap.android.maps.MapView.getMapView().getContext()
                : context;
        return PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    public static String getCallsign(Context context) {
        try {
            com.atakmap.android.maps.MapView mv = com.atakmap.android.maps.MapView.getMapView();
            if (mv != null && mv.getSelfMarker() != null) {
                return mv.getSelfMarker().getMetaString("callsign", "UNKNOWN");
            }
        } catch (Exception e) {
        }
        return "UNKNOWN";
    }

    public static int getBeaconIntervalSec(Context context) {
        String val = getPrefs(context)
                .getString(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL);
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    public static long getRetryIntervalMs(Context context) {
        String val = getPrefs(context)
                .getString(PREF_RETRY_INTERVAL_MIN, DEFAULT_RETRY_INTERVAL_MIN);
        try {
            return Long.parseLong(val) * 60_000L;
        } catch (NumberFormatException e) {
            return 2 * 60_000L;
        }
    }

    public static int getMaxChatRetries(Context context) {
        String val = getPrefs(context)
                .getString(PREF_RETRY_MAX, DEFAULT_RETRY_MAX);
        try {
            return Math.max(1, Integer.parseInt(val));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    public static boolean isAutoReconnectEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_AUTO_RECONNECT, DEFAULT_AUTO_RECONNECT);
    }

    /**
     * Use ATAK's team color ("locationTeam") rather than a plugin-managed setting, so
     * radio contacts match the operator's configured team consistently.
     */
    public static String getAtakTeamColor(Context context) {
        try {
            String team = ChatManagerMapComponent.getTeamName();
            if (team != null && !team.trim().isEmpty()) return team.trim();
        } catch (Exception ignored) {
        }
        try {
            com.atakmap.android.preference.AtakPreferences prefs =
                    com.atakmap.android.preference.AtakPreferences.getInstance(
                            com.atakmap.android.maps.MapView.getMapView() != null
                                    ? com.atakmap.android.maps.MapView.getMapView().getContext()
                                    : context);
            String team = prefs.get("locationTeam", "Cyan");
            if (team != null && !team.trim().isEmpty()) return team.trim();
        } catch (Exception ignored) {
        }
        return "Cyan";
    }

    public static boolean isEncryptionEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_ENCRYPTION_ENABLED, false);
    }

    public static String getEncryptionPassphrase(Context context) {
        return getPrefs(context)
                .getString(PREF_ENCRYPTION_PASSPHRASE, "");
    }
}
