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

class AdvancementRankingTest {

    private static final UUID MANY = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID FEW = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SHY = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID REVOKED = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @TempDir
    Path dataFolder;

    @BeforeEach
    void connect() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("AdvancementRankingTest"));
        DatabaseManager.connect(plugin, "test.db");
        DatabaseManager.createTableIfNotExists("players",
            "uuid TEXT PRIMARY KEY", "name TEXT", "hide_stats_ranking INTEGER NOT NULL DEFAULT 0");
        AdvancementStore.createTables();
        player(MANY, "Many", false, "a", "b", "c");
        player(FEW, "Few", false, "a");
        player(SHY, "Shy", true, "a", "b", "c", "d");
        player(REVOKED, "Revoked", false, "a");
        AdvancementStore.revoke(REVOKED, "a");
    }

    private static void player(UUID uuid, String name, boolean hidden, String... ids) {
        DatabaseManager.execute("INSERT INTO players (uuid, name, hide_stats_ranking) VALUES (?, ?, ?)",
            uuid.toString(), name, hidden ? 1 : 0);
        for (String id : ids) {
            AdvancementStore.recordCompletion(uuid, id, 1000L);
        }
    }

    @AfterEach
    void disconnect() {
        DatabaseManager.disconnect();
    }

    @Test
    void listsPublicPlayersByCompletedCount() {
        assertEquals(List.of(new AdvancementStore.RankEntry("Many", 3L), new AdvancementStore.RankEntry("Few", 1L)),
            AdvancementStore.topCompleted(10, 0));
        assertEquals(2, AdvancementStore.completedPublicCount());
    }

    @Test
    void countsAboveAndOwnTotalIgnoringRevokedRows() {
        assertEquals(1, AdvancementStore.countCompletedPublicAbove(1));
        assertEquals(0, AdvancementStore.countCompletedPublicAbove(3));
        assertEquals(4L, AdvancementStore.completedCount(SHY));
        assertEquals(0L, AdvancementStore.completedCount(REVOKED));
    }
}
