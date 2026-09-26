package org.craftcore.stellaria.managers;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdvancementStoreTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path dataFolder;

    @BeforeEach
    void connect() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("AdvancementStoreTest"));
        DatabaseManager.connect(plugin, "test.db");
        AdvancementStore.createTables();
    }

    @AfterEach
    void disconnect() {
        DatabaseManager.disconnect();
    }

    @Test
    void countersAccumulate() {
        assertTrue(AdvancementStore.addCounter(PLAYER, "kikori.logs", 3));
        assertTrue(AdvancementStore.addCounter(PLAYER, "kikori.logs", 4));
        assertEquals(Map.of("kikori.logs", 7L), AdvancementStore.load(PLAYER).counters());
    }

    @Test
    void distinctMembersAreCountedOnce() {
        assertTrue(AdvancementStore.addMember(PLAYER, "pay.recipients", "alice"));
        assertFalse(AdvancementStore.addMember(PLAYER, "pay.recipients", "alice"));
        assertTrue(AdvancementStore.addMember(PLAYER, "pay.recipients", "bob"));
        assertEquals(Map.of("pay.recipients", 2L), AdvancementStore.load(PLAYER).distinctCounts());
    }

    @Test
    void completionIsRecordedOnce() {
        assertTrue(AdvancementStore.recordCompletion(PLAYER, "kikori_100", 1000L));
        assertFalse(AdvancementStore.recordCompletion(PLAYER, "kikori_100", 2000L));
        assertEquals(Set.of("kikori_100"), AdvancementStore.load(PLAYER).completed());
        assertEquals(Map.of("kikori_100", 1000L), AdvancementStore.completedAt(PLAYER));
    }

    @Test
    void rewardIsClaimedOnceAndOnlyWhenCompleted() {
        assertFalse(AdvancementStore.claimReward(PLAYER, "kikori_100", 300));
        AdvancementStore.recordCompletion(PLAYER, "kikori_100", 1000L);
        assertTrue(AdvancementStore.claimReward(PLAYER, "kikori_100", 300));
        assertFalse(AdvancementStore.claimReward(PLAYER, "kikori_100", 300));
        assertEquals(300L, AdvancementStore.rewardTotal(PLAYER));
    }

    @Test
    void failedPaymentCanBeUnclaimedAndClaimedAgain() {
        AdvancementStore.recordCompletion(PLAYER, "kikori_100", 1000L);
        assertTrue(AdvancementStore.claimReward(PLAYER, "kikori_100", 300));
        AdvancementStore.unclaimReward(PLAYER, "kikori_100");
        assertEquals(0L, AdvancementStore.rewardTotal(PLAYER));
        assertTrue(AdvancementStore.claimReward(PLAYER, "kikori_100", 300));
    }

    @Test
    void revokedAdvancementCanBeCompletedAgainWithoutSecondReward() {
        AdvancementStore.recordCompletion(PLAYER, "kikori_100", 1000L);
        AdvancementStore.claimReward(PLAYER, "kikori_100", 300);
        assertTrue(AdvancementStore.revoke(PLAYER, "kikori_100"));
        assertFalse(AdvancementStore.revoke(PLAYER, "kikori_100"));
        assertEquals(Set.of(), AdvancementStore.load(PLAYER).completed());
        assertTrue(AdvancementStore.recordCompletion(PLAYER, "kikori_100", 3000L));
        assertFalse(AdvancementStore.claimReward(PLAYER, "kikori_100", 300));
        assertEquals(300L, AdvancementStore.rewardTotal(PLAYER));
    }
}
