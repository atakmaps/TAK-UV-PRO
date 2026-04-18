package com.btechrelay.plugin.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * BtechRelay custom packet format for efficient data transfer over radio.
 *
 * TLV (Type-Length-Value) structure:
 *   [Type (1 byte)] [Length (2 bytes, big-endian)] [Value (N bytes)]
 *
 * Type codes:
 *   0x01 = CoT XML (gzipped)
 *   0x02 = Chat message
 *   0x03 = GPS position (compact binary, 22 bytes)
 *   0x04 = Ping/discovery
 *   0x05 = Acknowledgment
 *   0x06 = CoT fragment (for large CoT that exceeds one frame)
 *
 * This sits INSIDE the AX.25 info field, which sits INSIDE a KISS frame:
 *   KISS → AX.25 → BtechRelayPacket
 */
public class BtechRelayPacket {

    // Packet type codes
    public static final byte TYPE_COT = 0x01;
    public static final byte TYPE_CHAT = 0x02;
    public static final byte TYPE_GPS = 0x03;
    public static final byte TYPE_PING = 0x04;
    public static final byte TYPE_ACK = 0x05;
    public static final byte TYPE_COT_FRAGMENT = 0x06;

    private byte type;
    private byte[] payload;

    public BtechRelayPacket(byte type, byte[] payload) {
        this.type = type;
        this.payload = payload;
    }

    /**
     * Encode this packet into bytes for the AX.25 info field.
     */
    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(3 + payload.length);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put(type);
        buf.putShort((short) payload.length);
        buf.put(payload);
        return buf.array();
    }

    /**
     * Decode an BtechRelayPacket from raw bytes.
     */
    public static BtechRelayPacket decode(byte[] data) {
        if (data == null || data.length < 3) return null;

        ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.BIG_ENDIAN);

        byte type = buf.get();
        int length = buf.getShort() & 0xFFFF;

        if (buf.remaining() < length) return null;

        byte[] payload = new byte[length];
        buf.get(payload);

        return new BtechRelayPacket(type, payload);
    }

    // --- Compact GPS Position encoding/decoding ---

    /**
     * Create a compact GPS position packet (22 bytes).
     *
     * Format:
     *   Callsign (6 bytes, ASCII padded)
     *   Latitude (4 bytes, int32, degrees * 1e7)
     *   Longitude (4 bytes, int32, degrees * 1e7)
     *   Altitude (2 bytes, int16, meters)
     *   Speed (2 bytes, uint16, m/s * 100)
     *   Course (2 bytes, uint16, degrees * 100)
     *   Flags (1 byte)
     *   Battery (1 byte, percentage)
     */
    public static BtechRelayPacket createGpsPacket(String callsign,
                                                   double lat, double lon,
                                                   double alt, double speed,
                                                   double course, int battery) {
        ByteBuffer buf = ByteBuffer.allocate(22);
        buf.order(ByteOrder.BIG_ENDIAN);

        // Callsign (6 bytes, padded)
        byte[] callBytes = (callsign + "      ").substring(0, 6)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        buf.put(callBytes);

        // Lat/lon as fixed-point integers
        buf.putInt((int) (lat * 1e7));
        buf.putInt((int) (lon * 1e7));

        // Altitude (meters, clamped to int16 range)
        buf.putShort((short) Math.max(-32768, Math.min(32767, (int) alt)));

        // Speed (m/s * 100)
        buf.putShort((short) Math.max(0, Math.min(65535, (int) (speed * 100))));

        // Course (degrees * 100)
        buf.putShort((short) Math.max(0, Math.min(36000, (int) (course * 100))));

        // Flags
        byte flags = 0x01; // hasGPS
        if (battery >= 0) flags |= 0x04; // hasBattery
        buf.put(flags);

        // Battery
        buf.put((byte) Math.max(0, Math.min(100, battery)));

        return new BtechRelayPacket(TYPE_GPS, buf.array());
    }

    /**
     * Decode a compact GPS position from a GPS packet payload.
     */
    public static GpsData decodeGpsPayload(byte[] payload) {
        if (payload == null || payload.length < 22) return null;

        ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.order(ByteOrder.BIG_ENDIAN);

        GpsData gps = new GpsData();

        byte[] callBytes = new byte[6];
        buf.get(callBytes);
        gps.callsign = new String(callBytes,
                java.nio.charset.StandardCharsets.US_ASCII).trim();

        gps.latitude = buf.getInt() / 1e7;
        gps.longitude = buf.getInt() / 1e7;
        gps.altitude = buf.getShort();
        gps.speed = (buf.getShort() & 0xFFFF) / 100.0;
        gps.course = (buf.getShort() & 0xFFFF) / 100.0;
        gps.flags = buf.get();
        gps.battery = buf.get() & 0xFF;

        return gps;
    }

    /**
     * Create a chat message packet (auto-generates messageId).
     */
    public static BtechRelayPacket createChatPacket(String sender,
                                                    String chatroom,
                                                    String message) {
        return createChatPacket(sender, chatroom,
                (int)(System.currentTimeMillis() & 0x7FFFFFFF), message);
    }

    /**
     * Create a chat message packet.
     */
    public static BtechRelayPacket createChatPacket(String sender,
                                                    String chatroom,
                                                    int messageId,
                                                    String message) {
        byte[] senderBytes = (sender + "      ").substring(0, 6)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] roomBytes = (chatroom + "      ").substring(0, 6)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] msgBytes = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        ByteBuffer buf = ByteBuffer.allocate(16 + msgBytes.length);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put(senderBytes);
        buf.put(roomBytes);
        buf.putInt(messageId);
        buf.put(msgBytes);

        return new BtechRelayPacket(TYPE_CHAT, buf.array());
    }

    /**
     * Create a ping/discovery packet.
     */
    public static BtechRelayPacket createPingPacket(String callsign) {
        byte[] callBytes = (callsign + "      ").substring(0, 6)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return new BtechRelayPacket(TYPE_PING, callBytes);
    }

    // --- Getters ---

    public byte getType() { return type; }
    public byte[] getPayload() { return payload; }

    /**
     * Compact GPS data structure.
     */
    public static class GpsData {
        public String callsign;
        public double latitude;
        public double longitude;
        public double altitude;
        public double speed;
        public double course;
        public byte flags;
        public int battery;
    }
}
