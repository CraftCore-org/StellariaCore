package org.craftcore.stellariaenchants;

import org.craftcore.stellaria.enchantkeys.EnchantKeys;
import org.junit.jupiter.api.Test;

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
        assertEquals(Map.of(
                EnchantKeys.SMELTING, 1, EnchantKeys.PURSUIT, 3, EnchantKeys.REPLANT, 1,
                EnchantKeys.HARVEST, 3, EnchantKeys.GLIDE_BOOST, 3, EnchantKeys.LAUNCH, 2,
                EnchantKeys.LIFESTEAL, 3, EnchantKeys.LAST_STAND, 1, EnchantKeys.DOUBLE_JUMP, 2,
                EnchantKeys.ANGLER, 3), levels);
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
}
