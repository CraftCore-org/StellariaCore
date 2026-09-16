package org.craftcore.stellaria.managers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnclaimableChunkRegistryTest {

    @Test
    void markMakesOnlyThatChunkUnavailableUntilRemoved() {
        UnclaimableChunkRegistry registry = new UnclaimableChunkRegistry();
        LandManager.ChunkKey spawnChunk = new LandManager.ChunkKey("world", 0, 0);
        LandManager.ChunkKey adjacentChunk = new LandManager.ChunkKey("world", 1, 0);

        registry.mark(spawnChunk);

        assertTrue(registry.isMarked(spawnChunk));
        assertFalse(registry.isMarked(adjacentChunk));

        registry.unmark(spawnChunk);

        assertFalse(registry.isMarked(spawnChunk));
    }

    @Test
    void markAndUnmarkReportWhetherTheyChangedState() {
        UnclaimableChunkRegistry registry = new UnclaimableChunkRegistry();
        LandManager.ChunkKey chunk = new LandManager.ChunkKey("world", 0, 0);

        assertTrue(registry.mark(chunk));
        assertFalse(registry.mark(chunk));
        assertTrue(registry.unmark(chunk));
        assertFalse(registry.unmark(chunk));
    }
}
