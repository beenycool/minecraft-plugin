package com.beenycool.namespoofer.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameReplacerTest {
    @Test
    void replacesConfiguredEntriesCaseInsensitively() {
        NameReplacer replacer = new NameReplacer();
        replacer.update(Map.of("Alex", "PlayerOne"));

        String result = replacer.replace("alex joined the game");
        assertEquals("PlayerOne joined the game", result);
    }

    @Test
    void leavesTextUnchangedWhenDisabled() {
        NameReplacer replacer = new NameReplacer();
        replacer.update(Map.of("Alex", "PlayerOne"));
        replacer.setEnabled(false);

        String original = "Alex joined";
        assertEquals(original, replacer.replace(original));
    }

    @Test
    void skipUpdatesWhenNoReplacements() {
        NameReplacer replacer = new NameReplacer();
        replacer.update(Map.of());

        String input = "Nothing changes";
        assertEquals(input, replacer.replace(input));
        assertTrue(replacer.isEnabled());
    }
}
