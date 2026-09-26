package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatDefinitionsTest {

    @Test
    void definesElevenKeysInSpecOrder() {
        List<String> keys = StatDefinitions.all().stream().map(StatDefinitions.Definition::key).toList();
        assertEquals(List.of("mobkills", "pvpkills", "deaths", "fishing", "mined", "placed",
                "distance", "jumps", "trades", "bred", "cake"), keys);
    }

    @Test
    void distanceSumsMovementStatistics() {
        StatDefinitions.Definition distance = StatDefinitions.find("distance").orElseThrow();
        assertEquals(StatDefinitions.Kind.CUSTOM_SUM, distance.kind());
        assertTrue(distance.customIds().contains("walk_one_cm"));
        assertTrue(distance.customIds().contains("aviate_one_cm"));
        assertTrue(distance.customIds().contains("happy_ghast_one_cm"));
    }

    @Test
    void filterConfiguredDropsUnknownAndDuplicateKeysKeepingOrder() {
        List<String> warnings = new ArrayList<>();
        List<String> result = StatDefinitions.filterConfigured(
                List.of(" Deaths", "mobkills", "unknown", "deaths", "cake"), warnings::add);
        assertEquals(List.of("deaths", "mobkills", "cake"), result);
        assertEquals(2, warnings.size());
    }
}
