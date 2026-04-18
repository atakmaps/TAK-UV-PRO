package com.btechrelay.plugin.kiss;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static com.btechrelay.plugin.kiss.KissConstants.*;

/**
 * Decodes KISS TNC frames received from the BTECH radio.
 *
 * Accumulates bytes from the Bluetooth SPP stream and extracts complete
 * AX.25 frames delimited by FEND (0xC0) bytes.
 *
 * Handles transparency escaping:
 *   FESC TFEND → FEND (0xC0)
 *   FESC TFESC → FESC (0xDB)
 *
 * This decoder is stateful — call decode() with each chunk of bytes
 * received from Bluetooth. It will return zero or more complete frames
 * as they become available.
 */
public class KissFrameDecoder {

    private final ByteArrayOutputStream currentFrame = new ByteArrayOutputStream();
    private boolean inFrame = false;
    private boolean escaped = false;

    /**
     * Feed received bytes into the decoder.
     *
     * @param data Raw bytes received from Bluetooth SPP
     * @return Array of complete AX.25 frames (stripped of KISS framing).
     *         May be empty if no complete frames are available yet.
     */
    public byte[][] decode(byte[] data) {
        List<byte[]> frames = new ArrayList<>();

        for (byte b : data) {
            if (b == FEND) {
                if (inFrame && currentFrame.size() > 0) {
                    // End of frame — extract the AX.25 data
                    byte[] frameData = currentFrame.toByteArray();
                    if (frameData.length > 1) {
                        // First byte is the command byte; extract just the data
                        byte cmdByte = frameData[0];
                        int command = cmdByte & 0x0F;

                        if (command == CMD_DATA) {
                            // Data frame — strip the command byte
                            byte[] ax25Data = new byte[frameData.length - 1];
                            System.arraycopy(frameData, 1, ax25Data, 0,
                                    ax25Data.length);
                            frames.add(ax25Data);
                        }
                        // Other commands (TXDELAY, etc.) are ignored for now
                    }
                }
                // Reset for next frame
                currentFrame.reset();
                inFrame = true;
                escaped = false;

            } else if (inFrame) {
                if (escaped) {
                    // Previous byte was FESC — handle transposed characters
                    if (b == TFEND) {
                        currentFrame.write(FEND & 0xFF);
                    } else if (b == TFESC) {
                        currentFrame.write(FESC & 0xFF);
                    } else {
                        // Protocol error — ignore and continue
                    }
                    escaped = false;
                } else if (b == FESC) {
                    escaped = true;
                } else {
                    currentFrame.write(b & 0xFF);
                }
            }
            // If not in a frame, discard the byte (garbage before first FEND)
        }

        return frames.toArray(new byte[0][]);
    }

    /**
     * Reset the decoder state. Call this after a connection reset.
     */
    public void reset() {
        currentFrame.reset();
        inFrame = false;
        escaped = false;
    }
}
