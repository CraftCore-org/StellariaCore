package org.craftcore.stellaria.managers;

import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class IncomeManager {

    private final StellariaCore plugin;

    private final Map<UUID, Long> pendingRewards = new HashMap<>();
    private final Set<UUID> scheduledPlayers = new HashSet<>();

    public IncomeManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /**
     * 同じtick内の報酬をまとめて、DB更新を1回だけにする。
     * /mineで大量の鉱石を一括破壊した場合のDB負荷対策。
     */
    public void reward(Player player, long amount) {
        if (amount <= 0) {
            return;
        }

        UUID uuid = player.getUniqueId();

        pendingRewards.merge(uuid, amount, Long::sum);

        if (!scheduledPlayers.add(uuid)) {
            return;
        }

        player.getScheduler().run(plugin, task -> flush(player), null);
    }

    private void flush(Player player) {
        UUID uuid = player.getUniqueId();

        scheduledPlayers.remove(uuid);

        Long amount = pendingRewards.remove(uuid);
        if (amount == null || amount <= 0) {
            return;
        }

        var response = plugin.getEconomyManager().depositPlayer(player, amount);

        if (!response.transactionSuccess()) {
            plugin.getLogger().warning(
                    player.getName() + " への収入 " + amount + "円 の付与に失敗しました。"
            );
            return;
        }

        String message = plugin.getConfigManager()
                .getMessage("income.earned", player)
                .replace("%amount%", plugin.getEconomyManager().formatExact(amount));

        plugin.getActionBarManager().flash(
                player,
                "income",
                ColorUtil.component(message),
                40L
        );
    }
}