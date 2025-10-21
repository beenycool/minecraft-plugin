package com.beenycool.namespoofer.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatUtilsTest {
    @Test
    void convertsAlternateColorCodesToSectionSymbol() {
        assertEquals("§aHello §bWorld", ChatUtils.color("&aHello §BWorld"));
    }

    @Test
    void leavesUnknownCodesIntact() {
        assertEquals("&zHello", ChatUtils.color("&zHello"));
    }
}
