package org.craftcore.stellaria.utils;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementsYamlTest {

    private static final Set<String> SAMPLE_IDS = Set.of("welcome", "first_steps", "playtime_1h", "playtime_10h", "kikori_100");

    @Test
    void bundledDefinitionsLoadWithoutWarnings() throws Exception {
        YamlConfiguration yaml;
        try (var in = new InputStreamReader(getClass().getResourceAsStream("/advancements.yml"), StandardCharsets.UTF_8)) {
            yaml = YamlConfiguration.loadConfiguration(in);
        }
        List<String> warnings = new ArrayList<>();
        AdvancementDefinitions.Parsed parsed = AdvancementDefinitions.parse(yaml,
                name -> org.bukkit.Material.matchMaterial(name) != null, warnings::add);
        assertEquals(List.of(), warnings);
        assertEquals(10, parsed.tabs().size());
        assertEquals(116, parsed.definitions().size());
        assertEquals(116, yaml.getConfigurationSection("advancements").getKeys(false).size());
        for (String id : SAMPLE_IDS) {
            assertTrue(parsed.find(id).isPresent(), id);
        }
        assertEquals(15, parsed.definitions().stream().filter(d -> d.tab().equals("stellaria")).count());
        assertEquals(8, parsed.definitions().stream().filter(AdvancementDefinitions.Definition::hidden).count());
    }
}
