package org.craftcore.stellaria.enchants;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import org.craftcore.stellaria.StellariaCore;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 滑空加速（エリトラ）: 滑空を始めてから一定間隔ごとに、進行方向へ自動で加速する。
 * 着地・入水などで滑空が終わるとタスクが止まり、次の滑空で間隔のカウントが最初からになる。
 */
public final class GlideBoostListener implements Listener {

    private final StellariaCore plugin;
    private final CustomEnchantRegistry registry;
    private final CustomEnchantConfig config;
    private final Map<UUID, ScheduledTask> boostTasks = new HashMap<>();

    public GlideBoostListener(StellariaCore plugin, CustomEnchantRegistry registry, CustomEnchantConfig config) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        cancel(player.getUniqueId());
        if (!event.isGliding()) {
            return;
        }
        int level = registry.level(player.getInventory().getChestplate(), CustomEnchant.GLIDE_BOOST);
        if (level <= 0) {
            return;
        }
        long intervalTicks = Math.max(1L, Math.round(EnchantMath.perLevel(config.glideBoostIntervalSeconds(), level) * 20));
        // サーバー側の速度はクライアントの実際の動きとずれるため、毎 tick の位置の変化を記録し、
        // 加速のタイミングでその移動量に上乗せする（getVelocity() に足すと減速になることがある）
        long[] ticks = {0};
        Location[] previous = {player.getLocation()};
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, scheduled -> {
            if (!player.isGliding()) {
                cancel(player.getUniqueId());
                return;
            }
            Location current = player.getLocation();
            Vector motion = current.toVector().subtract(previous[0].toVector());
            previous[0] = current;
            if (++ticks[0] % intervalTicks != 0
                    || !EnchantMath.hasEnoughFood(player.getFoodLevel(), config.movementMinFoodLevel())) {
                return;
            }
            player.setVelocity(GlideMath.boostedVelocity(motion, current.getDirection(), config.glideBoostStrength()));
            player.setExhaustion(player.getExhaustion() + config.glideBoostExhaustion());
            player.getWorld().spawnParticle(Particle.FIREWORK, current, 10, 0.2, 0.2, 0.2, 0.05);
            player.getWorld().playSound(current, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.7f, 1.3f);
        }, () -> boostTasks.remove(player.getUniqueId()), 1L, 1L);
        if (task != null) {
            boostTasks.put(player.getUniqueId(), task);
        }
    }

    private void cancel(UUID id) {
        ScheduledTask task = boostTasks.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId());
    }
}
