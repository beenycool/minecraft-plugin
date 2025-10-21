package com.beenycool.namespoofer.util;

import java.util.Map;

public final class NameUtils {
    private static final NameReplacer REPLACER = new NameReplacer();

    private NameUtils() {
    }

    public static String apply(String text) {
        return REPLACER.replace(text);
    }

    public static void updateSpoofs(Map<String, String> replacements) {
        REPLACER.update(replacements);
    }

    public static void setEnabled(boolean enabled) {
        REPLACER.setEnabled(enabled);
    }

    public static boolean isEnabled() {
        return REPLACER.isEnabled();
    }
}
