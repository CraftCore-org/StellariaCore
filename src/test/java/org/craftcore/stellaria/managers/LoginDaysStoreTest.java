package org.craftcore.stellaria.managers;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginDaysStoreTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path dataFolder;

    @BeforeEach
    void connect() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("LoginDaysStoreTest"));
        DatabaseManager.connect(plugin, "test.db");
        LoginDaysStore.createTable();
    }

    @AfterEach
    void disconnect() {
        DatabaseManager.disconnect();
    }

    @Test
    void recordsEachDayOnceAndReadsSince() {
        LocalDate d1 = LocalDate.of(2026, 9, 1);
        LocalDate d2 = LocalDate.of(2026, 9, 20);
        LocalDate d3 = LocalDate.of(2026, 9, 21);
        LoginDaysStore.record(PLAYER, d1);
        LoginDaysStore.record(PLAYER, d2);
        LoginDaysStore.record(PLAYER, d2);
        LoginDaysStore.record(PLAYER, d3);
        assertEquals(Set.of(d2, d3), LoginDaysStore.since(PLAYER, d2));
    }
}
