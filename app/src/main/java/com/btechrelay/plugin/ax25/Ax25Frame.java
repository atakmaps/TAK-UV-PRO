package com.btechrelay.plugin.ax25;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Represents an AX.25 frame — the data link layer protocol used by
 * amateur packet radio and APRS.
 *
 * AX.25 frame structure:
 *   [Dest Address (7)] [Src Address (7)] [Digipeater*] [Control (1)] [PID (1)] [Info (N)]
 *
 * Address format (7 bytes per address):
 *   6 bytes: Callsign (ASCII, left-justified, space-padded, shifted left 1 bit)
 *   1 byte:  SSID byte (contains SSID in bits 1-4, flags in other bits)
 *
 * We use the AX.25 UI (Unnumbered Information) frame type, which is connectionless.
 * This is the same type used by APRS.
 *
 * Control byte: 0x03 (UI frame)
 * PID byte: 0xF0 (no layer 3 protocol)
 */
public class Ax25Frame {

    /** UI frame control byte */
    public static final byte CONTROL_UI = 0x03;

    /** No Layer 3 protocol PID */
    public static final byte PID_NO_L3 = (byte) 0xF0;

    private String destCallsign;
    private int destSsid;
    private String srcCallsign;
    private int srcSsid;
    private byte[] infoField;

    public Ax25Frame() {}

    public Ax25Frame(String srcCallsign, int srcSsid,
                     String destCallsign, int destSsid,
                     byte[] infoField) {
        this.srcCallsign = srcCallsign;
        this.srcSsid = srcSsid;
        this.destCallsign = destCallsign;
        this.destSsid = destSsid;
        this.infoField = infoField;
    }

    /**
     * Encode this frame into raw bytes for KISS TNC transmission.
     */
    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Destination address (7 bytes)
        encodeAddress(out, destCallsign, destSsid, false);

        // Source address (7 bytes) — last address, so set the "last" bit
        encodeAddress(out, srcCallsign, srcSsid, true);

        // Control field (UI frame)
        out.write(CONTROL_UI & 0xFF);

        // Protocol ID (no layer 3)
        out.write(PID_NO_L3 & 0xFF);

        // Information field
        if (infoField != null) {
            out.write(infoField, 0, infoField.length);
        }

        return out.toByteArray();
    }

    /**
     * Decode raw AX.25 bytes into an Ax25Frame object.
     */
    public static Ax25Frame decode(byte[] data) {
        if (data == null || data.length < 16) {
            return null; // Minimum: 7 + 7 + 1 + 1 = 16 bytes
        }

        Ax25Frame frame = new Ax25Frame();

        // Decode destination address (bytes 0-6)
        frame.destCallsign = decodeCallsign(data, 0);
        frame.destSsid = (data[6] >> 1) & 0x0F;

        // Decode source address (bytes 7-13)
        frame.srcCallsign = decodeCallsign(data, 7);
        frame.srcSsid = (data[13] >> 1) & 0x0F;

        // Skip any digipeater addresses
        int pos = 14;
        if ((data[13] & 0x01) == 0) {
            // There are digipeater addresses — scan for the last one
            while (pos + 6 < data.length) {
                if ((data[pos + 6] & 0x01) != 0) {
                    pos += 7; // Skip this digipeater, it's the last
                    break;
                }
                pos += 7;
            }
        }

        // Control byte
        if (pos < data.length) {
            // byte control = data[pos];
            pos++;
        }

        // PID byte
        if (pos < data.length) {
            // byte pid = data[pos];
            pos++;
        }

        // Info field — remainder of the frame
        if (pos < data.length) {
            frame.infoField = Arrays.copyOfRange(data, pos, data.length);
        } else {
            frame.infoField = new byte[0];
        }

        return frame;
    }

    /**
     * Encode a callsign into the AX.25 address format.
     * Callsign is left-justified, space-padded to 6 chars, each byte shifted left 1 bit.
     */
    private static void encodeAddress(ByteArrayOutputStream out,
                                       String callsign, int ssid,
                                       boolean isLast) {
        // Pad callsign to 6 characters
        String padded = (callsign + "      ").substring(0, 6).toUpperCase();

        // Shift each character left by 1 bit
        for (int i = 0; i < 6; i++) {
            out.write((padded.charAt(i) << 1) & 0xFF);
        }

        // SSID byte: bits 1-4 are SSID, bit 0 is "last address" flag
        // bits 5-6 are reserved (set to 1), bit 7 is command/response
        int ssidByte = 0x60 | ((ssid & 0x0F) << 1);
        if (isLast) {
            ssidByte |= 0x01; // Set "last address" bit
        }
        out.write(ssidByte);
    }

    /**
     * Decode a callsign from AX.25 address bytes.
     */
    private static String decodeCallsign(byte[] data, int offset) {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            char c = (char) ((data[offset + i] & 0xFF) >> 1);
            if (c != ' ') {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    // --- Convenience factory methods ---

    /**
     * Create an AX.25 frame for transmitting our custom BtechRelay data.
     * Uses "OPENRL" as the destination (our protocol identifier).
     */
    public static Ax25Frame createBtechRelayFrame(String callsign, int ssid,
                                                  byte[] payload) {
        return new Ax25Frame(callsign, ssid, "OPENRL", 0, payload);
    }

    /**
     * Create a standard APRS position frame.
     */
    public static Ax25Frame createAprsFrame(String callsign, int ssid,
                                             String aprsPayload) {
        return new Ax25Frame(callsign, ssid, "APRS", 0,
                aprsPayload.getBytes(StandardCharsets.US_ASCII));
    }

    // --- Getters ---

    public String getDestCallsign() { return destCallsign; }
    public int getDestSsid() { return destSsid; }
    public String getSrcCallsign() { return srcCallsign; }
    public int getSrcSsid() { return srcSsid; }
    public byte[] getInfoField() { return infoField; }

    /**
     * Get the info field as a UTF-8 string.
     */
    public String getInfoString() {
        if (infoField == null) return "";
        return new String(infoField, StandardCharsets.UTF_8);
    }
}
