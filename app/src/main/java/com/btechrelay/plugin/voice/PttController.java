package com.btechrelay.plugin.voice;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.util.Log;

/**
 * Push-To-Talk (PTT) controller for voice communication over BTECH radio.
 *
 * The BTECH UV-PRO supports Bluetooth HFP (Hands-Free Profile). When connected
 * via HFP, the radio acts as a Bluetooth audio device. Voice is routed through
 * the phone's Bluetooth audio → radio → transmitted over the air.
 *
 * PTT activation:
 * - The UV-PRO supports VOX (Voice-Operated-Transmit), so when audio is sent
 *   to the radio via HFP, it will automatically begin transmitting.
 * - Alternatively, some BTECH radios support a Bluetooth PTT button/command.
 *
 * This controller manages:
 * - Starting/stopping SCO audio for voice path
 * - Audio routing between phone and Bluetooth radio
 * - PTT state management
 *
 * NOTE: This is Phase 5 functionality. Basic scaffold provided here,
 *       full implementation requires testing with actual hardware.
 */
public class PttController {

    private static final String TAG = "BtechRelay.PTT";

    public enum PttState {
        IDLE,           // Not actively transmitting or receiving
        TRANSMITTING,   // PTT active, audio going to radio
        RECEIVING       // Audio coming from radio
    }

    private final Context context;
    private final AudioManager audioManager;
    private BluetoothHeadset bluetoothHeadset;
    private BluetoothDevice connectedDevice;

    private PttState state = PttState.IDLE;
    private PttListener listener;

    private boolean scoConnected = false;
    private boolean hfpConnected = false;

    public interface PttListener {
        void onPttStateChanged(PttState newState);
        void onHfpConnectionChanged(boolean connected);
        void onError(String message);
    }

    public PttController(Context context) {
        this.context = context;
        this.audioManager = (AudioManager)
                context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void setListener(PttListener listener) {
        this.listener = listener;
    }

    /**
     * Initialize HFP connection to the radio.
     * Should be called after Bluetooth SPP is established and we know
     * which device is the BTECH radio.
     */
    public void initialize(BluetoothDevice radioDevice) {
        this.connectedDevice = radioDevice;

        // Register for SCO state changes
        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        context.registerReceiver(scoReceiver, filter);

        // Get BluetoothHeadset proxy for HFP operations
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            adapter.getProfileProxy(context, profileListener,
                    BluetoothProfile.HEADSET);
        }

        Log.d(TAG, "PTT controller initialized for " + radioDevice.getName());
    }

    /**
     * Start PTT — begin transmitting voice over radio.
     *
     * This starts the SCO audio connection, which routes the phone's
     * microphone through the Bluetooth audio to the radio. With VOX enabled
     * on the radio, it will automatically begin transmitting.
     */
    public void startTransmit() {
        if (state == PttState.TRANSMITTING) {
            Log.d(TAG, "Already transmitting");
            return;
        }

        if (!hfpConnected) {
            Log.w(TAG, "HFP not connected — cannot start PTT");
            if (listener != null) {
                listener.onError("Radio HFP not connected");
            }
            return;
        }

        Log.i(TAG, "Starting PTT transmit");

        // Route audio to Bluetooth
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.startBluetoothSco();
        audioManager.setBluetoothScoOn(true);

        state = PttState.TRANSMITTING;
        if (listener != null) {
            listener.onPttStateChanged(state);
        }
    }

    /**
     * Stop PTT — stop transmitting.
     */
    public void stopTransmit() {
        if (state != PttState.TRANSMITTING) {
            return;
        }

        Log.i(TAG, "Stopping PTT transmit");

        audioManager.setBluetoothScoOn(false);
        audioManager.stopBluetoothSco();
        audioManager.setMode(AudioManager.MODE_NORMAL);

        state = PttState.IDLE;
        if (listener != null) {
            listener.onPttStateChanged(state);
        }
    }

    /**
     * Toggle PTT state.
     */
    public void togglePtt() {
        if (state == PttState.TRANSMITTING) {
            stopTransmit();
        } else {
            startTransmit();
        }
    }

    /**
     * Get current PTT state.
     */
    public PttState getState() {
        return state;
    }

    /**
     * Check if HFP is connected.
     */
    public boolean isHfpConnected() {
        return hfpConnected;
    }

    /**
     * Clean up resources.
     */
    public void dispose() {
        stopTransmit();

        try {
            context.unregisterReceiver(scoReceiver);
        } catch (Exception e) {
            // May not be registered
        }

        if (bluetoothHeadset != null) {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                adapter.closeProfileProxy(BluetoothProfile.HEADSET,
                        bluetoothHeadset);
            }
        }

        Log.d(TAG, "PTT controller disposed");
    }

    // --- Bluetooth callbacks ---

    private final BluetoothProfile.ServiceListener profileListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile,
                                               BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.HEADSET) {
                        bluetoothHeadset = (BluetoothHeadset) proxy;

                        // Check if our radio is in the connected devices
                        for (BluetoothDevice device :
                                bluetoothHeadset.getConnectedDevices()) {
                            if (device.equals(connectedDevice)) {
                                hfpConnected = true;
                                Log.i(TAG, "HFP connected to radio");
                                if (listener != null) {
                                    listener.onHfpConnectionChanged(true);
                                }
                                break;
                            }
                        }

                        if (!hfpConnected) {
                            Log.w(TAG, "Radio not found in HFP devices. "
                                    + "Pair the radio via HFP in system "
                                    + "Bluetooth settings.");
                            if (listener != null) {
                                listener.onHfpConnectionChanged(false);
                            }
                        }
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        bluetoothHeadset = null;
                        hfpConnected = false;
                        Log.w(TAG, "HFP disconnected");
                        if (listener != null) {
                            listener.onHfpConnectionChanged(false);
                        }
                    }
                }
            };

    private final BroadcastReceiver scoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int scoState = intent.getIntExtra(
                    AudioManager.EXTRA_SCO_AUDIO_STATE,
                    AudioManager.SCO_AUDIO_STATE_ERROR);

            switch (scoState) {
                case AudioManager.SCO_AUDIO_STATE_CONNECTED:
                    scoConnected = true;
                    Log.d(TAG, "SCO audio connected");
                    break;
                case AudioManager.SCO_AUDIO_STATE_DISCONNECTED:
                    scoConnected = false;
                    Log.d(TAG, "SCO audio disconnected");
                    if (state == PttState.TRANSMITTING) {
                        state = PttState.IDLE;
                        if (listener != null) {
                            listener.onPttStateChanged(state);
                        }
                    }
                    break;
                case AudioManager.SCO_AUDIO_STATE_ERROR:
                    Log.e(TAG, "SCO audio error");
                    break;
            }
        }
    };
}
