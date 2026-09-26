package org.craftcore.stellaria.enchantkeys;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnchantKeysTest {

    @Test
    void allContainsTenDistinctIds() {
        assertEquals(10, EnchantKeys.ALL.size());
        assertEquals(10, new HashSet<>(EnchantKeys.ALL).size());
    }

    @Test
    void idsAreValidResourceLocationPaths() {
        for (String id : EnchantKeys.ALL) {
            assertTrue(id.matches("[a-z0-9_]+"), id);
        }
    }

    @Test
    void namespacedPrefixesTheStellariaNamespace() {
        assertEquals("stellaria:smelting", EnchantKeys.namespaced(EnchantKeys.SMELTING));
        assertEquals("stellaria", EnchantKeys.NAMESPACE);
    }
}
