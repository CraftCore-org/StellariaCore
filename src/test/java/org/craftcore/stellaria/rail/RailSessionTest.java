package org.craftcore.stellaria.rail;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailSessionTest {

    private RailSession newSession() {
        return new RailSession(UUID.randomUUID(), "origin", BlockFace.NORTH, 2.0, 0.4);
    }

    @Test
    void hasEnteredBlockIsTrueUntilRemembered() {
        RailSession session = newSession();
        // 初期状態（一度もrememberBlockしていない）では常にtrue
        assertTrue(session.hasEnteredBlock(10, 64, 20));

        session.rememberBlock(10, 64, 20);
        assertFalse(session.hasEnteredBlock(10, 64, 20));
        assertTrue(session.hasEnteredBlock(11, 64, 20));
        assertTrue(session.hasEnteredBlock(10, 65, 20));
        assertTrue(session.hasEnteredBlock(10, 64, 21));
    }

    @Test
    void hasEnteredChunkIsTrueUntilRemembered() {
        RailSession session = newSession();
        assertTrue(session.hasEnteredChunk(0, 0));

        session.rememberChunk(3, -2);
        assertFalse(session.hasEnteredChunk(3, -2));
        assertTrue(session.hasEnteredChunk(4, -2));
        assertTrue(session.hasEnteredChunk(3, -1));
    }
}
