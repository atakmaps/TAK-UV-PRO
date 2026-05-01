package com.uvpro.plugin.util;

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
}
