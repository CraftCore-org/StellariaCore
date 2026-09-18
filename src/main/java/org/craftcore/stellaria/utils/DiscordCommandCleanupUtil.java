package org.craftcore.stellaria.utils;

import java.util.Set;

/** StellariaCoreが過去に登録した、削除対象のDiscordコマンドを判定する。 */
public final class DiscordCommandCleanupUtil {

    private static final Set<String> RETIRED_COMMANDS = Set.of("whois", "discordconfig");

    private DiscordCommandCleanupUtil() {
    }

    public static boolean isRetiredCommand(String name) {
        return RETIRED_COMMANDS.contains(name);
    }
}
