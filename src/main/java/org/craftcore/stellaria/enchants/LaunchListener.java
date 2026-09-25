package org.craftcore.stellaria.enchants;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 跳躍（エリトラ）: 地上でスニークを長押しして溜め、満タンの状態でジャンプキーを押すと打ち上がる。
 * 上昇が止まったら自動で滑空を始める。建築中のスニークで誤発射しないよう、発射にはジャンプキーを必須にしている。
 */
public final class LaunchListener implements Listener {

    private static final int CHARGE_TICK_STEP = 2;
    private static final int GLIDE_WATCH_MAX_TICKS = 60;

    private final StellariaCore plugin;
    private final CustomEnchantRegistry registry;
    private final CustomEnchantConfig config;
    private final Map<UUID, ScheduledTask> chargeTasks = new HashMap<>();
    private final Set<UUID> charged = new HashSet<>();

    public LaunchListener(StellariaCore plugin, CustomEnchantRegistry registry, CustomEnchantConfig config) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
    }

    private int level(Player player) {
        return registry.level(player.getInventory().getChestplate(), CustomEnchant.LAUNCH);
    }

    @EventHandler
    @SuppressWarnings("deprecation") // Player#isOnGround はクライアント申告値だが、地上判定の用途には十分
    public void onInput(PlayerInputEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        if (event.getInput().isJump() && event.getInput().isSneak() && charged.contains(id)) {
            launch(player);
            return;
        }
        if (event.getInput().isSneak() && !chargeTasks.containsKey(id)
                && player.isOnGround() && !player.isGliding() && level(player) > 0) {
            startCharging(player);
        }
    }

    @SuppressWarnings("deprecation")
    private void startCharging(Player player) {
        UUID id = player.getUniqueId();
        int[] progress = {0};
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, scheduled -> {
            if (!player.getCurrentInput().isSneak() || !player.isOnGround() || level(player) <= 0) {
                stopCharging(id);
                return;
            }
            int chargeTicks = config.launchChargeTicks();
            progress[0] = Math.min(chargeTicks, progress[0] + CHARGE_TICK_STEP);
            double ratio = (double) progress[0] / chargeTicks;
            drawRing(player.getLocation(), 0.3 + ratio * 1.2);
            if (ratio >= 1.0 && charged.add(id)) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1.0f, 1.6f);
                plugin.getActionBarManager().flash(player, "launch",
                        ColorUtil.component(plugin.getConfigManager().getMessage("custom-enchants.launch_charged", player)),
                        40L);
            }
        }, () -> stopCharging(id), 1L, CHARGE_TICK_STEP);
        if (task != null) {
            chargeTasks.put(id, task);
        }
    }

    private void stopCharging(UUID id) {
        ScheduledTask task = chargeTasks.remove(id);
        if (task != null) {
            task.cancel();
        }
        charged.remove(id);
    }

    private static void drawRing(Location center, double radius) {
        for (int i = 0; i < 16; i++) {
            double angle = 2 * Math.PI * i / 16;
            Location point = center.clone().add(Math.cos(angle) * radius, 0.1, Math.sin(angle) * radius);
            center.getWorld().spawnParticle(Particle.END_ROD, point, 1, 0, 0, 0, 0);
        }
    }

    private void launch(Player player) {
        stopCharging(player.getUniqueId());
        int level = level(player);
        if (level <= 0 || !EnchantMath.hasEnoughFood(player.getFoodLevel(), config.movementMinFoodLevel())) {
            return;
        }
        player.setVelocity(new Vector(0, EnchantMath.perLevel(config.launchVelocity(), level), 0));
        player.setExhaustion(player.getExhaustion() + config.launchExhaustion());
        player.getWorld().spawnParticle(Particle.EXPLOSION, player.getLocation(), 1);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.0f, 0.8f);

        // サーバー側の速度はクライアントの実際の動きとずれるため、高さの変化で頂点を判定する
        int[] waited = {0};
        double[] previousY = {player.getLocation().getY()};
        player.getScheduler().runAtFixedRate(plugin, scheduled -> {
            waited[0]++;
            if (waited[0] > GLIDE_WATCH_MAX_TICKS || player.isGliding() || player.isInWater()) {
                scheduled.cancel();
                return;
            }
            double currentY = player.getLocation().getY();
            if (waited[0] > 1 && GlideMath.reachedApex(previousY[0], currentY) && level(player) > 0) {
                player.setGliding(true);
                scheduled.cancel();
                return;
            }
            previousY[0] = currentY;
        }, null, 2L, 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopCharging(event.getPlayer().getUniqueId());
    }
}
