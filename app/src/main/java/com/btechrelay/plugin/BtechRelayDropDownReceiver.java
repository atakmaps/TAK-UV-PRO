package com.btechrelay.plugin;

import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.EditText;
import android.widget.TextView;

import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;

import com.btechrelay.plugin.bluetooth.BtConnectionManager;
import com.btechrelay.plugin.contacts.ContactTracker;
import com.btechrelay.plugin.contacts.RadioContact;
import com.btechrelay.plugin.cot.CotBridge;
import com.btechrelay.plugin.crypto.EncryptionManager;
import com.btechrelay.plugin.protocol.PacketRouter;
import com.btechrelay.plugin.ui.SettingsFragment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * BtechRelay Drop-Down UI Panel.
 *
 * Slides in from the right side of the ATAK map. Provides:
 * - Radio connection status and controls
 * - Contact count and statistics
 * - Quick-action buttons (beacon, ping, settings)
 * - Debug log view
 */
public class BtechRelayDropDownReceiver extends DropDownReceiver
        implements DropDown.OnStateListener,
        BtConnectionManager.ConnectionListener,
        ContactTracker.ContactListener,
        PacketRouter.PacketCountListener {

    public static final String SHOW_PLUGIN =
            "com.btechrelay.plugin.SHOW_PLUGIN";

    private static final String TAG = "BtechRelay.UI";
    private static final int MAX_LOG_LINES = 50;

    private final Context pluginContext;
    private final BtConnectionManager btManager;
    private final ContactTracker contactTracker;
    private CotBridge cotBridge;
    private EncryptionManager encryptionManager;

    private View rootView;
    private View statusDot;
    private TextView statusText;
    private TextView deviceName;
    private TextView callsignText;
    private TextView contactsText;
    private TextView packetsText;
    private TextView logText;
    private TextView encryptionStatusText;
    private TextView beaconIntervalText;
    private TextView teamColorText;
    private Button btnScan;
    private Button btnDisconnect;

    private Switch switchEncryption;
    private View passphraseRow;
    private EditText editPassphrase;
    private Button btnSetPassphrase;

    private final LinkedList<String> logLines = new LinkedList<>();
    private final List<BluetoothDevice> foundDevices = new ArrayList<>();
    private int txCount = 0;
    private int rxCount = 0;

    public BtechRelayDropDownReceiver(MapView mapView,
                                     Context pluginContext,
                                     BtConnectionManager btManager,
                                     ContactTracker contactTracker) {
        super(mapView);
        this.pluginContext = pluginContext;
        this.btManager = btManager;
        this.contactTracker = contactTracker;

        // Register as listener for connection and contact updates
        btManager.addListener(this);
        contactTracker.setListener(this);
    }

    public void setCotBridge(CotBridge cotBridge) {
        this.cotBridge = cotBridge;
    }

    public void setEncryptionManager(EncryptionManager encryptionManager) {
        this.encryptionManager = encryptionManager;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (SHOW_PLUGIN.equals(action)) {
            showDropDown(createView(),
                    HALF_WIDTH, FULL_HEIGHT,
                    FULL_WIDTH, HALF_HEIGHT,
                    false, this);
        }
    }

    /**
     * Create the main plugin UI view by inflating the XML layout.
     */
    private View createView() {
        LayoutInflater inflater = LayoutInflater.from(pluginContext);
        rootView = inflater.inflate(
                pluginContext.getResources().getIdentifier(
                        "btechrelay_dropdown", "layout",
                        pluginContext.getPackageName()),
                null);

        // Bind views
        bindViews();

        // Restore actual connection state (survives dropdown close/reopen)
        if (btManager.isConnected()) {
            updateConnectionUI(true, btManager.getConnectedDeviceName());
        } else {
            updateConnectionUI(false, null);
        }

        // Set callsign from ATAK self marker
        String callsign = MapView.getMapView().getSelfMarker().getMetaString("callsign","UNKNOWN");
        if (callsignText != null) {
            callsignText.setText(callsign);
        }

        // Make log scrollable inside the outer ScrollView
        if (logText != null) {
            logText.setMovementMethod(new ScrollingMovementMethod());
            logText.setOnTouchListener((v, event) -> {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return false;
            });
        }

        // Load saved state into switches BEFORE attaching listeners
        updateStatusFields();
        // Restore packet counts in UI
        updatePacketCount();
        updateContactCount();
        // Now attach change listeners so user interactions are wired
        setupListeners();
        // Re-render the log from memory
        refreshLogView();
        appendLog("BTECH Relay ready");
        return rootView;
    }

    private void bindViews() {
        statusDot = rootView.findViewById(getId("status_dot"));
        statusText = rootView.findViewById(getId("status_text"));
        deviceName = rootView.findViewById(getId("device_name"));
        callsignText = rootView.findViewById(getId("text_callsign"));
        contactsText = rootView.findViewById(getId("text_contacts"));
        packetsText = rootView.findViewById(getId("text_packets"));
        logText = rootView.findViewById(getId("text_log"));
        encryptionStatusText = rootView.findViewById(getId("text_encryption_status"));
        beaconIntervalText = rootView.findViewById(getId("text_beacon_interval"));
        teamColorText = rootView.findViewById(getId("text_team_color"));
        btnScan = rootView.findViewById(getId("btn_scan"));
        btnDisconnect = rootView.findViewById(getId("btn_disconnect"));

        // Interactive switches
        switchEncryption = rootView.findViewById(getId("switch_encryption"));
        passphraseRow = rootView.findViewById(getId("passphrase_row"));
        editPassphrase = rootView.findViewById(getId("edit_passphrase"));
        btnSetPassphrase = rootView.findViewById(getId("btn_set_passphrase"));
    }

    private void setupListeners() {
        btnScan.setOnClickListener(v -> {
            appendLog("Scanning paired devices...");
            foundDevices.clear();
            btManager.startScan();
            // After scan completes (synchronous for paired devices),
            // show picker if multiple devices found
            getMapView().postDelayed(this::showDevicePicker, 500);
        });

        btnDisconnect.setOnClickListener(v -> {
            btManager.disconnect();
        });

        // --- Encryption switch ---
        if (switchEncryption != null) {
            switchEncryption.setOnCheckedChangeListener((buttonView, isChecked) -> {
                SharedPreferences prefs = PreferenceManager
                        .getDefaultSharedPreferences(getMapView().getContext());
                prefs.edit().putBoolean(SettingsFragment.PREF_ENCRYPTION_ENABLED, isChecked).apply();

                if (passphraseRow != null) {
                    passphraseRow.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                }

                if (!isChecked && encryptionManager != null) {
                    encryptionManager.setPassphrase(null);
                    updateEncryptionStatus();
                    appendLog("Encryption disabled");
                } else if (isChecked) {
                    // Check if passphrase already set
                    String existing = SettingsFragment.getEncryptionPassphrase(
                            getMapView().getContext());
                    if (existing != null && !existing.isEmpty() && encryptionManager != null) {
                        encryptionManager.setPassphrase(existing);
                        updateEncryptionStatus();
                        appendLog("Encryption enabled (AES-256)");
                    } else {
                        updateEncryptionStatus();
                        appendLog("Set passphrase to enable encryption");
                    }
                }
            });
        }

        // --- Set passphrase button ---
        if (btnSetPassphrase != null) {
            btnSetPassphrase.setOnClickListener(v -> {
                if (editPassphrase == null) return;
                String pass = editPassphrase.getText().toString().trim();
                if (pass.isEmpty()) {
                    appendLog("Passphrase cannot be empty");
                    return;
                }
                SharedPreferences prefs = PreferenceManager
                        .getDefaultSharedPreferences(getMapView().getContext());
                prefs.edit().putString(SettingsFragment.PREF_ENCRYPTION_PASSPHRASE, pass).apply();

                if (encryptionManager != null) {
                    encryptionManager.setPassphrase(pass);
                }
                editPassphrase.setText("");
                updateEncryptionStatus();
                appendLog("Passphrase set — encryption active");
            });
        }

        // Quick action buttons
        View btnBeacon = rootView.findViewById(getId("btn_send_beacon"));
        if (btnBeacon != null) {
            btnBeacon.setOnClickListener(v -> sendManualBeacon());
        }

        View btnPing = rootView.findViewById(getId("btn_send_ping"));
        if (btnPing != null) {
            btnPing.setOnClickListener(v -> sendPing());
        }

        View btnSettings = rootView.findViewById(getId("btn_settings"));
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showSettingsDialog());
        }
    }

    private int getId(String name) {
        return pluginContext.getResources().getIdentifier(
                name, "id", pluginContext.getPackageName());
    }

    // --- Connection Listener callbacks ---

    @Override
    public void onConnected(BluetoothDevice device) {
        String name = device != null ? device.getName() : "Unknown";
        if (name == null) name = device != null ? device.getAddress() : "Radio";
        final String displayName = name;
        getMapView().post(() -> {
            updateConnectionUI(true, displayName);
            appendLog("Connected to " + displayName);
        });
    }

    @Override
    public void onDisconnected(String reason) {
        getMapView().post(() -> {
            updateConnectionUI(false, null);
            appendLog("Disconnected: " + reason);
        });
    }

    @Override
    public void onError(String error) {
        getMapView().post(() -> {
            appendLog("Error: " + error);
        });
    }

    @Override
    public void onDeviceFound(BluetoothDevice device) {
        foundDevices.add(device);
        String name = device != null ? device.getName() : "Unknown";
        getMapView().post(() -> {
            appendLog("Found: " + name);
        });
    }

    // --- Contact Listener callbacks ---

    @Override
    public void onContactUpdated(RadioContact contact) {
        getMapView().post(() -> {
            updateContactCount();
            appendLog("Contact: " + contact.getCallsign()
                    + " (" + String.format(Locale.US, "%.4f, %.4f",
                    contact.getLatitude(), contact.getLongitude()) + ")");
        });
    }

    @Override
    public void onContactRemoved(RadioContact contact) {
        getMapView().post(() -> {
            updateContactCount();
            appendLog("Contact lost: " + contact.getCallsign());
        });
    }

    @Override
    public void onContactCountChanged(int count) {
        getMapView().post(this::updateContactCount);
    }

    // --- PacketCountListener callback ---

    @Override
    public void onPacketReceived() {
        rxCount++;
        getMapView().post(this::updatePacketCount);
    }

    public void incrementTxCount() {
        txCount++;
        getMapView().post(this::updatePacketCount);
    }

    // --- UI update methods ---

    private void updateConnectionUI(boolean connected, String device) {
        if (statusDot != null) {
            statusDot.setBackgroundColor(connected ? 0xFF4CAF50 : 0xFFFF0000);
        }
        if (statusText != null) {
            statusText.setText(connected ? "Connected" : "Disconnected");
        }
        if (deviceName != null) {
            if (connected && device != null) {
                deviceName.setText(device);
                deviceName.setVisibility(View.VISIBLE);
            } else {
                deviceName.setVisibility(View.GONE);
            }
        }
        if (btnScan != null) btnScan.setEnabled(!connected);
        if (btnDisconnect != null) btnDisconnect.setEnabled(connected);
    }

    private void updateContactCount() {
        if (contactsText != null) {
            int active = contactTracker.getActiveCount();
            int total = contactTracker.getTotalCount();
            contactsText.setText(active + " active / " + total + " total");
        }
    }

    private void updatePacketCount() {
        if (packetsText != null) {
            packetsText.setText(txCount + " / " + rxCount);
        }
    }

    private void updateStatusFields() {
        Context ctx = getMapView().getContext();

        boolean encOn = SettingsFragment.isEncryptionEnabled(ctx);
        if (switchEncryption != null) {
            switchEncryption.setChecked(encOn);
        }

        // Show/hide passphrase row
        if (passphraseRow != null) {
            passphraseRow.setVisibility(encOn ? View.VISIBLE : View.GONE);
        }

        updateEncryptionStatus();

        // Beacon interval
        int beaconSec = SettingsFragment.getBeaconIntervalSec(ctx);
        if (beaconIntervalText != null) {
            beaconIntervalText.setText(beaconSec + "s");
        }

        // Team color (ATAK preference)
        try {
            String teamColor = com.atakmap.android.chat.ChatManagerMapComponent.getTeamName();
            if (teamColorText != null) {
                teamColorText.setText(teamColor != null ? teamColor : "Cyan");
            }
        } catch (Exception ignored) {
        }
    }

    private void updateEncryptionStatus() {
        if (encryptionStatusText == null) return;
        boolean encOn = SettingsFragment.isEncryptionEnabled(getMapView().getContext());
        String pass = SettingsFragment.getEncryptionPassphrase(getMapView().getContext());
        if (encOn && pass != null && !pass.isEmpty()) {
            encryptionStatusText.setText("\u2705 AES-256 active");
            encryptionStatusText.setTextColor(0xFF4CAF50);
        } else if (encOn) {
            encryptionStatusText.setText("\u26A0 Set passphrase to activate");
            encryptionStatusText.setTextColor(0xFFFF9800);
        } else {
            encryptionStatusText.setText("All radios must share the same passphrase");
            encryptionStatusText.setTextColor(0xFF888888);
        }
    }

    private void refreshLogView() {
        if (logText != null && !logLines.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String l : logLines) {
                sb.append(l).append("\n");
            }
            logText.setText(sb.toString());
        }
    }

    private void appendLog(String message) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss",
                Locale.US);
        String line = sdf.format(new Date()) + " " + message;

        logLines.addLast(line);
        while (logLines.size() > MAX_LOG_LINES) {
            logLines.removeFirst();
        }

        refreshLogView();
        Log.d(TAG, message);
    }

    // --- Actions ---

    private void showDevicePicker() {
        if (foundDevices.isEmpty()) {
            appendLog("No paired devices found");
            return;
        }

        if (foundDevices.size() == 1) {
            // Only one device — connect directly
            BluetoothDevice device = foundDevices.get(0);
            String name = device.getName() != null ? device.getName() : device.getAddress();
            appendLog("Connecting to " + name + "...");
            btManager.connect(device);
            return;
        }

        // Multiple devices — show picker dialog
        String[] names = new String[foundDevices.size()];
        for (int i = 0; i < foundDevices.size(); i++) {
            BluetoothDevice d = foundDevices.get(i);
            String n = d.getName() != null ? d.getName() : "Unknown";
            names[i] = n + " [" + d.getAddress() + "]";
        }

        try {
            Context ctx = getMapView().getContext();
            new AlertDialog.Builder(ctx)
                    .setTitle("Select Radio")
                    .setItems(names, (dialog, which) -> {
                        BluetoothDevice selected = foundDevices.get(which);
                        String sn = selected.getName() != null ? selected.getName() : selected.getAddress();
                        appendLog("Connecting to " + sn + "...");
                        btManager.connect(selected);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing device picker", e);
            appendLog("Error showing device picker");
        }
    }

    private void showSettingsDialog() {
        Context ctx = getMapView().getContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        // Build a custom dialog with EditTexts for key settings
        android.widget.ScrollView scrollView = new android.widget.ScrollView(ctx);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(ctx);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 16);


        // Beacon interval field
        TextView labelBeacon = new TextView(ctx);
        labelBeacon.setText("\nGPS Beacon Interval (seconds)");
        labelBeacon.setTextColor(0xFFAAAAAA);
        layout.addView(labelBeacon);
        EditText editBeacon = new EditText(ctx);
        editBeacon.setText(prefs.getString(SettingsFragment.PREF_BEACON_INTERVAL,
                SettingsFragment.DEFAULT_BEACON_INTERVAL));
        editBeacon.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        layout.addView(editBeacon);

        // Team color is controlled by ATAK core settings (locationTeam). Plugin no longer overrides it.

        scrollView.addView(layout);

        new AlertDialog.Builder(ctx)
                .setTitle("BTECH Relay Settings")
                .setView(scrollView)
                .setPositiveButton("Save", (dialog, which) -> {
                    SharedPreferences.Editor editor = prefs.edit();

                    String newBeacon = editBeacon.getText().toString().trim();
                    if (!newBeacon.isEmpty()) {
                        editor.putString(SettingsFragment.PREF_BEACON_INTERVAL, newBeacon);
                        if (beaconIntervalText != null)
                            beaconIntervalText.setText(newBeacon + "s");
                    }

                    editor.apply();
                    appendLog("Settings saved");
                    try {
                        AtakBroadcast.getInstance().sendBroadcast(
                                new Intent(BtechRelayMapComponent.ACTION_BEACON_INTERVAL_CHANGED));
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendManualBeacon() {
        if (cotBridge != null && btManager.isConnected()) {
            // Get self location from ATAK
            com.atakmap.android.maps.MapItem self =
                    getMapView().getSelfMarker();
            if (self != null && self instanceof com.atakmap.android.maps.PointMapItem) {
                com.atakmap.coremap.maps.coords.GeoPoint gp =
                        ((com.atakmap.android.maps.PointMapItem) self).getPoint();
                cotBridge.sendPositionOverRadio(
                        gp.getLatitude(), gp.getLongitude(),
                        gp.getAltitude(), 0, 0, -1);
                txCount++;
                updatePacketCount();
                appendLog("Beacon sent");
            } else {
                appendLog("No self-location available");
            }
        } else {
            appendLog("Not connected");
        }
    }

    private void sendPing() {
        if (cotBridge != null && btManager.isConnected()) {
            String callsign = MapView.getMapView().getSelfMarker().getMetaString("callsign","UNKNOWN");
            try {
                com.btechrelay.plugin.protocol.BtechRelayPacket packet =
                        com.btechrelay.plugin.protocol.BtechRelayPacket
                                .createPingPacket(com.btechrelay.plugin.util.CallsignUtil.toRadioCallsign(callsign));
                byte[] packetBytes = packet.encode();
                if (encryptionManager != null && encryptionManager.isEnabled()) {
                    packetBytes = encryptionManager.encrypt(packetBytes);
                    if (packetBytes == null) {
                        appendLog("Ping encryption failed");
                        return;
                    }
                }
                com.btechrelay.plugin.ax25.Ax25Frame frame =
                        com.btechrelay.plugin.ax25.Ax25Frame
                                .createBtechRelayFrame(callsign, 0, packetBytes);
                byte[] ax25 = frame.encode();
                btManager.sendKissFrame(ax25);
                txCount++;
                updatePacketCount();
                appendLog("Ping sent");
            } catch (Exception e) {
                appendLog("Ping failed: " + e.getMessage());
            }
        } else {
            appendLog("Not connected");
        }
    }

    @Override
    public void onDropDownSelectionRemoved() { }

    @Override
    public void onDropDownClose() { }

    @Override
    public void onDropDownSizeChanged(double width, double height) { }

    @Override
    public void onDropDownVisible(boolean visible) { }

    @Override
    public void disposeImpl() {
        // Unregister listeners
        btManager.removeListener(this);
        contactTracker.setListener(null);
    }
}
