package org.craftcore.stellaria.utils;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomHeadUtilTest {

    @Test
    void resolvesConfiguredNonBlankTexture() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("heads.lobby-compass.texture", "base64-value");

        assertEquals("base64-value", CustomHeadUtil.resolveTexture(
                config.getConfigurationSection("heads"), "lobby-compass").orElseThrow());
    }

    @Test
    void rejectsMissingAndBlankTextures() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("heads.empty.texture", "  ");

        assertTrue(CustomHeadUtil.resolveTexture(config.getConfigurationSection("heads"), "missing").isEmpty());
        assertTrue(CustomHeadUtil.resolveTexture(config.getConfigurationSection("heads"), "empty").isEmpty());
    }
}
