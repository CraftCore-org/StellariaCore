package org.craftcore.stellaria.enchants;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.craftcore.stellaria.StellariaCore;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** 戦闘系のカスタムエンチャント（追撃・吸命・背水）。 */
public final class CombatEnchantListener implements Listener {

    private final StellariaCore plugin;
    private final CustomEnchantRegistry registry;
    private final CustomEnchantConfig config;
    private final CooldownTracker lastStandCooldowns = new CooldownTracker();
    /** 追撃ダメージを与えている最中の対象。追撃のダメージから追撃が再発動しないようにする。 */
    private final Set<UUID> pursuitInFlight = new HashSet<>();

    public CombatEnchantListener(StellariaCore plugin, CustomEnchantRegistry registry, CustomEnchantConfig config) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
    }

    // ------------------------------------------------------------------
    // 追撃: モンスターへの近接攻撃で、確率で少し遅れて追加ダメージを与える
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity target) || !(target instanceof Enemy)) {
            return;
        }
        // なぎ払い（ENTITY_SWEEP_ATTACK）や追撃自身のダメージでは発動させない
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || pursuitInFlight.contains(target.getUniqueId())) {
            return;
        }
        int level = registry.level(player.getInventory().getItemInMainHand(), CustomEnchant.PURSUIT);
        if (level <= 0) {
            return;
        }
        double chance = EnchantMath.chanceForLevel(config.pursuitChancePerLevel(), level);
        if (!EnchantMath.roll(chance, ThreadLocalRandom.current()::nextDouble)) {
            return;
        }
        double damage = event.getDamage() * config.pursuitDamageMultiplier();
        UUID playerId = player.getUniqueId();
        target.getScheduler().runDelayed(plugin, task -> strikePursuit(target, playerId, damage), null,
                config.pursuitDelayTicks());
    }

    private void strikePursuit(LivingEntity target, UUID playerId, double damage) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || target.isDead() || !target.isValid()) {
            return;
        }
        // 無敵時間を無視し、ノックバックは付けない（元の速度に戻す）。source にプレイヤーを渡してキルの帰属を保つ
        Vector velocity = target.getVelocity();
        target.setNoDamageTicks(0);
        pursuitInFlight.add(target.getUniqueId());
        try {
            target.damage(damage, player);
        } finally {
            pursuitInFlight.remove(target.getUniqueId());
        }
        target.setVelocity(velocity);
        target.getWorld().spawnParticle(Particle.CRIT,
                target.getLocation().add(0, target.getHeight() / 2, 0), 12, 0.3, 0.3, 0.3, 0.1);
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 0.8f, 1.4f);
    }

    // ------------------------------------------------------------------
    // 吸命: 倒したときに回復する（PvP でも有効）
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || killer.isDead()) {
            return;
        }
        int level = registry.level(killer.getInventory().getItemInMainHand(), CustomEnchant.LIFESTEAL);
        if (level <= 0) {
            return;
        }
        double healed = EnchantMath.healedHealth(killer.getHealth(), maxHealth(killer),
                config.lifestealHealPerLevel() * level);
        killer.setHealth(healed);
        killer.getWorld().spawnParticle(Particle.HEART, killer.getLocation().add(0, 2, 0), 3, 0.3, 0.2, 0.3, 0);
    }

    // ------------------------------------------------------------------
    // 背水: 体力が閾値以下になったら一定時間強化。PvP 可能な場所では発動しない
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onDamaged(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (registry.level(player.getInventory().getHelmet(), CustomEnchant.LAST_STAND) <= 0) {
            return;
        }
        double remaining = player.getHealth() - event.getFinalDamage();
        if (!EnchantMath.shouldTriggerLastStand(remaining, maxHealth(player), config.lastStandHealthThreshold())) {
            return;
        }
        if (isPvpZone(player)) {
            return;
        }
        if (!lastStandCooldowns.tryUse(player.getUniqueId(), System.currentTimeMillis(),
                config.lastStandCooldownMillis())) {
            return;
        }
        int duration = config.lastStandDurationTicks();
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, duration, config.lastStandSpeedAmplifier()));
        player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, duration, config.lastStandStrengthAmplifier()));
        player.sendMessage(plugin.getConfigManager().getMessage("custom-enchants.last_stand_activated", player)
                .replace("%seconds%", String.valueOf(duration / 20)));
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 0.6f, 1.2f);
    }

    /** ワールドの PvP が有効で、かつ土地のルールでも PvP が許可されている場所。 */
    private boolean isPvpZone(Player player) {
        return player.getWorld().getPVP() && plugin.getLandManager().isPvpAllowed(player.getLocation());
    }

    private static double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20.0 : attribute.getValue();
    }
}
