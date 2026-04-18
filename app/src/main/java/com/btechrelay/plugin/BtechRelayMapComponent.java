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
import com.btechrelay.plugin.voice.PttController;

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

    private Context pluginContext;
    private MapView mapView;

    // Sub-systems
    private BtConnectionManager btConnectionManager;
    private PacketRouter packetRouter;
    private CotBridge cotBridge;
    private ChatBridge chatBridge;
    private ContactTracker contactTracker;
    private BtechRelayDropDownReceiver dropDownReceiver;
    private PttController pttController;
    private EncryptionManager encryptionManager;
    private Handler beaconHandler;
    private Runnable beaconRunnable;

    @Override
    public void onCreate(Context context, Intent intent, MapView view) {
        // context = plugin context, view.getContext() = map context (use for UI)
        this.pluginContext = context;
        this.mapView = view;

        Log.i(TAG, "BTECH Relay plugin initializing...");

        // Read user preferences
        String callsign = SettingsFragment.getCallsign(context);

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
        dropDownReceiver.setChatBridge(chatBridge);
        dropDownReceiver.setEncryptionManager(encryptionManager);

        // 7. PttController (wire to dropdown)
        pttController = new PttController(view.getContext());
        dropDownReceiver.setPttController(pttController);

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
        chatBridge.setRelayOutgoing(SettingsFragment.isRelayChatEnabled(context));
        chatBridge.startOutgoingRelay();

        // Enable outgoing CoT relay based on user preferences
        cotBridge.setRelayOutgoingSa(SettingsFragment.isRelayCotEnabled(context));
        cotBridge.startOutgoingRelay();

        // 9. Start periodic beacon timer
        startBeaconTimer();

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
        if (pttController != null) {
            pttController.dispose();
            pttController = null;
        }
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

        Log.i(TAG, "BTECH Relay plugin shutdown complete");
    }

    /**
     * Start periodic GPS beacon broadcasts.
     */
    private void startBeaconTimer() {
        beaconHandler = new Handler(Looper.getMainLooper());
        beaconRunnable = new Runnable() {
            @Override
            public void run() {
                sendBeaconIfConnected();
                int intervalSec = SettingsFragment.getBeaconIntervalSec(
                        pluginContext);
                beaconHandler.postDelayed(this, intervalSec * 1000L);
            }
        };
        int initialDelay = SettingsFragment.getBeaconIntervalSec(
                pluginContext) * 1000;
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
