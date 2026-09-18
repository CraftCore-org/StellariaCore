package org.craftcore.stellaria.listeners;

import com.vexsoftware.votifier.model.VotifierEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.VoteMessageUtil;

import java.util.List;

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

        double amount =
                plugin.getConfigManager()
                        .getDouble("vote.reward.money", 500);

        plugin.getEconomyManager().ensurePlayerRecord(offlinePlayer);

        EconomyResponse response =
                plugin.getEconomyManager()
                        .depositPlayer(offlinePlayer, amount);

        if (!response.transactionSuccess()) {
            plugin.getLogger().warning(
                    "投票報酬の付与に失敗しました: "
                            + username
                            + " ("
                            + response.errorMessage
                            + ")"
            );
            return;
        }

        Player player = offlinePlayer.getPlayer();

        if (player != null) {
            String message =
                    plugin.getConfigManager()
                            .getMessage("vote.reward_received", player)
                            .replace("%player%", username)
                            .replace(
                                    "%amount%",
                                    plugin.getEconomyManager().format(amount)
                            );

            player.sendMessage(message);
        }

        if (plugin.getConfigManager()
                .getBoolean("vote.broadcast.enabled", true)) {

            broadcastVote(username, offlinePlayer);
        }
    }

    private void broadcastVote(
            String username,
            OfflinePlayer player
    ) {
        List<String> lines =
                plugin.getConfigManager()
                        .getStringList("vote.broadcast.message");

        List<Component> components =
                VoteMessageUtil.build(
                        plugin,
                        lines,
                        player
                );

        for (Component component : components) {
            Bukkit.broadcast(component);
        }
    }
}