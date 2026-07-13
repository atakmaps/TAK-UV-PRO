package com.uvpro.plugin.cot;

import android.util.Log;

import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.maps.coords.GeoPoint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GRG Builder Settings JSON share over RF (markers + settings recipe).
 */
public final class GrgSettingsShare {

    private static final String TAG = "UVPro.GrgShare";

    public static final String GRG_SETTINGS_COT_TYPE = "t-x-grg-settings";
    public static final String GRG_SETTINGS_DETAIL = "grgbuilder";
    public static final String GRG_SETTINGS_CHILD = "settings";
    public static final String GRG_SETTINGS_REMARKS_SOURCE = "UV-PRO GRG settings";

    private static final String GRG_BUILDER_DIR = "tools/grgbuilder";
    private static final long MARKER_SEND_GAP_MS = 400L;

    private static final Set<String> receivedGrgUids = ConcurrentHashMap.newKeySet();

    private GrgSettingsShare() {
    }

    public static boolean isGrgSettingsCot(CotEvent event) {
        return event != null && GRG_SETTINGS_COT_TYPE.equals(event.getType());
    }

    public static CotEvent buildGrgSettingsCot(String jsonUtf8, String filename, MapView mapView) {
        if (jsonUtf8 == null || jsonUtf8.isEmpty()) {
            return null;
        }
        CotEvent event = new CotEvent();
        event.setUID("GRG-SETTINGS-" + UUID.randomUUID());
        event.setType(GRG_SETTINGS_COT_TYPE);
        event.setHow("h-g-i-g-o");
        long now = System.currentTimeMillis();
        event.setTime(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStart(new com.atakmap.coremap.maps.time.CoordinatedTime(now));
        event.setStale(new com.atakmap.coremap.maps.time.CoordinatedTime(now + 24 * 60 * 60 * 1000L));
        event.setPoint(new com.atakmap.coremap.cot.event.CotPoint(0, 0,
                com.atakmap.coremap.cot.event.CotPoint.UNKNOWN,
                com.atakmap.coremap.cot.event.CotPoint.UNKNOWN,
                com.atakmap.coremap.cot.event.CotPoint.UNKNOWN));

        CotDetail detail = new CotDetail("detail");
        CotDetail contact = new CotDetail("contact");
        if (mapView != null) {
            String callsign = mapView.getDeviceCallsign();
            if (callsign != null && !callsign.trim().isEmpty()) {
                contact.setAttribute("callsign", callsign.trim().toUpperCase(Locale.US));
            }
        }
        detail.addChild(contact);

        CotDetail grg = new CotDetail(GRG_SETTINGS_DETAIL);
        if (filename != null && !filename.trim().isEmpty()) {
            grg.setAttribute("filename", filename.trim());
        }
        CotDetail settings = new CotDetail(GRG_SETTINGS_CHILD);
        settings.setInnerText(jsonUtf8);
        grg.addChild(settings);
        detail.addChild(grg);

        CotDetail remarks = new CotDetail("remarks");
        remarks.setAttribute("source", GRG_SETTINGS_REMARKS_SOURCE);
        remarks.setInnerText("GRG settings");
        detail.addChild(remarks);
        event.setDetail(detail);
        return event;
    }

    public static String extractSettingsJson(CotEvent event) {
        if (event == null || event.getDetail() == null) {
            return null;
        }
        CotDetail grg = event.getDetail().getFirstChildByName(0, GRG_SETTINGS_DETAIL);
        if (grg == null) {
            return null;
        }
        CotDetail settings = grg.getFirstChildByName(0, GRG_SETTINGS_CHILD);
        if (settings == null) {
            return null;
        }
        String text = settings.getInnerText();
        return text != null && !text.isEmpty() ? text : null;
    }

    public static String extractFilename(CotEvent event) {
        if (event == null || event.getDetail() == null) {
            return null;
        }
        CotDetail grg = event.getDetail().getFirstChildByName(0, GRG_SETTINGS_DETAIL);
        if (grg == null) {
            return null;
        }
        String name = grg.getAttribute("filename");
        return name != null && !name.trim().isEmpty() ? name.trim() : null;
    }

    public static File grgBuilderDir() {
        return FileSystemUtils.getItem(GRG_BUILDER_DIR);
    }

    public static File[] listSettingsJsonFiles() {
        File dir = grgBuilderDir();
        if (dir == null || !dir.isDirectory()) {
            return new File[0];
        }
        File[] files = dir.listFiles(pathname ->
                pathname != null && pathname.isFile()
                        && pathname.getName().toLowerCase(Locale.US).endsWith(".json"));
        return files != null ? files : new File[0];
    }

    public static String readUtf8File(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] buf = new byte[(int) Math.min(file.length(), 256 * 1024)];
            int n = in.read(buf);
            if (n <= 0) {
                return "";
            }
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    public static File writeReceivedSettings(String jsonUtf8, String preferredName) throws Exception {
        File dir = grgBuilderDir();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create " + dir.getAbsolutePath());
        }
        String safeName = sanitizeFilename(preferredName);
        if (safeName == null || safeName.isEmpty()) {
            safeName = "grg_settings_" + System.currentTimeMillis() + ".json";
        }
        File out = new File(dir, safeName);
        int suffix = 1;
        while (out.exists()) {
            int dot = safeName.lastIndexOf('.');
            String base = dot > 0 ? safeName.substring(0, dot) : safeName;
            String ext = dot > 0 ? safeName.substring(dot) : ".json";
            out = new File(dir, base + "_" + suffix + ext);
            suffix++;
        }
        FileOutputStream fos = new FileOutputStream(out, false);
        try {
            fos.write(jsonUtf8.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } finally {
            fos.close();
        }
        return out;
    }

    private static String sanitizeFilename(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        trimmed = trimmed.replace('\\', '_').replace('/', '_');
        if (!trimmed.toLowerCase(Locale.US).endsWith(".json")) {
            trimmed = trimmed + ".json";
        }
        return trimmed;
    }

    public static boolean markReceivedOnce(String cotUid) {
        if (cotUid == null || cotUid.trim().isEmpty()) {
            return true;
        }
        return receivedGrgUids.add(cotUid.trim());
    }

    public static double[] parseCornerBounds(String jsonUtf8) {
        if (jsonUtf8 == null || jsonUtf8.isEmpty()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(jsonUtf8);
            JSONObject grg = root.optJSONObject("GRG");
            if (grg == null) {
                return null;
            }
            JSONArray corners = grg.optJSONArray("Corners");
            if (corners == null || corners.length() < 3) {
                return null;
            }
            double minLat = Double.MAX_VALUE;
            double maxLat = -Double.MAX_VALUE;
            double minLon = Double.MAX_VALUE;
            double maxLon = -Double.MAX_VALUE;
            for (int i = 0; i < corners.length(); i++) {
                String pair = corners.optString(i, "");
                if (pair.isEmpty()) {
                    continue;
                }
                String[] parts = pair.split(",");
                if (parts.length < 2) {
                    continue;
                }
                double lat = Double.parseDouble(parts[0].trim());
                double lon = Double.parseDouble(parts[1].trim());
                minLat = Math.min(minLat, lat);
                maxLat = Math.max(maxLat, lat);
                minLon = Math.min(minLon, lon);
                maxLon = Math.max(maxLon, lon);
            }
            if (minLat == Double.MAX_VALUE) {
                return null;
            }
            return new double[]{minLat, maxLat, minLon, maxLon};
        } catch (Exception e) {
            Log.w(TAG, "parseCornerBounds failed: " + e.getMessage());
            return null;
        }
    }

    public static List<CotEvent> collectMarkerCotsInsideBounds(MapView mapView, double[] bounds) {
        List<CotEvent> out = new ArrayList<>();
        if (mapView == null || mapView.getRootGroup() == null || bounds == null || bounds.length < 4) {
            return out;
        }
        double minLat = bounds[0];
        double maxLat = bounds[1];
        double minLon = bounds[2];
        double maxLon = bounds[3];
        Set<String> seen = new HashSet<>();
        collectMarkerCotsRecursive(mapView.getRootGroup(), minLat, maxLat, minLon, maxLon, seen, out);
        return out;
    }

    private static void collectMarkerCotsRecursive(MapGroup group,
                                                   double minLat, double maxLat,
                                                   double minLon, double maxLon,
                                                   Set<String> seen,
                                                   List<CotEvent> out) {
        if (group == null) {
            return;
        }
        for (MapItem item : group.getItems()) {
            if (!(item instanceof PointMapItem)) {
                continue;
            }
            String uid = item.getUID();
            if (uid == null || uid.isEmpty() || !seen.add(uid)) {
                continue;
            }
            GeoPoint gp = ((PointMapItem) item).getPoint();
            if (gp == null || !gp.isValid()) {
                continue;
            }
            double lat = gp.getLatitude();
            double lon = gp.getLongitude();
            if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) {
                continue;
            }
            try {
                CotEvent cot = CotEventFactory.createCotEvent(item);
                if (cot == null || !cot.isValid()) {
                    continue;
                }
                String type = cot.getType();
                if (type == null || !isRelayableMarkerType(type)) {
                    continue;
                }
                if (type.startsWith("a-f-") && type.contains("-U-C")) {
                    continue;
                }
                out.add(cot);
            } catch (Exception ignored) {
            }
        }
        for (MapGroup child : group.getChildGroups()) {
            collectMarkerCotsRecursive(child, minLat, maxLat, minLon, maxLon, seen, out);
        }
    }

    private static boolean isRelayableMarkerType(String type) {
        return type.startsWith("b-m-p")
                || type.startsWith("a-f-")
                || type.startsWith("a-h-")
                || type.startsWith("a-n-")
                || type.startsWith("a-u-")
                || type.startsWith("a-p-")
                || type.startsWith("u-d-p")
                || type.startsWith("u-d-c");
    }

    public static long markerSendGapMs() {
        return MARKER_SEND_GAP_MS;
    }
}
