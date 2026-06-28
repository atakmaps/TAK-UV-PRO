package com.uvpro.plugin.ax25;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.coremap.filesystem.FileSystemUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Ensures the APRS iconset package is silently auto-imported into ATAK on first launch.
 * Stages the bundled zip to the ATAK import folder then fires the standard ATAK import
 * broadcast so no manual user action is required.
 */
public final class AprsIconsetInstaller {
    private static final String TAG = "UVPro.Iconset";
    private static final String ICONSET_UID = AprsSymbolMapper.ICONSET_UID;
    private static final String ICONSET_ASSET = "APRS-Symbols-APRSdroid.zip";
    private static final String ICONSET_FILENAME = "APRS.zip";

    private static volatile boolean autoImportTriggered = false;

    private AprsIconsetInstaller() {
    }

    /**
     * Stage and silently auto-import the APRS iconset.
     * The broadcast is fired only once per session; subsequent calls just check
     * {@link #isIconsetInstalled()} and return false once ATAK confirms import.
     *
     * @return true when iconset is still pending (caller should retry later)
     */
    public static boolean ensureStagedAndPromptIfMissing(Context pluginContext, Context uiContext) {
        if (pluginContext == null) {
            return false;
        }
        try {
            if (isIconsetInstalled()) {
                autoImportTriggered = false;
                return false;
            }
            if (!autoImportTriggered) {
                boolean staged = stageIconsetZip(pluginContext);
                if (staged) {
                    File stagedFile = FileSystemUtils.getItem("tools/import/" + ICONSET_FILENAME);
                    triggerAutoImport(stagedFile);
                    autoImportTriggered = true;
                    Log.i(TAG, "APRS iconset auto-import triggered: " + stagedFile.getAbsolutePath());
                } else {
                    Log.w(TAG, "APRS iconset staging failed — will retry");
                }
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "ensureStagedAndPromptIfMissing failed: " + e.getMessage(), e);
            return false;
        }
    }

    /** No-op kept for backward compatibility with map component call sites. */
    public static void clearPersistentReminder(Context uiContext) {
        autoImportTriggered = false;
    }

    public static boolean isIconsetInstalled() {
        File db = FileSystemUtils.getItem("Databases/iconsets.sqlite");
        if (!db.exists()) {
            return false;
        }
        SQLiteDatabase sqlite = null;
        Cursor cursor = null;
        try {
            sqlite = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            cursor = sqlite.rawQuery("SELECT 1 FROM iconsets WHERE uid=? LIMIT 1",
                    new String[]{ICONSET_UID});
            return cursor.moveToFirst();
        } catch (Exception e) {
            Log.w(TAG, "isIconsetInstalled query failed: " + e.getMessage());
            return false;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            if (sqlite != null) {
                sqlite.close();
            }
        }
    }

    private static void triggerAutoImport(File file) {
        try {
            Intent intent = new Intent("com.atakmap.android.icons.ADD_ICONSET");
            intent.putExtra("filepath", file.getAbsolutePath());
            AtakBroadcast.getInstance().sendBroadcast(intent);
        } catch (Exception e) {
            Log.w(TAG, "triggerAutoImport failed: " + e.getMessage(), e);
        }
    }

    private static boolean stageIconsetZip(Context pluginContext) {
        File importDir = FileSystemUtils.getItem("tools/import");
        if (!importDir.exists() && !importDir.mkdirs()) {
            Log.w(TAG, "Failed to create import dir: " + importDir.getAbsolutePath());
            return false;
        }
        File outFile = new File(importDir, ICONSET_FILENAME);
        if (outFile.isFile() && outFile.length() > 0L) {
            Log.i(TAG, "APRS iconset already staged: " + outFile.getAbsolutePath());
            return true;
        }
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = pluginContext.getAssets().open(ICONSET_ASSET);
            out = new FileOutputStream(outFile, false);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            Log.i(TAG, "Staged APRS iconset: " + outFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "stageIconsetZip failed: " + e.getMessage(), e);
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }
}
