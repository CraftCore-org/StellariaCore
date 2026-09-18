package org.craftcore.stellaria.listeners;

import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.craftcore.stellaria.StellariaCore;

public class FishingIncomeListener implements Listener {

    private final StellariaCore plugin;

    public FishingIncomeListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }

        if (!(event.getCaught() instanceof Item item)) {
            return;
        }

        Material material = item.getItemStack().getType();

        int reward = Math.max(
                0,
                plugin.getConfigManager().getInt(
                        "income.fishing.rewards." + material.name(),
                        0,
                        true
                )
        );

        if (reward <= 0) {
            return;
        }

        Player player = event.getPlayer();

        plugin.getIncomeManager().reward(player, reward);
    }
}