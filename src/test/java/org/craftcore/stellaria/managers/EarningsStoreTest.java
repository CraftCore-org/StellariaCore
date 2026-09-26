package org.craftcore.stellaria.managers;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EarningsStoreTest {

    private static final UUID RICH = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SHY = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID NONE = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @TempDir
    Path dataFolder;

    @BeforeEach
    void connect() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("EarningsStoreTest"));
        DatabaseManager.connect(plugin, "test.db");
        DatabaseManager.createTableIfNotExists("players",
            "uuid TEXT PRIMARY KEY", "name TEXT", "hide_balance INTEGER NOT NULL DEFAULT 0");
        AdvancementStore.createTables();
        player(RICH, "Rich", false, 5000);
        player(MID, "Mid", false, 300);
        player(SHY, "Shy", true, 9000);
        player(NONE, "None", false, 0);
    }

    private static void player(UUID uuid, String name, boolean hidden, long earned) {
        DatabaseManager.execute("INSERT INTO players (uuid, name, hide_balance) VALUES (?, ?, ?)",
            uuid.toString(), name, hidden ? 1 : 0);
        if (earned > 0) {
            AdvancementStore.addCounter(uuid, EarningsStore.KEY, earned);
        }
    }

    @AfterEach
    void disconnect() {
        DatabaseManager.disconnect();
    }

    @Test
    void rankingListsPublicEarnersInDescendingOrder() {
        assertEquals(List.of(new EarningsStore.Entry("Rich", 5000L), new EarningsStore.Entry("Mid", 300L)),
            EarningsStore.top(10, 0));
        assertEquals(2, EarningsStore.publicCount());
    }

    @Test
    void countsPublicPlayersAboveValue() {
        assertEquals(0, EarningsStore.countPublicAbove(5000));
        assertEquals(1, EarningsStore.countPublicAbove(300));
        assertEquals(2, EarningsStore.countPublicAbove(0));
    }

    @Test
    void totalOfPlayerWithoutEarningsIsZero() {
        assertEquals(9000L, EarningsStore.total(SHY));
        assertEquals(0L, EarningsStore.total(NONE));
    }
}
