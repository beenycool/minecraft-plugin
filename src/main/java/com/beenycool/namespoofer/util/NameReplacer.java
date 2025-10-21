package com.beenycool.namespoofer.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NameReplacer {
    private static final Locale LOCALE = Locale.ROOT;

    private volatile Pattern compiledPattern;
    private volatile Map<String, String> normalizedReplacements = Collections.emptyMap();
    private volatile boolean enabled = true;

    public synchronized void update(Map<String, String> replacements) {
        if (replacements == null || replacements.isEmpty()) {
            compiledPattern = null;
            normalizedReplacements = Collections.emptyMap();
            return;
        }

        Map<String, String> normalized = new HashMap<>();
        StringBuilder patternBuilder = new StringBuilder();
        patternBuilder.append("(?i)(");

        boolean first = true;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }

            if (!first) {
                patternBuilder.append("|");
            }
            patternBuilder.append(Pattern.quote(key));
            normalized.put(key.toLowerCase(LOCALE), entry.getValue());
            first = false;
        }

        if (first) {
            compiledPattern = null;
            normalizedReplacements = Collections.emptyMap();
            return;
        }

        if (normalized.equals(normalizedReplacements)) {
            return;
        }

        patternBuilder.append(")");
        compiledPattern = Pattern.compile(patternBuilder.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        normalizedReplacements = normalized;
    }

    public String replace(String input) {
        if (!enabled || input == null || input.isEmpty()) {
            return input;
        }

        Pattern pattern = compiledPattern;
        if (pattern == null) {
            return input;
        }

        Matcher matcher = pattern.matcher(input);
        if (!matcher.find()) {
            return input;
        }

        StringBuffer result = new StringBuffer(input.length());
        do {
            String match = matcher.group();
            String replacement = normalizedReplacements.get(match.toLowerCase(LOCALE));
            if (replacement == null) {
                matcher.appendReplacement(result, Matcher.quoteReplacement(match));
            } else {
                matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
            }
        } while (matcher.find());
        matcher.appendTail(result);
        return result.toString();
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
