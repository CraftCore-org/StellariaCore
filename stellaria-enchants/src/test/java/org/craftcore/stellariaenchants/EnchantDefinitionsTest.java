package org.craftcore.stellariaenchants;

import org.craftcore.stellaria.enchantkeys.EnchantKeys;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnchantDefinitionsTest {

    @Test
    void definesEveryKeyExactlyOnceInOrder() {
        assertEquals(EnchantKeys.ALL, EnchantDefinitions.ALL.stream().map(EnchantDefinition::id).toList());
    }

    @Test
    void maxLevelsMatchTheSpec() {
        Map<String, Integer> levels = EnchantDefinitions.ALL.stream()
                .collect(Collectors.toMap(EnchantDefinition::id, EnchantDefinition::maxLevel));
        assertEquals(Map.ofEntries(
                Map.entry(EnchantKeys.SMELTING, 1), Map.entry(EnchantKeys.PURSUIT, 3),
                Map.entry(EnchantKeys.REPLANT, 1), Map.entry(EnchantKeys.HARVEST, 3),
                Map.entry(EnchantKeys.GLIDE_BOOST, 3), Map.entry(EnchantKeys.LAUNCH, 2),
                Map.entry(EnchantKeys.LIFESTEAL, 3), Map.entry(EnchantKeys.LAST_STAND, 1),
                Map.entry(EnchantKeys.DOUBLE_JUMP, 2), Map.entry(EnchantKeys.ANGLER, 3),
                Map.entry(EnchantKeys.EXCAVATION, 1)), levels);
    }

    @Test
    void onlySmeltingIsExclusiveWithSilkTouch() {
        for (EnchantDefinition definition : EnchantDefinitions.ALL) {
            if (definition.id().equals(EnchantKeys.SMELTING)) {
                assertTrue(definition.exclusiveWithSilkTouch());
            } else {
                assertFalse(definition.exclusiveWithSilkTouch(), definition.id());
            }
        }
    }

    @Test
    void weightsAndCostsAreInVanillaRange() {
        for (EnchantDefinition definition : EnchantDefinitions.ALL) {
            assertTrue(definition.weight() >= 1 && definition.weight() <= 1024, definition.id());
            assertTrue(definition.minCostBase() >= 1, definition.id());
            assertTrue(definition.anvilCost() >= 0, definition.id());
        }
    }

    @Test
    void onlyExcavationIsTreasure() {
        for (EnchantDefinition definition : EnchantDefinitions.ALL) {
            assertEquals(definition.id().equals(EnchantKeys.EXCAVATION), definition.treasure(), definition.id());
        }
    }

    @Test
    void discoverableExcludesTreasureEnchantments() {
        List<String> ids = EnchantDefinitions.discoverable().stream().map(EnchantDefinition::id).toList();
        assertFalse(ids.contains(EnchantKeys.EXCAVATION));
        assertEquals(EnchantDefinitions.ALL.size() - 1, ids.size());
    }

    @Test
    void excavationTargetsTheCustomPickaxeAndShovelTag() {
        EnchantDefinition excavation = EnchantDefinitions.ALL.stream()
                .filter(definition -> definition.id().equals(EnchantKeys.EXCAVATION))
                .findFirst().orElseThrow();
        assertEquals(EnchantDefinitions.EXCAVATION_ITEMS, excavation.itemTag());
        assertEquals("stellaria:enchantable/excavation", EnchantDefinitions.EXCAVATION_ITEMS.key().asString());
    }
}
