package com.uvpro.plugin.kiss;

/**
 * KISS TNC protocol constants.
 *
 * KISS (Keep It Simple, Stupid) is the protocol used between the host (Android)
 * and the TNC (BTECH radio's built-in packet modem) over Bluetooth SPP.
 *
 * Reference: https://www.ax25.net/kiss.aspx
 */
public final class KissConstants {

    private KissConstants() {} // Utility class

    /** Frame End — delimits KISS frames */
    public static final byte FEND = (byte) 0xC0;

    /** Frame Escape — begins an escape sequence */
    public static final byte FESC = (byte) 0xDB;

    /** Transposed Frame End — represents FEND within frame data */
    public static final byte TFEND = (byte) 0xDC;

    /** Transposed Frame Escape — represents FESC within frame data */
    public static final byte TFESC = (byte) 0xDD;

    // --- KISS command types (low nibble of command byte) ---

    /** Data frame — rest of frame is AX.25 data to transmit */
    public static final byte CMD_DATA = 0x00;

    /** TX Delay — next byte is keyup delay in 10ms units */
    public static final byte CMD_TXDELAY = 0x01;

    /** Persistence parameter (0-255) */
    public static final byte CMD_PERSISTENCE = 0x02;

    /** Slot time in 10ms units */
    public static final byte CMD_SLOTTIME = 0x03;

    /** TX tail time in 10ms units */
    public static final byte CMD_TXTAIL = 0x04;

    /** Full duplex: 0=half, nonzero=full */
    public static final byte CMD_FULLDUPLEX = 0x05;

    /** Device-specific hardware command */
    public static final byte CMD_SETHARDWARE = 0x06;

    /** Return — exit KISS mode */
    public static final byte CMD_RETURN = (byte) 0xFF;
}
