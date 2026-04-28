package com.btechrelay.plugin.cot;

import android.util.Log;

import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Builds CoT (Cursor-on-Target) events for ATAK ingestion.
 *
 * CoT is XML-based situational awareness protocol. Key structure:
 * <pre>
 * &lt;event uid="..." type="..." how="..." time="..." start="..." stale="..."&gt;
 *   &lt;point lat="..." lon="..." hae="..." ce="..." le="..."/&gt;
 *   &lt;detail&gt;
 *     &lt;contact callsign="..."/&gt;
 *     &lt;__group name="..." role="..."/&gt;
 *     &lt;status battery="..."/&gt;
 *     &lt;track speed="..." course="..."/&gt;
 *   &lt;/detail&gt;
 * &lt;/event&gt;
 * </pre>
 */
public class CotBuilder {

    private static final String TAG = "BtechRelay.CotBuilder";

    /** Stale interval for radio contacts: 5 minutes */
    private static final long STALE_MILLIS = 5 * 60 * 1000L;

    /** ISO 8601 date format for CoT timestamps */
    private static final SimpleDateFormat COT_DATE_FORMAT;
    static {
        COT_DATE_FORMAT = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        COT_DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /**
     * Build a CoT event for a radio contact position.
     *
     * @param callsign  Callsign of the radio contact
     * @param lat       Latitude in decimal degrees
     * @param lon       Longitude in decimal degrees
     * @param alt       Altitude in meters (HAE), or 9999999 if unknown
     * @param speed     Speed in m/s, or -1 if unknown
     * @param course    Course in degrees, or -1 if unknown
     * @return CotEvent ready for dispatch to ATAK
     */
    public static CotEvent buildPositionCot(String callsign,
                                            double lat, double lon,
                                            double alt, double speed,
                                            double course) {
        return buildPositionCot(callsign, lat, lon, alt, speed, course, "Cyan");
    }

    /**
     * Build a CoT event for a radio contact position with configurable team color.
     */
    public static CotEvent buildPositionCot(String callsign,
                                            double lat, double lon,
                                            double alt, double speed,
                                            double course, String teamColor) {
        CotEvent event = new CotEvent();

        String normalizedCall = callsign.trim().toUpperCase();
        String uid = "ANDROID-" + normalizedCall;
        event.setUID(uid);
        event.setType("a-f-G-U-C"); // friendly ground unit combat
        event.setHow("m-g");          // machine GPS

        long now = System.currentTimeMillis();
        String timeStr = formatCotTime(now);
        String staleStr = formatCotTime(now + STALE_MILLIS);

        event.setTime(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStart(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStale(new com.atakmap.coremap.maps.time.CoordinatedTime(
                now + STALE_MILLIS));

        // HAE: Height Above Ellipsoid
        double hae = (alt >= 0 && alt < 99999) ? alt : CotPoint.UNKNOWN;
        event.setPoint(new CotPoint(lat, lon, hae,
                CotPoint.UNKNOWN, CotPoint.UNKNOWN));

        CotDetail detail = new CotDetail("detail");

        // Contact element
        CotDetail contact = new CotDetail("contact");
        contact.setAttribute("callsign", normalizedCall);
        detail.addChild(contact);

        // Group element — use configured team color
        CotDetail group = new CotDetail("__group");
        group.setAttribute("name", teamColor != null ? teamColor : "Cyan");
        group.setAttribute("role", "Team Member");
        detail.addChild(group);

        // Track element (speed/course)
        if (speed >= 0 || course >= 0) {
            CotDetail track = new CotDetail("track");
            if (speed >= 0) {
                track.setAttribute("speed",
                        String.format(Locale.US, "%.1f", speed));
            }
            if (course >= 0) {
                track.setAttribute("course",
                        String.format(Locale.US, "%.1f", course));
            }
            detail.addChild(track);
        }

        // Remark with source info
        CotDetail remarks = new CotDetail("remarks");
        remarks.setAttribute("source", "BTECH Relay Radio");
        detail.addChild(remarks);

        event.setDetail(detail);

        return event;
    }

    /**
     * Build a GeoChat CoT event.
     *
     * GeoChat messages use type "b-t-f" (bits-text-free).
     *
     * @param senderUid  UID of the sender
     * @param senderCall Callsign of the sender
     * @param message    Chat message text
     * @param chatRoom   Chat room identifier (use "All Chat Rooms" for broadcast)
     * @return CotEvent for the chat message
     */
    /** Pass wire {@code messageId != 0} from BtechRelay chat packet so GeoChat IDs stay unique vs ATAK merge. */
    public static CotEvent buildChatCot(String senderUid, String senderCall,
                                        String message, String chatRoom,
                                        long uniqueSuffix) {
        CotEvent event = new CotEvent();

        String uid = "GeoChat." + senderUid + "." + chatRoom + "." + uniqueSuffix;
        event.setUID(uid);
        event.setType("b-t-f");
        event.setHow("h-g-i-g-o"); // human-generated

        long now = System.currentTimeMillis();
        event.setTime(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStart(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStale(new com.atakmap.coremap.maps.time.CoordinatedTime(
                now + STALE_MILLIS));

        // Point is required but not meaningful for chat — use 0,0
        event.setPoint(new CotPoint(0, 0, CotPoint.UNKNOWN,
                CotPoint.UNKNOWN, CotPoint.UNKNOWN));

        CotDetail detail = new CotDetail("detail");

        // __chat element
        CotDetail chat = new CotDetail("__chat");
        chat.setAttribute("parent", "RootContactGroup");
        chat.setAttribute("groupOwner", "false");
        chat.setAttribute("messageId", uid);
        chat.setAttribute("chatroom", chatRoom);
        chat.setAttribute("id", chatRoom);
        chat.setAttribute("senderCallsign", senderCall);

        CotDetail chatgrp = new CotDetail("chatgrp");
        chatgrp.setAttribute("uid0", senderUid);
        chatgrp.setAttribute("uid1", chatRoom);
        chatgrp.setAttribute("id", chatRoom);
        chat.addChild(chatgrp);

        detail.addChild(chat);

        // link element
        CotDetail link = new CotDetail("link");
        link.setAttribute("uid", senderUid);
        link.setAttribute("type", "a-f-G-U-C");
        link.setAttribute("relation", "p-p");
        detail.addChild(link);

        // remarks element — contains the actual message
        CotDetail remarks = new CotDetail("remarks");
        remarks.setAttribute("source", "BAO.F.ATAK." + senderUid);
        remarks.setAttribute("to", chatRoom);
        remarks.setAttribute("time", formatCotTime(now));
        remarks.setInnerText(message);
        detail.addChild(remarks);

        event.setDetail(detail);

        return event;
    }

    /**
     * Format a timestamp as CoT-compatible ISO 8601 UTC string.
     */
    public static String formatCotTime(long millis) {
        synchronized (COT_DATE_FORMAT) {
            return COT_DATE_FORMAT.format(new Date(millis));
        }
    }

    /**
     * Compress a CoT XML string with GZIP.
     */
    public static byte[] compressCot(String cotXml) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            GZIPOutputStream gzip = new GZIPOutputStream(baos);
            gzip.write(cotXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            gzip.close();
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Failed to compress CoT", e);
            return null;
        }
    }

    /**
     * Decompress a GZIP-compressed CoT XML.
     */
    public static String decompressCot(byte[] compressed) {
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
            GZIPInputStream gzip = new GZIPInputStream(bais);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = gzip.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            gzip.close();
            return new String(baos.toByteArray(),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Failed to decompress CoT", e);
            return null;
        }
    }
}
