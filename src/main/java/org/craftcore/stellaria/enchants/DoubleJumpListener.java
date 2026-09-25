package org.craftcore.stellaria.enchants;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 二段跳び: 空中でジャンプキーを押すともう一度跳ぶ。PlayerInputEvent でキーの押下を直接検知する
 * （setAllowFlight を使う方式は、ログアウト時に飛行許可が保存されて無限飛行になる危険があるため使わない）。
 */
public final class DoubleJumpListener implements Listener {

    private final CustomEnchantRegistry registry;
    private final CustomEnchantConfig config;
    private final Map<UUID, Integer> airJumpsUsed = new HashMap<>();

    public DoubleJumpListener(CustomEnchantRegistry registry, CustomEnchantConfig config) {
        this.registry = registry;
        this.config = config;
    }

    @EventHandler
    public void onInput(PlayerInputEvent event) {
        if (!event.getInput().isJump()) {
            return;
        }
        Player player = event.getPlayer();
        int level = registry.level(player.getInventory().getBoots(), CustomEnchant.DOUBLE_JUMP);
        if (level <= 0) {
            return;
        }
        UUID id = player.getUniqueId();
        int used = airJumpsUsed.getOrDefault(id, 0);
        if (!DoubleJumpRules.canAirJump(stateOf(player), used, level, config.movementMinFoodLevel())) {
            return;
        }
        airJumpsUsed.put(id, used + 1);

        Vector forward = player.getLocation().getDirection().setY(0);
        if (forward.lengthSquared() > 0) {
            forward.normalize().multiply(config.doubleJumpForward());
        }
        player.setVelocity(forward.setY(config.doubleJumpVelocityY()));
        player.setFallDistance(0);
        player.setExhaustion(player.getExhaustion() + config.doubleJumpExhaustion());
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 8, 0.3, 0.05, 0.3, 0.02);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_BREEZE_JUMP, 0.6f, 1.2f);
    }

    @EventHandler(ignoreCancelled = true)
    @SuppressWarnings("deprecation") // Player#isOnGround はクライアント申告値だが、着地判定の用途には十分
    public void onMove(PlayerMoveEvent event) {
        if (event.getPlayer().isOnGround()) {
            airJumpsUsed.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        airJumpsUsed.remove(event.getPlayer().getUniqueId());
    }

    @SuppressWarnings("deprecation")
    private static DoubleJumpRules.State stateOf(Player player) {
        GameMode mode = player.getGameMode();
        ItemStack chest = player.getInventory().getChestplate();
        return new DoubleJumpRules.State(
                player.isOnGround(),
                mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE,
                player.isFlying(),
                player.isGliding(),
                player.isInWater(),
                player.isClimbing(),
                chest != null && chest.getType() == Material.ELYTRA,
                player.isInsideVehicle(),
                player.getFoodLevel()
        );
    }
}
