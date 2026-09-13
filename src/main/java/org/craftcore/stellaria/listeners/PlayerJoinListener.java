package org.craftcore.stellaria.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.EconomyManager;
import org.craftcore.stellaria.utils.Database;

import java.util.Map;

public class PlayerJoinListener implements Listener {

    private final StellariaCore plugin;

    public PlayerJoinListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        String uuid = event.getPlayer().getUniqueId().toString();
        Player player = event.getPlayer();

        EconomyManager eco = plugin.getEconomyManager();

        double balance = eco.getBalance(player);
        // すでにレコードがあるかチェック
        boolean exists = Database.exists("players", "uuid = ?", uuid);

        if (!exists) {
            Database.insertAsync("players", Map.of(
                "uuid", uuid,
                "name", event.getPlayer().getName(),
                "coins", 0
            ));
        }
    }
}