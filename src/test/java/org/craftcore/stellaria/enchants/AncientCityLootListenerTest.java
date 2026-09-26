package org.craftcore.stellaria.enchants;

import org.bukkit.NamespacedKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AncientCityLootListenerTest {

    private static final NamespacedKey ANCIENT_CITY = NamespacedKey.minecraft("chests/ancient_city");

    @Test
    void addsTheBookWhenTheRollIsBelowTheChance() {
        assertTrue(AncientCityLootListener.shouldAddBook(ANCIENT_CITY, 0.10, 0.05));
        assertFalse(AncientCityLootListener.shouldAddBook(ANCIENT_CITY, 0.10, 0.10));
        assertFalse(AncientCityLootListener.shouldAddBook(ANCIENT_CITY, 0.10, 0.50));
    }

    @Test
    void chanceZeroNeverAddsAndChanceOneAlwaysAdds() {
        assertFalse(AncientCityLootListener.shouldAddBook(ANCIENT_CITY, 0.0, 0.0));
        assertTrue(AncientCityLootListener.shouldAddBook(ANCIENT_CITY, 1.0, 0.999));
    }

    @Test
    void otherLootTablesIncludingTheIceBoxNeverAddTheBook() {
        assertFalse(AncientCityLootListener.shouldAddBook(NamespacedKey.minecraft("chests/ancient_city_ice_box"), 1.0, 0.0));
        assertFalse(AncientCityLootListener.shouldAddBook(NamespacedKey.minecraft("chests/stronghold_library"), 1.0, 0.0));
        assertFalse(AncientCityLootListener.shouldAddBook(NamespacedKey.minecraft("chests/end_city_treasure"), 1.0, 0.0));
    }
}
