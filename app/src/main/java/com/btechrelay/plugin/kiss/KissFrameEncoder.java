package com.btechrelay.plugin.kiss;

import java.io.ByteArrayOutputStream;

import static com.btechrelay.plugin.kiss.KissConstants.*;

/**
 * Encodes data into KISS TNC frames for transmission.
 *
 * Takes an AX.25 frame (raw bytes) and wraps it in KISS framing:
 *   FEND | CMD_DATA | escaped_data | FEND
 *
 * Any FEND (0xC0) bytes in the data are escaped as FESC TFEND.
 * Any FESC (0xDB) bytes in the data are escaped as FESC TFESC.
 */
public class KissFrameEncoder {

    /**
     * Encode an AX.25 frame into a KISS frame ready for transmission
     * over the Bluetooth SPP link to the BTECH radio.
     *
     * @param ax25Data The raw AX.25 frame bytes
     * @return KISS-encoded frame bytes
     */
    public byte[] encode(byte[] ax25Data) {
        return encode(ax25Data, CMD_DATA, 0);
    }

    /**
     * Encode data with a specific KISS command and port.
     *
     * @param data    The payload bytes
     * @param command KISS command (low nibble)
     * @param port    TNC port (high nibble, 0-15)
     * @return KISS-encoded frame bytes
     */
    public byte[] encode(byte[] data, byte command, int port) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 4);

        // Opening FEND
        out.write(FEND & 0xFF);

        // Command byte: high nibble = port, low nibble = command
        byte cmdByte = (byte) ((port << 4) | (command & 0x0F));
        out.write(cmdByte & 0xFF);

        // Data with transparency escaping
        for (byte b : data) {
            if (b == FEND) {
                out.write(FESC & 0xFF);
                out.write(TFEND & 0xFF);
            } else if (b == FESC) {
                out.write(FESC & 0xFF);
                out.write(TFESC & 0xFF);
            } else {
                out.write(b & 0xFF);
            }
        }

        // Closing FEND
        out.write(FEND & 0xFF);

        return out.toByteArray();
    }

    /**
     * Encode a KISS control command (e.g., TX delay, persistence).
     *
     * @param command The KISS command
     * @param value   The parameter value
     * @return KISS-encoded command frame
     */
    public byte[] encodeCommand(byte command, byte value) {
        return new byte[]{FEND, command, value, FEND};
    }
}
