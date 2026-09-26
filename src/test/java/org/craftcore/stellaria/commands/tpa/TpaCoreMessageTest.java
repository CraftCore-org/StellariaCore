package org.craftcore.stellaria.commands.tpa;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.managers.ConfigManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TpaCoreMessageTest {

    /** ConfigManager#getMessage の契約どおり、%player% を渡されたプレイヤーの名前で埋める偽物。 */
    private static ConfigManager configFillingPlayerName(String template) {
        ConfigManager config = mock(ConfigManager.class);
        when(config.getMessage(anyString(), any(OfflinePlayer.class))).thenAnswer(invocation -> {
            OfflinePlayer player = invocation.getArgument(1);
            return template.replace("%player%", player.getName());
        });
        return config;
    }

    private static Player player(String name) {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn(name);
        return player;
    }

    @Test
    void messageAboutOtherPlayerShowsTheOtherPlayersName() {
        ConfigManager config = configFillingPlayerName("%player% さんが切断しました");
        assertEquals("Alice さんが切断しました",
                TpaCore.messageAbout(config, "tpa.tpa_warmup_mover_disconnected", player("Alice")));
    }
}
