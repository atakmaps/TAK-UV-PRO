package com.uvpro.plugin.cot;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.Toast;

import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;

import java.io.File;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * UI for picking a GRG Builder settings JSON and sending it to a radio contact.
 */
public final class GrgShareUi {

    private static volatile CotBridge cotBridge;

    private GrgShareUi() {
    }

    public static void setCotBridge(CotBridge bridge) {
        cotBridge = bridge;
    }

    public static void showShareDialog(Context context, IndividualContact contact) {
        if (context == null || contact == null) {
            return;
        }
        CotBridge bridge = cotBridge;
        if (bridge == null || !bridge.isRadioConnected()) {
            Toast.makeText(context, "Radio not connected — connect UV-PRO or MeshCore first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        File[] files = GrgSettingsShare.listSettingsJsonFiles();
        if (files.length == 0) {
            Toast.makeText(context,
                    "No GRG settings JSON found in sdcard/atak/tools/grgbuilder/. "
                            + "Export Settings (JSON) from GRG Builder first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Arrays.sort(files, Comparator.comparing(f -> f.getName().toLowerCase(Locale.US)));
        String[] labels = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            labels[i] = files[i].getName();
        }
        String contactName = contact.getName() != null ? contact.getName() : contact.getUID();
        new AlertDialog.Builder(context)
                .setTitle("Share GRG over RF — " + contactName)
                .setMessage("Sends map points inside the GRG area, then the settings JSON.")
                .setItems(labels, (d, which) -> {
                    if (which < 0 || which >= files.length) {
                        return;
                    }
                    startShare(context, files[which], contact.getUID());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static void startShare(Context context, File jsonFile, String contactUid) {
        CotBridge bridge = cotBridge;
        if (bridge == null) {
            Toast.makeText(context, "Plugin not ready — try again.", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(context, "Sending GRG to contact…", Toast.LENGTH_SHORT).show();
        bridge.sendGrgSettingsBundle(jsonFile, new String[]{contactUid}, result -> {
            MapView mv = MapView.getMapView();
            Context ui = mv != null ? mv.getContext() : context;
            if (ui == null) {
                return;
            }
            Toast.makeText(ui, result.message, result.success
                    ? Toast.LENGTH_LONG : Toast.LENGTH_LONG).show();
        });
    }

    public static String formatUserPath(File file) {
        if (file == null) {
            return "sdcard/atak/tools/grgbuilder";
        }
        String abs = file.getAbsolutePath();
        if (abs.contains("/atak/")) {
            return "sdcard/atak/" + abs.substring(abs.indexOf("/atak/") + "/atak/".length());
        }
        return abs;
    }
}
