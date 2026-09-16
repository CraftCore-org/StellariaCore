package org.craftcore.stellaria.utils;

import java.util.List;

public final class WorldBlacklistUtil {
    private WorldBlacklistUtil() {
    }

    public static boolean isBlacklisted(List<String> disabledWorlds, String worldName) {
        return disabledWorlds.stream().anyMatch(name -> name.equalsIgnoreCase(worldName));
    }
}
