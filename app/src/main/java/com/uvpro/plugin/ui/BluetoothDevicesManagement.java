package com.uvpro.plugin.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.atakmap.android.maps.MapView;
import com.uvpro.plugin.bluetooth.BluetoothDeviceRegistry;
import com.uvpro.plugin.bluetooth.BluetoothDeviceRegistry.BtDeviceRecord;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen style dialog under Tools → UV-PRO Settings: Bluetooth device
 * history with rename, favorite, and delete.
 */
public final class BluetoothDevicesManagement {

    private BluetoothDevicesManagement() {
    }

    public static void show(Context context, Runnable onChanged) {
        Activity act = resolveActivity(context);
        if (act == null) return;

        ScrollView scroll = new ScrollView(act);
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(act, 16);
        root.setPadding(pad, pad, pad, pad);

        List<BtDeviceRecord> devices =
                BluetoothDeviceRegistry.getAllSortedForDisplay(act);

        if (devices.isEmpty()) {
            TextView empty = new TextView(act);
            empty.setText(
                    "No saved radios yet.\n\nConnect once from the UV-PRO panel "
                            + "(Scan & Connect); each successful connection is "
                            + "remembered here.");
            empty.setTextColor(0xFFE0E0E0);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            root.addView(empty);
        }

        scroll.addView(root);
        scroll.setFillViewport(true);

        AlertDialog dialog = new AlertDialog.Builder(act)
                .setTitle("Bluetooth Devices")
                .setView(scroll)
                .setPositiveButton("Close", null)
                .create();

        Runnable reopen = () -> {
            dialog.dismiss();
            show(context, onChanged);
        };

        if (!devices.isEmpty()) {
            SimpleDateFormat sdf =
                    new SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US);
            for (BtDeviceRecord r : devices) {
                root.addView(buildDeviceBlock(act, r, sdf, onChanged, reopen));
                addSpacer(root, act, 12);
            }
        }

        dialog.show();
    }

    private static LinearLayout buildDeviceBlock(Activity act,
                                                 BtDeviceRecord r,
                                                 SimpleDateFormat sdf,
                                                 Runnable onChanged,
                                                 Runnable reopen) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(act, 12), dp(act, 10), dp(act, 12), dp(act, 10));
        card.setBackgroundColor(0xFF2A2A2A);

        TextView title = new TextView(act);
        title.setText(BluetoothDeviceRegistry.getDisplayTitle(r)
                + (r.favorite ? "  ★" : ""));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(null, Typeface.BOLD);
        card.addView(title);

        TextView mac = new TextView(act);
        mac.setText(r.address);
        mac.setTextColor(0xFFAAAAAA);
        mac.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        card.addView(mac);

        if (r.lastConnectedMs > 0) {
            TextView when = new TextView(act);
            when.setText("Last connected: " + sdf.format(new Date(r.lastConnectedMs)));
            when.setTextColor(0xFF888888);
            when.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            card.addView(when);
        }

        CheckBox fav = new CheckBox(act);
        fav.setText("Favorite (shown on UV-PRO panel)");
        fav.setTextColor(0xFFE0E0E0);
        fav.setChecked(r.favorite);
        fav.setOnCheckedChangeListener((buttonView, isChecked) -> {
            BluetoothDeviceRegistry.setFavorite(act, r.address, isChecked);
            if (onChanged != null) onChanged.run();
        });
        LinearLayout.LayoutParams lpFav = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lpFav.topMargin = dp(act, 6);
        fav.setLayoutParams(lpFav);
        card.addView(fav);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(act, 8);
        row.setLayoutParams(rowLp);

        TextView btnRename = new TextView(act);
        btnRename.setText("Rename");
        btnRename.setTextColor(0xFF00BCD4);
        btnRename.setPadding(dp(act, 12), dp(act, 6), dp(act, 12), dp(act, 6));
        btnRename.setOnClickListener(v -> showRenameDialog(act, r, onChanged, reopen));
        row.addView(btnRename);

        TextView btnDelete = new TextView(act);
        btnDelete.setText("Delete");
        btnDelete.setTextColor(0xFFFF5252);
        btnDelete.setPadding(dp(act, 12), dp(act, 6), dp(act, 12), dp(act, 6));
        btnDelete.setOnClickListener(v -> new AlertDialog.Builder(act)
                .setTitle("Remove radio?")
                .setMessage("Forget " + BluetoothDeviceRegistry.getDisplayTitle(r)
                        + " — you can add it again by connecting.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    BluetoothDeviceRegistry.remove(act, r.address);
                    if (onChanged != null) onChanged.run();
                    reopen.run();
                })
                .show());
        row.addView(btnDelete);

        card.addView(row);
        return card;
    }

    private static void showRenameDialog(Activity act, BtDeviceRecord r,
                                         Runnable onChanged, Runnable reopen) {
        EditText input = new EditText(act);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        if (r.customName != null && !r.customName.isEmpty()) {
            input.setText(r.customName);
        } else if (r.lastSystemName != null) {
            input.setText(r.lastSystemName);
            input.selectAll();
        }
        int m = dp(act, 20);
        LinearLayout wrap = new LinearLayout(act);
        wrap.setPadding(m, dp(act, 8), m, 0);
        wrap.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(act)
                .setTitle("Rename")
                .setMessage("Leave blank to clear custom name and use the radio name from Android.")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    BluetoothDeviceRegistry.setCustomName(act, r.address,
                            input.getText().toString());
                    if (onChanged != null) onChanged.run();
                    reopen.run();
                })
                .show();
    }

    private static void addSpacer(LinearLayout root, Activity act, int dp) {
        ViewGroup.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, dp));
        TextView t = new TextView(act);
        t.setLayoutParams(sp);
        root.addView(t);
    }

    private static int dp(Context c, int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                dp, c.getResources().getDisplayMetrics());
    }

    private static Activity resolveActivity(Context context) {
        if (context instanceof Activity) {
            return (Activity) context;
        }
        try {
            MapView mv = MapView.getMapView();
            if (mv != null && mv.getContext() instanceof Activity) {
                return (Activity) mv.getContext();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
