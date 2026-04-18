package com.btechrelay.plugin.crypto;

import android.util.Log;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.security.spec.KeySpec;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-CBC encryption for BtechRelay radio packets.
 *
 * All nodes sharing the same passphrase can decrypt each other's packets.
 * Uses PBKDF2 to derive a 256-bit key from the passphrase.
 *
 * Wire format: [16-byte IV][encrypted payload]
 */
public class EncryptionManager {

    private static final String TAG = "BtechRelay.Crypto";
    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";
    private static final String KEY_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int KEY_LENGTH = 256;
    private static final int ITERATIONS = 10000;
    private static final int IV_LENGTH = 16;
    // Fixed salt for key derivation — all nodes must use the same salt
    // so that the same passphrase produces the same key
    private static final byte[] SALT = {
            0x4f, 0x70, 0x65, 0x6e, 0x52, 0x65, 0x6c, 0x61,
            0x79, 0x2d, 0x53, 0x41, 0x4c, 0x54, 0x76, 0x31
    }; // "BtechRelay-SALTv1"

    private SecretKey secretKey;
    private boolean enabled = false;
    private final SecureRandom random = new SecureRandom();

    /**
     * Set the encryption passphrase.
     * Call with null or empty string to disable encryption.
     */
    public void setPassphrase(String passphrase) {
        if (passphrase == null || passphrase.isEmpty()) {
            enabled = false;
            secretKey = null;
            Log.d(TAG, "Encryption disabled");
            return;
        }

        try {
            KeySpec spec = new PBEKeySpec(
                    passphrase.toCharArray(), SALT, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KEY_ALGORITHM);
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            secretKey = new SecretKeySpec(keyBytes, "AES");
            enabled = true;
            Log.d(TAG, "Encryption enabled with passphrase");
        } catch (Exception e) {
            Log.e(TAG, "Failed to derive encryption key", e);
            enabled = false;
            secretKey = null;
        }
    }

    /**
     * Encrypt a payload.
     * @param plaintext The raw data to encrypt
     * @return [16-byte IV][ciphertext], or the original plaintext if encryption is disabled
     */
    public byte[] encrypt(byte[] plaintext) {
        if (!enabled || secretKey == null) return plaintext;

        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            // Prepend IV to ciphertext
            ByteBuffer result = ByteBuffer.allocate(IV_LENGTH + ciphertext.length);
            result.put(iv);
            result.put(ciphertext);
            return result.array();
        } catch (Exception e) {
            Log.e(TAG, "Encryption failed — aborting send", e);
            return null;
        }
    }

    /**
     * Decrypt a payload.
     * @param data [16-byte IV][ciphertext]
     * @return Decrypted plaintext, or null if decryption fails
     */
    public byte[] decrypt(byte[] data) {
        if (!enabled || secretKey == null) return data;

        if (data.length < IV_LENGTH + 1) {
            Log.w(TAG, "Data too short to contain IV + ciphertext");
            return null;
        }

        try {
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(data, 0, iv, 0, IV_LENGTH);

            byte[] ciphertext = new byte[data.length - IV_LENGTH];
            System.arraycopy(data, IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            Log.w(TAG, "Decryption failed — wrong passphrase or unencrypted data");
            return null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
