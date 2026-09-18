package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscordRoleColorUtilTest {

    @Test
    void prefixesDisplayNameWithTheDiscordRoleColor() {
        assertEquals("&#1a2b3cStella", DiscordRoleColorUtil.applyToDisplayName("Stella", new Color(0x1A2B3C)));
    }

    @Test
    void preservesDisplayNameWhenTheMemberHasNoColoredRole() {
        assertEquals("Stella", DiscordRoleColorUtil.applyToDisplayName("Stella", null));
    }
}
