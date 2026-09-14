package org.craftcore.stellaria.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.EconomyManager;
import org.craftcore.stellaria.managers.DatabaseManager;

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

        // messages.yml からフォーマット済みのメッセージを取得
        String joinMsg = plugin.getConfigManager().getMessage("join", player);

        if (!joinMsg.isEmpty()) {
            event.setJoinMessage(joinMsg);
        } else {
            // 空メッセージの場合はメッセージ自体を非表示にする
            event.setJoinMessage(null);
        }

        EconomyManager eco = plugin.getEconomyManager();

        double balance = eco.getBalance(player);
        // すでにレコードがあるかチェック
        boolean exists = DatabaseManager.exists("players", "uuid = ?", uuid);

        if (!exists) {
            int defaultBalance = plugin.getConfigManager().getInt("economy.default-balance", 1000);
            DatabaseManager.insertAsync("players", Map.of(
                "uuid", uuid,
                "name", event.getPlayer().getName(),
                "coins", defaultBalance
            ));
        }
    }
}