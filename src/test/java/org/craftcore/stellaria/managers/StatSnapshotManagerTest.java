package org.craftcore.stellaria.managers;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatSnapshotManagerTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path dataFolder;

    @BeforeEach
    void connect() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("StatSnapshotManagerTest"));
        DatabaseManager.connect(plugin, "test.db");
        DatabaseManager.createTableIfNotExists("player_stat_snapshots",
            "uuid TEXT NOT NULL", "stat_key TEXT NOT NULL", "value INTEGER NOT NULL",
            "updated_at INTEGER NOT NULL", "PRIMARY KEY (uuid, stat_key)");
    }

    @AfterEach
    void disconnect() {
        DatabaseManager.disconnect();
    }

    private static Long stored(String key) {
        return DatabaseManager.queryOne(
            "SELECT value FROM player_stat_snapshots WHERE uuid = ? AND stat_key = ?",
            rs -> rs.getLong("value"), PLAYER.toString(), key);
    }

    @Test
    void liveWriteOverwritesExistingValue() {
        assertTrue(StatSnapshotManager.writeValues(PLAYER, Map.of("deaths", 10L), true));
        assertTrue(StatSnapshotManager.writeValues(PLAYER, Map.of("deaths", 12L), true));
        assertEquals(12L, stored("deaths"));
    }

    @Test
    void backfillWriteNeverOverwritesNewerSnapshot() {
        assertTrue(StatSnapshotManager.writeValues(PLAYER, Map.of("deaths", 12L), true));
        assertTrue(StatSnapshotManager.writeValues(PLAYER, Map.of("deaths", 5L, "jumps", 3L), false));
        assertEquals(12L, stored("deaths"));
        assertEquals(3L, stored("jumps"));
    }

    @Test
    void failedRowRollsBackWholeWrite() {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put("deaths", 4L);
        values.put("jumps", null); // value NOT NULL 制約で失敗させる
        assertFalse(StatSnapshotManager.writeValues(PLAYER, values, false));
        assertNull(stored("deaths"));
    }

    @Test
    void hasSnapshotReflectsStoredRows() {
        assertFalse(StatSnapshotManager.hasSnapshot(PLAYER, "deaths"));
        StatSnapshotManager.writeValues(PLAYER, Map.of("deaths", 1L), true);
        assertTrue(StatSnapshotManager.hasSnapshot(PLAYER, "deaths"));
        assertFalse(StatSnapshotManager.hasSnapshot(PLAYER, "jumps"));
    }
}
