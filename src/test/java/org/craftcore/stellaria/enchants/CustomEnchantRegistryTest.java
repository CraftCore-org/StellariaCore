package org.craftcore.stellaria.enchants;

import org.junit.jupiter.api.Test;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CustomEnchantRegistryTest {

    @Test
    void everythingIsUnavailableWhenStellariaEnchantsIsMissing() {
        CustomEnchantRegistry registry = CustomEnchantRegistry.resolve(key -> null, Logger.getAnonymousLogger());

        assertFalse(registry.anyAvailable());
        for (CustomEnchant enchant : CustomEnchant.values()) {
            assertFalse(registry.isAvailable(enchant));
            assertEquals(0, registry.level(null, enchant));
        }
        assertEquals(CustomEnchant.values().length, registry.missingIds().size());
    }
}
