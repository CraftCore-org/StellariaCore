package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordCommandCleanupUtilTest {

    @Test
    void identifiesOnlyTheExplicitlyRetiredDiscordCommands() {
        assertTrue(DiscordCommandCleanupUtil.isRetiredCommand("whois"));
        assertTrue(DiscordCommandCleanupUtil.isRetiredCommand("discordconfig"));
        assertFalse(DiscordCommandCleanupUtil.isRetiredCommand("players"));
        assertFalse(DiscordCommandCleanupUtil.isRetiredCommand("settings"));
    }
}
