package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldBlacklistUtilTest {
    @Test
    void matchesNamesIgnoringCaseAndAllowsOtherWorlds() {
        assertTrue(WorldBlacklistUtil.isBlacklisted(List.of("world_nether"), "WORLD_NETHER"));
        assertFalse(WorldBlacklistUtil.isBlacklisted(List.of(), "world"));
        assertFalse(WorldBlacklistUtil.isBlacklisted(List.of("world_the_end"), "world"));
    }
}
