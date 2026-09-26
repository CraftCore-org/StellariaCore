package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StatFileParserTest {

    private static final Set<String> BLOCK_ITEMS = Set.of("minecraft:stone", "minecraft:oak_planks");

    @Test
    void sumsVanillaStatisticsPerKey() {
        String json = """
                {"stats":{
                  "minecraft:custom":{"minecraft:mob_kills":12,"minecraft:deaths":3,
                    "minecraft:walk_one_cm":1000,"minecraft:sprint_one_cm":500,"minecraft:jump":7},
                  "minecraft:mined":{"minecraft:stone":40,"minecraft:dirt":2},
                  "minecraft:used":{"minecraft:stone":5,"minecraft:oak_planks":6,"minecraft:diamond_pickaxe":99}
                },"DataVersion":4440}
                """;
        Map<String, Long> values = StatFileParser.parse(json, BLOCK_ITEMS::contains);
        assertEquals(12L, values.get("mobkills"));
        assertEquals(3L, values.get("deaths"));
        assertEquals(1500L, values.get("distance"));
        assertEquals(7L, values.get("jumps"));
        assertEquals(42L, values.get("mined"));
        assertEquals(11L, values.get("placed"));
        assertEquals(0L, values.get("cake"));
        assertEquals(11, values.size());
    }

    @Test
    void missingSectionsBecomeZero() {
        Map<String, Long> values = StatFileParser.parse("{\"DataVersion\":4440}", BLOCK_ITEMS::contains);
        assertEquals(11, values.size());
        values.values().forEach(v -> assertEquals(0L, v));
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(IllegalArgumentException.class, () -> StatFileParser.parse("{not json", BLOCK_ITEMS::contains));
        assertThrows(IllegalArgumentException.class, () -> StatFileParser.parse("[]", BLOCK_ITEMS::contains));
    }
}
