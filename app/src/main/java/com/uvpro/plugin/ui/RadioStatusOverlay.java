package com.uvpro.plugin.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.widgets.LinearLayoutWidget;
import com.atakmap.android.widgets.MarkerIconWidget;
import com.atakmap.android.widgets.RootLayoutWidget;
import com.atakmap.coremap.maps.assets.Icon;
import com.uvpro.plugin.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Status widget anchored to the ATAK bottom-right layout.
 *
 * Connected    → full-colour Baofeng Tech icon (green tint)
 * Disconnected → same icon desaturated (red tint)
 *
 * Icons are written to the app's files dir so ATAK's GL renderer can load
 * them via file:// URI (android.resource:// URIs don't resolve cross-APK in
 * ATAK's OpenGL widget renderer).
 */
public class RadioStatusOverlay extends MarkerIconWidget {

    private static final String TAG = "UVPro.StatusOverlay";
    private static final int ICON_WIDTH  = 64;
    private static final int ICON_HEIGHT = 64;

    private static RadioStatusOverlay instance;
    private static boolean lastKnownConnected = false;

    private final MapView mapView;
    private final String connectedUri;
    private final String disconnectedUri;

    private RadioStatusOverlay(Context pluginContext, MapView mv,
                                LinearLayoutWidget brLayout) {
        this.mapView = mv;
        this.connectedUri    = extractIcon(pluginContext, mv, R.drawable.ic_radio_status_connected,    "ic_radio_connected.png");
        this.disconnectedUri = extractIcon(pluginContext, mv, R.drawable.ic_radio_status_disconnected, "ic_radio_disconnected.png");
        Log.d(TAG, "connectedUri=" + connectedUri);
        Log.d(TAG, "disconnectedUri=" + disconnectedUri);
        brLayout.addWidget(this);
        setMargins(0f, 0f, 4f, 20f); // small right + bottom gap from screen edge
        applyIcon(false);
        Log.d(TAG, "Widget installed in BOTTOM_RIGHT");
    }

    public static void install(Context pluginContext) {
        MapView mv = MapView.getMapView();
        if (mv == null) { Log.e(TAG, "install: MapView null"); return; }
        uninstall();
        try {
            RootLayoutWidget root =
                    (RootLayoutWidget) mv.getComponentExtra("rootLayoutWidget");
            if (root == null) {
                Log.e(TAG, "install: rootLayoutWidget null — retrying in 2s");
                mv.postDelayed(() -> install(pluginContext), 2000);
                return;
            }
            LinearLayoutWidget br = root.getLayout(RootLayoutWidget.BOTTOM_RIGHT);
            if (br == null) { Log.e(TAG, "install: BOTTOM_RIGHT null"); return; }
            instance = new RadioStatusOverlay(pluginContext, mv, br);
            // Apply whatever state the radio is already in
            instance.applyIcon(lastKnownConnected);
            Log.d(TAG, "Widget installed, connected=" + lastKnownConnected);
        } catch (Exception e) {
            Log.e(TAG, "install failed", e);
        }
    }

    public static void uninstall() {
        if (instance == null) return;
        try {
            RootLayoutWidget root =
                    (RootLayoutWidget) instance.mapView.getComponentExtra("rootLayoutWidget");
            root.getLayout(RootLayoutWidget.BOTTOM_RIGHT).removeWidget(instance);
        } catch (Exception ignored) {}
        instance = null;
    }

    public static void setConnected(boolean connected) {
        lastKnownConnected = connected;
        if (instance == null) return;
        MapView mv = MapView.getMapView();
        if (mv == null) return;
        mv.post(() -> { if (instance != null) instance.applyIcon(connected); });
    }

    private void applyIcon(boolean connected) {
        String uri = connected ? connectedUri : disconnectedUri;
        if (uri == null) { Log.e(TAG, "applyIcon: uri is null"); return; }
        Log.d(TAG, "applyIcon connected=" + connected);
        Icon.Builder b = new Icon.Builder();
        b.setAnchor(0, 0);
        b.setColor(Icon.STATE_DEFAULT, Color.WHITE); // neutral — PNG carries all color
        b.setSize(ICON_WIDTH, ICON_HEIGHT);
        b.setImageUri(Icon.STATE_DEFAULT, uri);
        setIcon(b.build());
    }

    /** Copies a drawable resource to ATAK's cache dir and returns a file:// URI. */
    private static String extractIcon(Context pluginCtx, MapView mv, int resId, String filename) {
        try {
            // Plugin runs inside ATAK's process — use ATAK's cache dir (writable)
            File dir = new File(mv.getContext().getCacheDir(), "uvpro_icons");
            dir.mkdirs();
            File out = new File(dir, filename);
            try (InputStream in = pluginCtx.getResources().openRawResource(resId);
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
            }
            Log.d(TAG, "Extracted icon to " + out.getAbsolutePath() + " size=" + out.length());
            return "file://" + out.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "extractIcon failed for " + filename, e);
            return null;
        }
    }
}
