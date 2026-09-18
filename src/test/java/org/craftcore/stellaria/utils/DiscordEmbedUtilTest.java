package org.craftcore.stellaria.utils;

import net.dv8tion.jda.api.entities.MessageEmbed;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DiscordEmbedUtilTest {

    @Test
    void usesPlayerHeadAsCompactAuthorIconInsteadOfThumbnail() {
        UUID playerId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

        MessageEmbed embed = DiscordEmbedUtil.playerActivity("Stella", "Stellaが参加しました。", playerId, Color.GREEN);

        assertEquals("Stella", embed.getAuthor().getName());
        assertEquals("https://mc-heads.net/avatar/123e4567-e89b-12d3-a456-426614174000/128",
                embed.getAuthor().getIconUrl());
        assertNull(embed.getThumbnail());
    }
}
