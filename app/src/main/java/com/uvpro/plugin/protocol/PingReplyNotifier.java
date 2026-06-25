package com.uvpro.plugin.protocol;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.uvpro.plugin.cot.CotBuilder;
import com.uvpro.plugin.util.CallsignUtil;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Toasts for ping send, receive, and slotted position replies over mesh/RF.
 */
public final class PingReplyNotifier {

    private static final String TAG = "UVPro.PingReply";
    private static final long REPLY_WINDOW_BUFFER_MS = 8000L;

    private static volatile long pingSentAtMs;
    /** Non-null after a directed ping — only this station may trigger a reply toast. */
    private static volatile String directedPingTargetCallsign;
    private static volatile boolean lastPingIncludedRf;
    private static volatile boolean lastPingIncludedWifi;
    private static final Set<String> toastedReplyKeys = ConcurrentHashMap.newKeySet();

    private PingReplyNotifier() {
    }

    /** Call after a broadcast ping is transmitted (RF and/or WiFi leg). */
    public static void notePingSent(Context context, boolean rfLeg, boolean wifiLeg) {
        pingSentAtMs = System.currentTimeMillis();
        directedPingTargetCallsign = null;
        toastedReplyKeys.clear();
        lastPingIncludedRf = rfLeg;
        lastPingIncludedWifi = wifiLeg;
        Log.d(TAG, "Ping sent — awaiting replies for up to "
                + formatWaitSeconds(context) + "s"
                + " (rf=" + rfLeg + " wifi=" + wifiLeg + ")");
    }

    /** @deprecated use {@link #notePingSent(Context, boolean, boolean)} */
    public static void notePingSent(Context context) {
        notePingSent(context, true, false);
    }

    /** Call after a directed position-request ping is transmitted. */
    public static void noteDirectedPingSent(Context context, String targetCallsign,
                                            String transportLabel) {
        pingSentAtMs = System.currentTimeMillis();
        toastedReplyKeys.clear();
        String target = targetCallsign != null ? targetCallsign.trim() : "";
        directedPingTargetCallsign = target.isEmpty() ? null : target;
        String label = transportLabel != null ? transportLabel : "";
        lastPingIncludedRf = label.contains("MeshCore") || label.contains("UV-PRO");
        lastPingIncludedWifi = label.contains("WiFi");
        Log.d(TAG, "Directed ping to " + target + " via " + transportLabel
                + " (rf=" + lastPingIncludedRf + " wifi=" + lastPingIncludedWifi + ")");
        if (target.isEmpty()) {
            showToast(context, "Ping sent");
        } else {
            showToast(context, "Ping sent to " + target);
        }
    }

    /** Responder: incoming ping from a peer. */
    public static void notifyPingReceived(Context context, String peerCallsign) {
        if (peerCallsign == null || peerCallsign.trim().isEmpty()) {
            return;
        }
        Log.d(TAG, "Ping received from " + peerCallsign.trim());
        showToast(context, "Ping from " + peerCallsign.trim());
    }

    /** Responder: slotted GPS reply was transmitted. */
    public static void notifyPingReplySent(Context context) {
        Log.d(TAG, "Ping reply transmitted (slotted)");
        showToast(context, "Ping reply sent");
    }

    /**
     * Pinger: compact GPS beacon from a peer after we sent a ping.
     */
    public static void maybeNotifyPingReply(Context context, String peerCallsign) {
        maybeNotifyPeerPosition(context, peerCallsign, "GPS");
    }

    /**
     * Pinger: inbound WiFi/TAK slotted ping reply CoT ({@link CotBuilder#WIFI_PING_REPLY_REMARKS_SOURCE}).
     * Routine network SA updates must not be treated as ping replies.
     */
    public static void maybeNotifyPingReplyFromCot(Context context, CotEvent event) {
        if (event == null) {
            return;
        }
        if (!CotBuilder.isWifiPingReplyCot(event)) {
            return;
        }
        if (!lastPingIncludedWifi) {
            String peer = extractCallsign(event);
            Log.d(TAG, "Skip ping-reply toast (WiFi reply after RF-only ping)"
                    + (peer != null ? " peer=" + peer : ""));
            return;
        }
        String callsign = extractCallsign(event);
        if (callsign == null || callsign.isEmpty()) {
            return;
        }
        maybeNotifyPeerPosition(context, callsign, "WiFi ping reply");
    }

    private static void maybeNotifyPeerPosition(Context context, String peerCallsign,
            String source) {
        if (context == null || peerCallsign == null || peerCallsign.trim().isEmpty()) {
            return;
        }
        long sentAt = pingSentAtMs;
        if (sentAt <= 0L) {
            return;
        }
        long elapsed = System.currentTimeMillis() - sentAt;
        long maxWait = maxReplyWaitMs(context);
        if (elapsed > maxWait) {
            Log.d(TAG, "Skip ping-reply toast (" + source + "): outside window elapsed="
                    + elapsed + "ms max=" + maxWait + "ms peer=" + peerCallsign);
            return;
        }

        String directedTarget = directedPingTargetCallsign;
        if (directedTarget != null && !directedTarget.isEmpty()
                && !CallsignUtil.isSameRadioStation(peerCallsign, directedTarget)) {
            Log.d(TAG, "Skip ping-reply toast (" + source + "): peer=" + peerCallsign
                    + " is not directed target " + directedTarget
                    + " elapsed=" + elapsed + "ms");
            return;
        }

        String normalized = normalizeCallsignKey(peerCallsign);
        if (isLocalCallsign(normalized, peerCallsign)) {
            return;
        }
        if (!toastedReplyKeys.add(normalized)) {
            return;
        }

        String label = peerCallsign.trim();
        Log.i(TAG, "Ping reply toast (" + source + ") from " + label
                + " elapsed=" + elapsed + "ms");
        showToast(context, "Ping reply from " + label);
    }

    private static String extractCallsign(CotEvent event) {
        try {
            CotDetail detail = event.getDetail();
            if (detail == null) {
                return null;
            }
            CotDetail contact = detail.getFirstChildByName(0, "contact");
            if (contact != null) {
                String cs = contact.getAttribute("callsign");
                if (cs != null && !cs.trim().isEmpty()) {
                    return cs.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String normalizeCallsignKey(String callsign) {
        String trimmed = callsign.trim().toUpperCase(Locale.US);
        String radio = CallsignUtil.toRadioCallsign(trimmed);
        return radio != null && !radio.isEmpty() ? radio : trimmed;
    }

    private static long maxReplyWaitMs(Context context) {
        int slots = NetSlotConfig.getSlotCount(context);
        float slotSec = NetSlotConfig.getSlotTimeSec(context);
        // Replies use effective slots 1..N (see PingReplyScheduler).
        return (long) (slots * slotSec * 1000.0f) + REPLY_WINDOW_BUFFER_MS;
    }

    private static int formatWaitSeconds(Context context) {
        return Math.max(1, Math.round(maxReplyWaitMs(context) / 1000.0f));
    }

    private static boolean isLocalCallsign(String normalizedKey, String rawPeer) {
        try {
            MapView mv = MapView.getMapView();
            if (mv == null || mv.getSelfMarker() == null) {
                return false;
            }
            String local = mv.getSelfMarker().getMetaString("callsign", "");
            if (local == null || local.trim().isEmpty()) {
                return false;
            }
            String localKey = normalizeCallsignKey(local);
            if (localKey.equals(normalizedKey)) {
                return true;
            }
            return rawPeer != null
                    && local.trim().equalsIgnoreCase(rawPeer.trim());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void showToast(Context context, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Context toastCtx = context;
                MapView mv = MapView.getMapView();
                if (mv != null && mv.getContext() != null) {
                    toastCtx = mv.getContext();
                }
                if (toastCtx == null) {
                    return;
                }
                Toast.makeText(toastCtx, message, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.w(TAG, "Toast failed: " + message, e);
            }
        });
    }
}
