package org.craftcore.stellaria.utils;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Minecraft の表示用カラーコードを Discord 向けのプレーンテキストへ変換する。 */
public final class DiscordTextUtil {

    private DiscordTextUtil() {
    }

    public static String plainMinecraftText(String input) {
        return PlainTextComponentSerializer.plainText().serialize(ColorUtil.component(input));
    }
}
