package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiscordTextUtilTest {

    @Test
    void convertsMinecraftColorTextToPlainText() {
        assertEquals("非公開", DiscordTextUtil.plainMinecraftText("&%8非公開"));
    }
}
