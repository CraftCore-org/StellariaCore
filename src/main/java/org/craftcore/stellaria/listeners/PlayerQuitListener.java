package org.craftcore.stellaria.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.craftcore.stellaria.StellariaCore;

public class PlayerQuitListener implements Listener {
    private final StellariaCore plugin;
    public PlayerQuitListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // messages.yml からフォーマット済みのメッセージを取得
        String quitMsg = plugin.getConfigManager().getMessage("quit", player);

        if (!quitMsg.isEmpty()) {
            event.setQuitMessage(quitMsg);
        } else {
            event.setQuitMessage(null);
        }

        plugin.getPlaytimeManager().onQuit(player);
        plugin.getScoreboardManager().forget(player);
    }
}

