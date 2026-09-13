package org.craftcore.stellaria.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.Format;

public class PlayerQuitListener {
    private final StellariaCore plugin;
    public PlayerQuitListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // config.yml から元の文字列を取得
        String rawMsg = plugin.getConfig().getString("messages.quit", "");

        if (!rawMsg.isEmpty()) {
            // Formatを使ってプレースホルダー置き換え
            event.setQuitMessage(Format.text(player, rawMsg));
        } else {
            event.setQuitMessage(null);
        }
    }
}

