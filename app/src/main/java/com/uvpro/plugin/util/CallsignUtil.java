package com.uvpro.plugin.util;

import java.util.Locale;

public class CallsignUtil {

    public static String toRadioCallsign(String atakCallsign) {
        if (atakCallsign == null) return "UNKNOWN";

        String cs = atakCallsign.toUpperCase()
                .replaceAll("[^A-Z0-9]", "");

        if (cs.length() > 1) {
            String first = cs.substring(0,1);
            String rest = cs.substring(1)
                    .replaceAll("[AEIOU]", "");
            cs = first + rest;
        }

        if (cs.length() > 6) {
            cs = cs.substring(0, 6);
        }

        return cs;
    }

    /**
     * Collapse punctuation/underscore forms to one key ({@code DEV_TWO} → {@code DEVTWO}).
     * Strips a leading {@code ANDROID-} prefix when present.
     */
    public static String alphanumericCallsignKey(String value) {
        if (value == null) {
            return "";
        }
        String s = value.trim().toUpperCase(Locale.US);
        if (s.startsWith("ANDROID-")) {
            s = s.substring("ANDROID-".length());
        }
        return s.replaceAll("[^A-Z0-9]", "");
    }

    /** True when labels refer to the same operator across ATAK / synthetic / wire forms. */
    public static boolean isSameCallsignAlias(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String ka = alphanumericCallsignKey(a);
        String kb = alphanumericCallsignKey(b);
        if (!ka.isEmpty() && ka.equals(kb)) {
            return true;
        }
        return isSameRadioStation(a, b);
    }

    /** True when both names resolve to the same 6-character wire callsign. */
    public static boolean isSameRadioStation(String callsignA, String callsignB) {
        if (callsignA == null || callsignB == null) {
            return false;
        }
        String wireA = toRadioCallsign(callsignA.trim());
        String wireB = toRadioCallsign(callsignB.trim());
        return !wireA.isEmpty() && wireA.equalsIgnoreCase(wireB);
    }
}
