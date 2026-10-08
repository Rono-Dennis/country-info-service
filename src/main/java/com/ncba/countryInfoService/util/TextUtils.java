package com.ncba.countryInfoService.util;

import java.util.Locale;
/**convert first letter to upperCase*/
public class TextUtils {

    private TextUtils() {
    }

    public static String toSentenceCase(String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) {
            return s;
        }
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}
