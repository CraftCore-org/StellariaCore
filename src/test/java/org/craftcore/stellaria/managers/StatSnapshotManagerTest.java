package org.craftcore.stellaria.managers;

import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        DatabaseManager.createTableIfNotExists("players",
            "uuid TEXT PRIMARY KEY", "name TEXT", "hide_stats_ranking INTEGER NOT NULL DEFAULT 0");
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
    void isListedOnlyForPositiveStoredValues() {
        assertFalse(StatSnapshotManager.isListed(PLAYER, "deaths"));
        StatSnapshotManager.writeValues(PLAYER, Map.of("deaths", 1L, "jumps", 0L), true);
        assertTrue(StatSnapshotManager.isListed(PLAYER, "deaths"));
        assertFalse(StatSnapshotManager.isListed(PLAYER, "jumps"));
    }

    @Test
    void rankingExcludesZeroValues() {
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        DatabaseManager.execute("INSERT INTO players (uuid, name) VALUES (?, ?)", PLAYER.toString(), "Zero");
        DatabaseManager.execute("INSERT INTO players (uuid, name) VALUES (?, ?)", other.toString(), "Killer");
        StatSnapshotManager.writeValues(PLAYER, Map.of("pvpkills", 0L), true);
        StatSnapshotManager.writeValues(other, Map.of("pvpkills", 3L), true);
        StatSnapshotManager manager = new StatSnapshotManager(null);
        assertEquals(List.of(new StatSnapshotManager.Entry("Killer", 3L)), manager.getTop("pvpkills", 10, 0));
        assertEquals(1, manager.getPublicCount("pvpkills"));
    }

    @Test
    void writeAfterDisconnectFailsQuietly() {
        DatabaseManager.disconnect();
        assertFalse(StatSnapshotManager.writeValues(PLAYER, Map.of("deaths", 1L), true));
    }

    @Test
    void readingOneCustomKeyDoesNotScanBlockStatistics() {
        Player player = mock(Player.class);
        when(player.getStatistic(Statistic.DEATHS)).thenReturn(7);
        assertEquals(7L, new StatSnapshotManager(null).readLive(player, "deaths"));
        verify(player, never()).getStatistic(eq(Statistic.MINE_BLOCK), any(Material.class));
    }

    @Test
    void periodicSnapshotsAreSpreadOverOneSecond() {
        assertEquals(1L, StatSnapshotManager.spreadDelayTicks(0));
        assertEquals(20L, StatSnapshotManager.spreadDelayTicks(19));
        assertEquals(1L, StatSnapshotManager.spreadDelayTicks(20));
    }
}
