package org.craftcore.stellaria.enchantkeys;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnchantKeysTest {

    @Test
    void allContainsElevenDistinctIds() {
        assertEquals(11, EnchantKeys.ALL.size());
        assertEquals(11, new HashSet<>(EnchantKeys.ALL).size());
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
