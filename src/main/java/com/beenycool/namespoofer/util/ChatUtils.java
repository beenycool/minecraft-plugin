package com.beenycool.namespoofer.util;

import net.minecraft.util.Formatting;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ChatUtils {
    private static final Pattern COLOR_PATTERN = Pattern.compile("(?i)([&§])([0-9A-FK-OR])");

    private ChatUtils() {
    }

    public static String color(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        Matcher matcher = COLOR_PATTERN.matcher(input);
        StringBuffer buffer = new StringBuffer(input.length());
        while (matcher.find()) {
            char code = Character.toLowerCase(matcher.group(2).charAt(0));
            Formatting formatting = Formatting.byCode(code);
            if (formatting == null) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group()));
                continue;
            }

            String replacement = "§" + formatting.getCode();
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
