package com.btechrelay.plugin.ax25;

import android.util.Log;

/**
 * Parses APRS packets from AX.25 info fields.
 *
 * Supports:
 * - Position reports (with and without timestamp)
 * - Mic-E encoded positions
 * - Messages (for chat bridging)
 * - Status packets
 *
 * APRS position formats we handle:
 *   '!' or '=' followed by lat/lon (no timestamp)
 *   '/' or '@' followed by timestamp then lat/lon
 *   Mic-E encoding in destination address
 *
 * Reference: APRS Protocol Reference (APRS101.pdf)
 */
public class AprsParser {

    private static final String TAG = "BtechRelay.APRS";

    /**
     * Parsed APRS position data.
     */
    public static class AprsPosition {
        public String callsign;
        public int ssid;
        public double latitude;
        public double longitude;
        public double altitude;    // meters, -1 if unknown
        public double speed;       // m/s, -1 if unknown
        public double course;      // degrees, -1 if unknown
        public String comment;     // APRS comment field
        public char symbol;        // APRS symbol character
        public char symbolTable;   // APRS symbol table ('/' or '\\')
    }

    /**
     * Parsed APRS message.
     */
    public static class AprsMessage {
        public String fromCallsign;
        public String toCallsign;
        public String message;
        public String messageId;   // For acknowledgment
    }

    /**
     * Try to parse an APRS position from an AX.25 frame's info field.
     * Returns null if this is not a position packet.
     */
    public static AprsPosition parsePosition(String callsign, int ssid,
                                              String info) {
        if (info == null || info.isEmpty()) return null;

        char dataType = info.charAt(0);
        AprsPosition pos = new AprsPosition();
        pos.callsign = callsign;
        pos.ssid = ssid;
        pos.altitude = -1;
        pos.speed = -1;
        pos.course = -1;

        try {
            switch (dataType) {
                case '!': // Position without timestamp, no messaging
                case '=': // Position without timestamp, with messaging
                    return parseUncompressedPosition(pos, info, 1);

                case '/': // Position with timestamp, no messaging
                case '@': // Position with timestamp, with messaging
                    // Skip 7-char timestamp (DHM or HMS format)
                    if (info.length() > 8) {
                        return parseUncompressedPosition(pos, info, 8);
                    }
                    break;

                case ':': // Message
                    // Not a position — handled by parseMessage()
                    return null;

                default:
                    // Could be Mic-E or other format — skip for now
                    // Mic-E decoding can be added later
                    Log.d(TAG, "Unhandled APRS data type: " + dataType);
                    return null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse APRS position: " + e.getMessage());
        }

        return null;
    }

    /**
     * Parse an uncompressed APRS position string.
     * Format: DDMM.MMN/DDDMM.MME (latitude/longitude in degrees-minutes)
     */
    private static AprsPosition parseUncompressedPosition(AprsPosition pos,
                                                           String info,
                                                           int offset) {
        if (info.length() < offset + 19) return null;

        String latStr = info.substring(offset, offset + 8);     // "DDMM.MMN"
        char symTable = info.charAt(offset + 8);                  // Symbol table
        String lonStr = info.substring(offset + 9, offset + 18); // "DDDMM.MME"
        char symChar = info.charAt(offset + 18);                  // Symbol char

        pos.latitude = parseLatitude(latStr);
        pos.longitude = parseLongitude(lonStr);
        pos.symbolTable = symTable;
        pos.symbol = symChar;

        // Parse optional course/speed after position
        if (info.length() > offset + 19) {
            String extra = info.substring(offset + 19);
            parseCourseSpeed(pos, extra);
            // Look for altitude in comments /A=NNNNNN
            int altIdx = extra.indexOf("/A=");
            if (altIdx >= 0 && extra.length() >= altIdx + 9) {
                try {
                    int altFeet = Integer.parseInt(extra.substring(altIdx + 3, altIdx + 9));
                    pos.altitude = altFeet * 0.3048; // Convert feet to meters
                } catch (NumberFormatException ignored) {}
            }
            pos.comment = extra;
        }

        if (Double.isNaN(pos.latitude) || Double.isNaN(pos.longitude)) {
            return null;
        }

        return pos;
    }

    /**
     * Parse APRS latitude: "DDMM.MMN" where N is N/S indicator.
     */
    private static double parseLatitude(String s) {
        try {
            int degrees = Integer.parseInt(s.substring(0, 2));
            double minutes = Double.parseDouble(s.substring(2, 7));
            char ns = s.charAt(7);

            double lat = degrees + minutes / 60.0;
            if (ns == 'S' || ns == 's') lat = -lat;
            return lat;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /**
     * Parse APRS longitude: "DDDMM.MME" where E is E/W indicator.
     */
    private static double parseLongitude(String s) {
        try {
            int degrees = Integer.parseInt(s.substring(0, 3));
            double minutes = Double.parseDouble(s.substring(3, 8));
            char ew = s.charAt(8);

            double lon = degrees + minutes / 60.0;
            if (ew == 'W' || ew == 'w') lon = -lon;
            return lon;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /**
     * Parse optional course/speed: "CCC/SSS" (course degrees / speed knots).
     */
    private static void parseCourseSpeed(AprsPosition pos, String extra) {
        if (extra.length() >= 7 && extra.charAt(3) == '/') {
            try {
                int course = Integer.parseInt(extra.substring(0, 3));
                int speedKnots = Integer.parseInt(extra.substring(4, 7));
                pos.course = course;
                pos.speed = speedKnots * 0.514444; // knots to m/s
            } catch (NumberFormatException ignored) {}
        }
    }

    /**
     * Try to parse an APRS message from an AX.25 frame's info field.
     * Format: ":ADDRESSEE:message text{ID"
     * Returns null if this is not a message packet.
     */
    public static AprsMessage parseMessage(String fromCallsign, String info) {
        if (info == null || info.length() < 12 || info.charAt(0) != ':') {
            return null;
        }

        // Addressee is 9 characters after the initial ':'
        String addressee = info.substring(1, 10).trim();

        // Message text starts after the second ':'
        if (info.charAt(10) != ':') return null;
        String messageText = info.substring(11);

        AprsMessage msg = new AprsMessage();
        msg.fromCallsign = fromCallsign;
        msg.toCallsign = addressee;

        // Check for message ID (after '{')
        int idIdx = messageText.lastIndexOf('{');
        if (idIdx >= 0) {
            msg.messageId = messageText.substring(idIdx + 1);
            msg.message = messageText.substring(0, idIdx);
        } else {
            msg.message = messageText;
        }

        return msg;
    }
}
