package com.uvpro.plugin.crypto;

import android.util.Log;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM RF envelope (v3) with per-payload salt and IV; KDF uses PBKDF2-HMAC-SHA256.
 *
 * <p>Envelope v3: {@code [0x03][16-byte salt][12-byte nonce][ciphertext || GCM tag]}.
 * Salt and nonce are random per payload; peers use matching operator-supplied RF crypto input.
 */
public class EncryptionManager {

    private static final String TAG = "UVPro.Crypto";
    private static final byte ENVELOPE_V3 = 0x03;
    private static final String GCM_CIPHER = "AES/GCM/NoPadding";
    private static final String PBKDF2 = "PBKDF2WithHmacSHA256";
    /** OWASP-aligned minimum for PBKDF2-HMAC-SHA256. */
    private static final int PBKDF2_ITERATIONS = 310_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_LEN = 16;
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;

    private char[] sharedSecretChars;
    private boolean enabled = false;
    private final SecureRandom random = new SecureRandom();

    /**
     * Supply the operator RF crypto string, or {@code null} / empty to disable RF crypto.
     */
    public void setSharedSecret(String secret) {
        wipeSharedSecret();
        if (secret == null || secret.isEmpty()) {
            enabled = false;
            Log.d(TAG, "RF crypto disabled");
            return;
        }
        sharedSecretChars = secret.toCharArray();
        enabled = true;
        Log.d(TAG, "RF crypto enabled");
    }

    /** Releases sensitive material from memory. */
    public void dispose() {
        wipeSharedSecret();
        enabled = false;
    }

    private void wipeSharedSecret() {
        if (sharedSecretChars != null) {
            Arrays.fill(sharedSecretChars, '\0');
            sharedSecretChars = null;
        }
    }

    /**
     * Encrypt a payload.
     *
     * @return v3 envelope, original plaintext if crypto disabled, or {@code null} on failure
     */
    public byte[] encrypt(byte[] plaintext) {
        if (!enabled || sharedSecretChars == null) {
            return plaintext;
        }
        if (plaintext == null) {
            return null;
        }
        byte[] key = null;
        try {
            byte[] salt = new byte[SALT_LEN];
            random.nextBytes(salt);
            key = deriveKey(sharedSecretChars, salt);
            byte[] iv = new byte[GCM_IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(GCM_CIPHER);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), gcmSpec);
            byte[] ciphertext = cipher.doFinal(plaintext);
            ByteBuffer out = ByteBuffer.allocate(1 + SALT_LEN + GCM_IV_LEN + ciphertext.length);
            out.put(ENVELOPE_V3);
            out.put(salt);
            out.put(iv);
            out.put(ciphertext);
            return out.array();
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "Encrypt failed", e);
            return null;
        } finally {
            if (key != null) {
                Arrays.fill(key, (byte) 0);
            }
        }
    }

    /**
     * Decrypt a v3 envelope.
     *
     * @return plaintext, original {@code data} if crypto disabled, or {@code null} on failure
     */
    public byte[] decrypt(byte[] data) {
        if (!enabled || sharedSecretChars == null) {
            return data;
        }
        if (data == null || data.length < 1 + SALT_LEN + GCM_IV_LEN + 16) {
            Log.w(TAG, "RF payload too short for crypto envelope");
            return null;
        }
        if (data[0] != ENVELOPE_V3) {
            Log.w(TAG, "Unsupported RF crypto envelope (peer may run an older plugin build)");
            return null;
        }
        byte[] key = null;
        try {
            ByteBuffer buf = ByteBuffer.wrap(data, 1, data.length - 1);
            byte[] salt = new byte[SALT_LEN];
            buf.get(salt);
            byte[] iv = new byte[GCM_IV_LEN];
            buf.get(iv);
            byte[] ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);
            key = deriveKey(sharedSecretChars, salt);
            Cipher cipher = Cipher.getInstance(GCM_CIPHER);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), gcmSpec);
            byte[] plain = cipher.doFinal(ciphertext);
            return Arrays.copyOf(plain, plain.length);
        } catch (GeneralSecurityException e) {
            Log.w(TAG, "Decrypt failed — wrong RF crypto string, corrupt data, or peer mismatch");
            return null;
        } finally {
            if (key != null) {
                Arrays.fill(key, (byte) 0);
            }
        }
    }

    private static byte[] deriveKey(char[] secret, byte[] salt) throws GeneralSecurityException {
        KeySpec spec = new PBEKeySpec(secret, salt, PBKDF2_ITERATIONS, KEY_BITS);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2);
        try {
            return factory.generateSecret(spec).getEncoded();
        } finally {
            ((PBEKeySpec) spec).clearPassword();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
