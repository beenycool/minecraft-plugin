package com.beenycool.namespoofer.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class NameSpooferConfig {
    private boolean failSoft = true;
    private Map<String, String> replacements = new LinkedHashMap<>();

    public boolean isFailSoft() {
        return failSoft;
    }

    public void setFailSoft(boolean failSoft) {
        this.failSoft = failSoft;
    }

    public Map<String, String> getReplacements() {
        return replacements;
    }

    public void setReplacements(Map<String, String> replacements) {
        this.replacements = sanitizeMap(replacements);
    }

    public void normalize() {
        this.replacements = sanitizeMap(this.replacements);
    }

    public static NameSpooferConfig createDefault() {
        NameSpooferConfig config = new NameSpooferConfig();
        config.replacements = new LinkedHashMap<>();
        return config;
    }

    private static Map<String, String> sanitizeMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }

        Map<String, String> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = Objects.toString(entry.getValue(), "");
            sanitized.put(key.trim(), value);
        }
        return sanitized;
    }
}
