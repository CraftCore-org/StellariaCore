package org.craftcore.stellaria.enchants;

import org.craftcore.stellaria.enchants.SaplingPlanner.Pos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaplingPlannerTest {

    @Test
    void mapsLogsAndWoodToTheirSapling() {
        assertEquals("OAK_SAPLING", SaplingPlanner.saplingFor("OAK_LOG"));
        assertEquals("DARK_OAK_SAPLING", SaplingPlanner.saplingFor("DARK_OAK_LOG"));
        assertEquals("PALE_OAK_SAPLING", SaplingPlanner.saplingFor("PALE_OAK_WOOD"));
        assertEquals("CHERRY_SAPLING", SaplingPlanner.saplingFor("CHERRY_LOG"));
        assertEquals("MANGROVE_PROPAGULE", SaplingPlanner.saplingFor("MANGROVE_LOG"));
    }

    @Test
    void returnsNullForUnplantableLogs() {
        assertNull(SaplingPlanner.saplingFor("STRIPPED_OAK_LOG"));
        assertNull(SaplingPlanner.saplingFor("CRIMSON_STEM"));
        assertNull(SaplingPlanner.saplingFor("BAMBOO_BLOCK"));
    }

    @Test
    void singleTrunkPlantsOneSapling() {
        assertEquals(List.of(new Pos(5, 64, 5)), SaplingPlanner.plan(List.of(new Pos(5, 64, 5))));
    }

    @Test
    void twoByTwoTrunkPlantsFourSaplingsRegardlessOfOrder() {
        List<Pos> roots = List.of(new Pos(1, 64, 1), new Pos(0, 64, 0), new Pos(1, 64, 0), new Pos(0, 64, 1));
        Set<Pos> planned = new HashSet<>(SaplingPlanner.plan(roots));
        assertEquals(Set.of(new Pos(0, 64, 0), new Pos(1, 64, 0), new Pos(0, 64, 1), new Pos(1, 64, 1)), planned);
    }

    @Test
    void incompleteSquareFallsBackToOneSapling() {
        List<Pos> roots = List.of(new Pos(0, 64, 0), new Pos(1, 64, 0), new Pos(0, 64, 1));
        assertEquals(List.of(new Pos(0, 64, 0)), SaplingPlanner.plan(roots));
    }

    @Test
    void emptyRootsPlantNothing() {
        assertTrue(SaplingPlanner.plan(List.of()).isEmpty());
    }
}
