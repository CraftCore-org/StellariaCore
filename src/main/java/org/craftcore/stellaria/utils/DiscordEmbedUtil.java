package org.craftcore.stellaria.utils;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.Color;
import java.util.UUID;

/** DiscordのMinecraftプレイヤー通知用Embedを組み立てる。 */
public final class DiscordEmbedUtil {

    private DiscordEmbedUtil() {
    }

    public static MessageEmbed playerActivity(String playerName, String description, UUID playerId, Color color) {
        return new EmbedBuilder()
                .setAuthor(playerName, null, "https://mc-heads.net/avatar/" + playerId + "/128")
                .setDescription(description)
                .setColor(color)
                .build();
    }
}
