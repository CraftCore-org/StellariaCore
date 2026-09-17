package org.craftcore.stellaria.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LobbyCommandTest {

    @Test
    void usesLobbyAsFallbackForBlankConfiguredWorld() {
        assertEquals("lobby", LobbyCommand.configuredWorldName("  "));
    }

    @Test
    void trimsConfiguredWorldName() {
        assertEquals("spawn", LobbyCommand.configuredWorldName(" spawn "));
    }
}
