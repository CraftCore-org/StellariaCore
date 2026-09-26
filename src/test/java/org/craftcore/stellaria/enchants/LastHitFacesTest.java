package org.craftcore.stellaria.enchants;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LastHitFacesTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID WORLD = UUID.randomUUID();

    @Test
    void returnsTheFaceTheClientStartedDiggingOnTheSameBlock() {
        LastHitFaces faces = new LastHitFaces();
        faces.record(PLAYER, WORLD, 10, 64, -3, BlockFace.NORTH);
        assertEquals(BlockFace.NORTH, faces.faceFor(PLAYER, WORLD, 10, 64, -3));
    }

    @Test
    void ignoresARecordForADifferentBlockOrWorld() {
        LastHitFaces faces = new LastHitFaces();
        faces.record(PLAYER, WORLD, 10, 64, -3, BlockFace.NORTH);
        assertNull(faces.faceFor(PLAYER, WORLD, 10, 65, -3));
        assertNull(faces.faceFor(PLAYER, UUID.randomUUID(), 10, 64, -3));
        assertNull(faces.faceFor(UUID.randomUUID(), WORLD, 10, 64, -3));
    }

    @Test
    void newerHitReplacesTheOlderOneAndForgetClearsIt() {
        LastHitFaces faces = new LastHitFaces();
        faces.record(PLAYER, WORLD, 10, 64, -3, BlockFace.NORTH);
        faces.record(PLAYER, WORLD, 10, 64, -3, BlockFace.UP);
        assertEquals(BlockFace.UP, faces.faceFor(PLAYER, WORLD, 10, 64, -3));
        faces.forget(PLAYER);
        assertNull(faces.faceFor(PLAYER, WORLD, 10, 64, -3));
    }
}
