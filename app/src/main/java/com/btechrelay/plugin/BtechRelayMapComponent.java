package com.btechrelay.plugin;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.app.preferences.ToolsPreferenceFragment;

import com.btechrelay.plugin.bluetooth.BtConnectionManager;
import com.btechrelay.plugin.contacts.ContactTracker;
import com.btechrelay.plugin.cot.CotBridge;
import com.btechrelay.plugin.chat.ChatBridge;
import com.btechrelay.plugin.crypto.EncryptionManager;
import com.btechrelay.plugin.protocol.PacketRouter;
import com.btechrelay.plugin.ui.SettingsFragment;

/**
 * BtechRelay Map Component — the central nervous system of the plugin.
 *
 * Initializes all sub-systems:
 * - Bluetooth connection management
 * - KISS TNC encoder/decoder
 * - CoT bridge (position sharing, marker sync)
 * - Chat bridge (GeoChat relay)
 * - Contact tracker (radio contacts on map)
 * - Packet router (dispatches received data)
 */
public class BtechRelayMapComponent extends DropDownMapComponent {

    private static final String TAG = "BtechRelay";
    public static final String PLUGIN_PACKAGE = "com.btechrelay.plugin";
    public static final String ACTION_BEACON_INTERVAL_CHANGED =
            "com.btechrelay.plugin.BEACON_INTERVAL_CHANGED";

    private Context pluginContext;
    private MapView mapView;

    // Sub-systems
    private BtConnectionManager btConnectionManager;
    private PacketRouter packetRouter;
    private CotBridge cotBridge;
    private ChatBridge chatBridge;
    private ContactTracker contactTracker;
    private BtechRelayDropDownReceiver dropDownReceiver;
    private EncryptionManager encryptionManager;
    private Handler beaconHandler;
    private Runnable beaconRunnable;
    private android.content.BroadcastReceiver beaconIntervalReceiver;

    @Override
    public void onCreate(Context context, Intent intent, MapView view) {
        // context = plugin context, view.getContext() = map context (use for UI)
        this.pluginContext = context;
        this.mapView = view;

        Log.i(TAG, "BTECH Relay plugin initializing...");

        // Read user preferences
        String callsign = "UNKNOWN";
try {
    com.atakmap.android.maps.PointMapItem self = view.getSelfMarker();
    if (self != null) {
        callsign = self.getMetaString("callsign", "UNKNOWN");
    }
} catch (Exception e) {
    android.util.Log.e("BTRelay", "Failed to get ATAK callsign", e);
}

        // Initialize sub-systems in dependency order:
        // 1. CotBridge (needs plugin context + MapView)
        cotBridge = new CotBridge(context, view);
        cotBridge.setLocalCallsign(callsign);
        cotBridge.setTeamColor(SettingsFragment.getTeamColor(context));

        // 1b. Encryption
        encryptionManager = new EncryptionManager();
        if (SettingsFragment.isEncryptionEnabled(context)) {
            encryptionManager.setPassphrase(
                    SettingsFragment.getEncryptionPassphrase(context));
        }
        cotBridge.setEncryptionManager(encryptionManager);

        // 2. ChatBridge (needs plugin context + MapView)
        chatBridge = new ChatBridge(context, view);
        chatBridge.setLocalCallsign(callsign);
        chatBridge.setCotBridge(cotBridge);

        // 3. ContactTracker (needs CotBridge for injecting CoT events)
        contactTracker = new ContactTracker(cotBridge);

        // === REGISTER CONTACT HANDLER ===
        try {
            com.atakmap.android.contact.ContactConnectorManager mgr =
                    com.atakmap.android.cot.CotMapComponent.getInstance()
                            .getContactConnectorMgr();

            mgr.addContactHandler(
                    new com.btechrelay.plugin.BtechRelayContactHandler(context)
            );

        } catch (Exception e) {
            android.util.Log.e("BTRelay", "Handler registration failed", e);
        }


        // 4. PacketRouter (needs CotBridge, ChatBridge, ContactTracker)
        packetRouter = new PacketRouter(cotBridge, chatBridge, contactTracker);
        packetRouter.setEncryptionManager(encryptionManager);

        // 5. BtConnectionManager (needs context + PacketRouter)
        btConnectionManager = new BtConnectionManager(context, packetRouter);

        // Wire BT manager into bridges so they can transmit
        cotBridge.setBtManager(btConnectionManager);
        chatBridge.setBtManager(btConnectionManager);
        chatBridge.setEncryptionManager(encryptionManager);

        // 6. Create the drop-down UI receiver
        dropDownReceiver = new BtechRelayDropDownReceiver(
                view, pluginContext, btConnectionManager, contactTracker);
        dropDownReceiver.setCotBridge(cotBridge);
        dropDownReceiver.setEncryptionManager(encryptionManager);


        // Wire PacketRouter RX count to dropdown UI
        packetRouter.setPacketCountListener(dropDownReceiver);

        // Register the drop-down with ATAK
        AtakBroadcast.DocumentedIntentFilter filter =
                new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction(BtechRelayDropDownReceiver.SHOW_PLUGIN);
        registerDropDownReceiver(dropDownReceiver, filter);

        // 8. Register settings with ATAK Tools Preferences
        ToolsPreferenceFragment.register(
                new ToolsPreferenceFragment.ToolPreference(
                        "BTECH Relay Settings",
                        "BTECH radio bridge configuration",
                        "btechRelayPreference",
                        context.getResources().getDrawable(
                                context.getResources().getIdentifier(
                                        "ic_btechrelay", "drawable",
                                        context.getPackageName()), null),
                        new SettingsFragment(context)));

        // Start background services
        contactTracker.start();
        // Outbound is contact-targeted (+ optional periodic beacon path). Legacy
        // "bridge all PLI/chat" toggles were removed — radio traffic follows ATAK contacts.
        chatBridge.setRelayOutgoing(true);
        chatBridge.startOutgoingRelay();

        // Do not blanket-flood outbound SA/geo over RX; relay when destination is a radio contact.
        cotBridge.setRelayOutgoingSa(false);
        cotBridge.startOutgoingRelay();

        // 9. Start periodic beacon timer
        startBeaconTimer();

        // Listen for runtime preference changes that require rescheduling timers.
        try {
            beaconIntervalReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent i) {
                    if (i == null) return;
                    if (ACTION_BEACON_INTERVAL_CHANGED.equals(i.getAction())) {
                        Log.d(TAG, "Beacon interval changed — rescheduling timer");
                        startBeaconTimer();
                    }
                }
            };
            AtakBroadcast.DocumentedIntentFilter beaconFilter =
                    new AtakBroadcast.DocumentedIntentFilter();
            beaconFilter.addAction(ACTION_BEACON_INTERVAL_CHANGED);
            AtakBroadcast.getInstance()
                    .registerReceiver(beaconIntervalReceiver, beaconFilter);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register beacon interval receiver", e);
        }

        Log.i(TAG, "BTECH Relay plugin initialized successfully (callsign="
                + callsign + ")");
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        Log.i(TAG, "BTECH Relay plugin shutting down...");

        // Stop beacon timer
        if (beaconHandler != null && beaconRunnable != null) {
            beaconHandler.removeCallbacks(beaconRunnable);
        }

        // Unregister settings
        ToolsPreferenceFragment.unregister("btechRelayPreference");

        // Shutdown in reverse order
        if (btConnectionManager != null) {
            btConnectionManager.disconnect();
            btConnectionManager = null;
        }
        if (contactTracker != null) {
            contactTracker.stop();
            contactTracker = null;
        }
        if (cotBridge != null) {
            cotBridge.dispose();
            cotBridge = null;
        }
        if (chatBridge != null) {
            chatBridge.dispose();
            chatBridge = null;
        }
        if (beaconIntervalReceiver != null) {
            try {
                AtakBroadcast.getInstance().unregisterReceiver(beaconIntervalReceiver);
            } catch (Exception ignored) {
            }
            beaconIntervalReceiver = null;
        }

        Log.i(TAG, "BTECH Relay plugin shutdown complete");
    }

    /**
     * Start periodic GPS beacon broadcasts.
     */
    private void startBeaconTimer() {
        // Defensive: ensure we don't accidentally run multiple timers if the
        // component is re-created without a clean destroy (can happen in ATAK).
        if (beaconHandler != null && beaconRunnable != null) {
            beaconHandler.removeCallbacks(beaconRunnable);
        }

        beaconHandler = new Handler(Looper.getMainLooper());
        beaconRunnable = new Runnable() {
            @Override
            public void run() {
                sendBeaconIfConnected();
                int intervalSec = SettingsFragment.getBeaconIntervalSec(
                        pluginContext);
                if (intervalSec < 1) intervalSec = 1;
                beaconHandler.postDelayed(this, intervalSec * 1000L);
            }
        };
        int initialDelay = SettingsFragment.getBeaconIntervalSec(
                pluginContext) * 1000;
        if (initialDelay < 1000) initialDelay = 1000;
        beaconHandler.postDelayed(beaconRunnable, initialDelay);
    }

    private void sendBeaconIfConnected() {
        if (btConnectionManager == null || !btConnectionManager.isConnected()) {
            return;
        }
        if (cotBridge == null || mapView == null) return;

        try {
            PointMapItem self = mapView.getSelfMarker();
            if (self != null) {
                GeoPoint gp = self.getPoint();
                cotBridge.sendPositionOverRadio(
                        gp.getLatitude(), gp.getLongitude(),
                        gp.getAltitude(), 0, 0, -1);
                Log.d(TAG, "Periodic beacon sent");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending periodic beacon", e);
        }
    }

    /**
     * Get the Bluetooth connection manager (for UI access).
     */
    public BtConnectionManager getBtConnectionManager() {
        return btConnectionManager;
    }
}
