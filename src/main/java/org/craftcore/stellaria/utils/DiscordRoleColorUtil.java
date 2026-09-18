package org.craftcore.stellaria.utils;

import java.awt.Color;

/** DiscordロールカラーをMinecraftチャットで使える16進カラーコードへ変換する。 */
public final class DiscordRoleColorUtil {

    private DiscordRoleColorUtil() {
    }

    public static String applyToDisplayName(String displayName, Color roleColor) {
        if (roleColor == null) {
            return displayName;
        }
        return String.format("&#%06x%s", roleColor.getRGB() & 0xFFFFFF, displayName);
    }
}
