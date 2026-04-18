package com.btechrelay.plugin.voice;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

/**
 * Manages audio routing between the phone and the Bluetooth radio.
 *
 * Ensures audio is properly directed to/from the BTECH radio's HFP
 * connection for voice communication, while not interfering with
 * normal phone audio when PTT is not active.
 *
 * NOTE: Phase 5 implementation. Requires hardware testing.
 */
public class AudioRouter {

    private static final String TAG = "BtechRelay.Audio";

    private final Context context;
    private final AudioManager audioManager;

    /** Original audio mode before we changed it */
    private int originalAudioMode = AudioManager.MODE_NORMAL;
    private boolean originalSpeakerphone = false;

    private boolean isRouted = false;

    public AudioRouter(Context context) {
        this.context = context;
        this.audioManager = (AudioManager)
                context.getSystemService(Context.AUDIO_SERVICE);
    }

    /**
     * Route audio to the Bluetooth radio for PTT.
     * Saves current audio state so it can be restored later.
     */
    public void routeToRadio() {
        if (isRouted) {
            Log.d(TAG, "Already routed to radio");
            return;
        }

        // Save current state
        originalAudioMode = audioManager.getMode();
        originalSpeakerphone = audioManager.isSpeakerphoneOn();

        // Set communication mode and enable Bluetooth SCO
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(false);
        audioManager.startBluetoothSco();
        audioManager.setBluetoothScoOn(true);

        isRouted = true;
        Log.d(TAG, "Audio routed to Bluetooth radio");
    }

    /**
     * Restore audio routing to normal after PTT.
     */
    public void restoreNormal() {
        if (!isRouted) return;

        audioManager.setBluetoothScoOn(false);
        audioManager.stopBluetoothSco();
        audioManager.setSpeakerphoneOn(originalSpeakerphone);
        audioManager.setMode(originalAudioMode);

        isRouted = false;
        Log.d(TAG, "Audio restored to normal routing");
    }

    /**
     * Check if Bluetooth SCO (audio) is available.
     */
    public boolean isBluetoothScoAvailable() {
        return audioManager.isBluetoothScoAvailableOffCall();
    }

    /**
     * Check if we have Bluetooth audio connected.
     */
    public boolean isBluetoothAudioConnected() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioDeviceInfo[] devices = audioManager.getDevices(
                    AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo device : devices) {
                if (device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    return true;
                }
            }
            return false;
        }

        // Fallback for older API levels
        return audioManager.isBluetoothScoOn();
    }

    /**
     * Get current routing description (for debug UI).
     */
    public String getRoutingDescription() {
        if (isRouted) {
            return "Radio (Bluetooth SCO)";
        }
        if (audioManager.isSpeakerphoneOn()) {
            return "Speaker";
        }
        return "Default";
    }

    /**
     * Clean up.
     */
    public void dispose() {
        restoreNormal();
        Log.d(TAG, "AudioRouter disposed");
    }
}
