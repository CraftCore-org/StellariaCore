package org.craftcore.stellaria.enchants;

import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 釣り人の粘り: 続けて釣り上げるほどボーナスが増える。支払いは既存の釣り収入と同じ IncomeManager を通す。
 * AFK 中は支払わず、連続回数も 0 に戻す（放置釣りの対策）。
 */
public final class AnglerListener implements Listener {

    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    private final StellariaCore plugin;
    private final CustomEnchantRegistry registry;
    private final CustomEnchantConfig config;
    private final AnglerStreakTracker tracker = new AnglerStreakTracker();

    public AnglerListener(StellariaCore plugin, CustomEnchantRegistry registry, CustomEnchantConfig config) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item)) {
            return;
        }
        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand() == null ? EquipmentSlot.HAND : event.getHand();
        ItemStack rod = player.getInventory().getItem(hand);
        int level = registry.level(rod, CustomEnchant.ANGLER);
        if (level <= 0) {
            return;
        }
        if (plugin.getAfkManager().isAfk(player.getUniqueId())) {
            tracker.resetStreak(player.getUniqueId());
            return;
        }

        AnglerStreakTracker.Settings settings = new AnglerStreakTracker.Settings(
                config.anglerStreakWindowMillis(),
                config.anglerBaseAmount(),
                config.anglerMaxStreak(),
                EnchantMath.perLevel(config.anglerLevelMultipliers(), level),
                config.anglerDailyCap());
        AnglerStreakTracker.Result result = tracker.recordCatch(
                player.getUniqueId(), System.currentTimeMillis(), LocalDate.now(JAPAN), settings);

        if (result.payout() > 0) {
            plugin.getIncomeManager().reward(player, result.payout());
            String message = plugin.getConfigManager().getMessage("custom-enchants.angler_streak", player)
                    .replace("%streak%", String.valueOf(result.streak()))
                    .replace("%amount%", plugin.getEconomyManager().formatExact(result.payout()));
            plugin.getActionBarManager().flash(player, "angler", ColorUtil.component(message), 60L);
        }
        if (result.capJustReached()) {
            player.sendMessage(plugin.getConfigManager().getMessage("custom-enchants.angler_daily_cap", player));
        }
    }
}
