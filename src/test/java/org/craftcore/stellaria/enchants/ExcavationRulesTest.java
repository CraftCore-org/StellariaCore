package org.craftcore.stellaria.enchants;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.craftcore.stellaria.enchants.ExcavationRules.Offset;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcavationRulesTest {

    private static void assertPlane(List<Offset> offsets, boolean xFixed, boolean yFixed, boolean zFixed) {
        assertEquals(8, offsets.size());
        assertEquals(8, new HashSet<>(offsets).size());
        assertFalse(offsets.contains(new Offset(0, 0, 0)));
        for (Offset offset : offsets) {
            if (xFixed) assertEquals(0, offset.dx(), offset.toString());
            if (yFixed) assertEquals(0, offset.dy(), offset.toString());
            if (zFixed) assertEquals(0, offset.dz(), offset.toString());
            assertTrue(Math.abs(offset.dx()) <= 1 && Math.abs(offset.dy()) <= 1 && Math.abs(offset.dz()) <= 1);
        }
    }

    @Test
    void floorAndCeilingSpreadHorizontally() {
        assertPlane(ExcavationRules.offsets(BlockFace.UP), false, true, false);
        assertPlane(ExcavationRules.offsets(BlockFace.DOWN), false, true, false);
    }

    @Test
    void northAndSouthWallsSpreadOnTheXyPlane() {
        assertPlane(ExcavationRules.offsets(BlockFace.NORTH), false, false, true);
        assertPlane(ExcavationRules.offsets(BlockFace.SOUTH), false, false, true);
    }

    @Test
    void eastAndWestWallsSpreadOnTheZyPlane() {
        assertPlane(ExcavationRules.offsets(BlockFace.EAST), true, false, false);
        assertPlane(ExcavationRules.offsets(BlockFace.WEST), true, false, false);
    }

    @Test
    void nonAxisFacesProduceNoOffsets() {
        assertTrue(ExcavationRules.offsets(BlockFace.SELF).isEmpty());
        assertTrue(ExcavationRules.offsets(BlockFace.NORTH_EAST).isEmpty());
    }

    @Test
    void breaksSofterOrEquallyHardPreferredBlocks() {
        assertTrue(ExcavationRules.canBreakAround(false, true, true, 1.5f, 1.5f, false));
        assertTrue(ExcavationRules.canBreakAround(false, true, true, 0.5f, 1.5f, false));
        assertTrue(ExcavationRules.canBreakAround(false, true, true, 0.0f, 0.0f, false));
    }

    @Test
    void skipsBlocksHarderThanTheCenter() {
        // ネザーラック（0.4）を掘ったとき、隣の石（1.5）や黒曜石（50）は壊さない
        assertFalse(ExcavationRules.canBreakAround(false, true, true, 1.5f, 0.4f, false));
        assertFalse(ExcavationRules.canBreakAround(false, true, true, 50f, 1.5f, false));
    }

    @Test
    void skipsUnbreakableAirLiquidContainersAndWrongTool() {
        assertFalse(ExcavationRules.canBreakAround(false, true, true, -1f, 1.5f, false)); // 岩盤
        assertFalse(ExcavationRules.canBreakAround(true, true, true, 0f, 1.5f, false));   // 空気・液体
        assertFalse(ExcavationRules.canBreakAround(false, true, true, 3.5f, 3.5f, true)); // かまど
        assertFalse(ExcavationRules.canBreakAround(false, true, false, 0.5f, 1.5f, false)); // ツルハシで土
    }

    @Test
    void durabilityAtTheThresholdStillExcavates() {
        assertTrue(ExcavationRules.hasEnoughDurability(10, 10));
        assertTrue(ExcavationRules.hasEnoughDurability(500, 10));
        assertFalse(ExcavationRules.hasEnoughDurability(9, 10));
        assertFalse(ExcavationRules.hasEnoughDurability(0, 10));
    }

    @Test
    void toolsWithoutDurabilityAlwaysExcavate() {
        assertTrue(ExcavationRules.hasEnoughDurability(null, 10));
    }

    @Test
    void skipsBlocksOutsideTheToolsMineableTag() {
        // ツルハシで石を掘ったとき、道具を選ばないガラス・松明・作物・木材は壊さない
        assertFalse(ExcavationRules.canBreakAround(false, false, true, 0.3f, 1.5f, false));
        assertFalse(ExcavationRules.canBreakAround(false, false, true, 0.0f, 0.0f, false));
    }

    @Test
    void skipsBlockEntitiesAndIrreplaceableBlocks() {
        assertFalse(ExcavationRules.canBreakAround(false, true, true, 5.0f, 50f, true)); // スポナー
        assertTrue(ExcavationRules.isIrreplaceable(Material.BUDDING_AMETHYST));
        assertTrue(ExcavationRules.isIrreplaceable(Material.REINFORCED_DEEPSLATE));
        assertFalse(ExcavationRules.isIrreplaceable(Material.AMETHYST_BLOCK));
        assertFalse(ExcavationRules.isIrreplaceable(Material.STONE));
    }

    @Test
    void recognisesPickaxesAndShovelsOnly() {
        assertEquals(ExcavationRules.Tool.PICKAXE, ExcavationRules.toolOf(Material.DIAMOND_PICKAXE));
        assertEquals(ExcavationRules.Tool.PICKAXE, ExcavationRules.toolOf(Material.WOODEN_PICKAXE));
        assertEquals(ExcavationRules.Tool.SHOVEL, ExcavationRules.toolOf(Material.NETHERITE_SHOVEL));
        assertNull(ExcavationRules.toolOf(Material.DIAMOND_AXE));
        assertNull(ExcavationRules.toolOf(Material.ENCHANTED_BOOK));
    }
}
