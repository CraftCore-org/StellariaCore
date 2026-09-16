package org.craftcore.stellaria.listeners;

import com.vexsoftware.votifier.model.VotifierEvent;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.craftcore.stellaria.StellariaCore;

/** NuVotifier が発行する投票通知に報酬を付与する。 */
public class VoteListener implements Listener {

    private final StellariaCore plugin;

    public VoteListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onVote(VotifierEvent event) {
        String username = event.getVote().getUsername();
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(username);
        double amount = plugin.getConfigManager().getDouble("vote.reward.money", 500);
        plugin.getEconomyManager().ensurePlayerRecord(offlinePlayer);
        EconomyResponse response = plugin.getEconomyManager().depositPlayer(offlinePlayer, amount);

        if (!response.transactionSuccess()) {
            plugin.getLogger().warning("投票報酬の付与に失敗しました: " + username + " (" + response.errorMessage + ")");
            return;
        }

        Player player = offlinePlayer.getPlayer();
        if (player != null) {
            String message = plugin.getConfigManager().getMessage("vote.reward_received", player)
                .replace("%player%", username)
                .replace("%amount%", plugin.getEconomyManager().format(amount));
            player.sendMessage(message);
        }
    }
}
