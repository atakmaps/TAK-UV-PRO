package com.uvpro.plugin.protocol;

import androidx.annotation.Nullable;

import com.uvpro.plugin.bluetooth.MeshBtConnectionManager;

/** Static access to the MeshCore BLE transport (separate from classic UV-PRO SPP). */
public final class UVProMeshServices {

    private static volatile MeshBtConnectionManager meshBtManager;

    private UVProMeshServices() {
    }

    public static void install(MeshBtConnectionManager meshBt) {
        meshBtManager = meshBt;
    }

    public static void clear() {
        meshBtManager = null;
    }

    public static boolean isConnected() {
        MeshBtConnectionManager bt = meshBtManager;
        return bt != null && bt.isConnected();
    }

    @Nullable
    public static MeshBtConnectionManager getMeshBtManager() {
        return meshBtManager;
    }
}
