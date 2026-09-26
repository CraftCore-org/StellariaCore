package org.craftcore.stellaria.enchants;

import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.ConfigManager;

import java.util.Arrays;
import java.util.List;

/**
 * config.yml の custom-enchants.* を読み込んでキャッシュする。移動・攻撃のたびに呼ばれるため、
 * RailConfig と同じく ConfigManager への文字列引きを毎回行わない。不正な値は既定値に戻して警告する。
 * /stellariareload 時は CustomEnchantModule#reload() から reload() が呼ばれる。
 */
public final class CustomEnchantConfig {

    private static final String ROOT = "custom-enchants.";

    private final StellariaCore plugin;

    private volatile int movementMinFoodLevel;
    private volatile double pursuitChancePerLevel;
    private volatile int pursuitDelayTicks;
    private volatile double pursuitDamageMultiplier;
    private volatile double lifestealHealPerLevel;
    private volatile double lastStandHealthThreshold;
    private volatile int lastStandDurationTicks;
    private volatile int lastStandSpeedAmplifier;
    private volatile int lastStandStrengthAmplifier;
    private volatile long lastStandCooldownMillis;
    private volatile double harvestChancePerLevel;
    private volatile double[] glideBoostIntervalSeconds;
    private volatile double glideBoostStrength;
    private volatile float glideBoostExhaustion;
    private volatile int launchChargeTicks;
    private volatile double[] launchVelocity;
    private volatile float launchExhaustion;
    private volatile double doubleJumpVelocityY;
    private volatile double doubleJumpForward;
    private volatile float doubleJumpExhaustion;
    private volatile long anglerStreakWindowMillis;
    private volatile long anglerBaseAmount;
    private volatile int anglerMaxStreak;
    private volatile double[] anglerLevelMultipliers;
    private volatile long anglerDailyCap;

    public CustomEnchantConfig(StellariaCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        movementMinFoodLevel = intAtLeast("movement.min-food-level", 7, 0);
        pursuitChancePerLevel = chance("pursuit.chance-per-level", 0.10);
        pursuitDelayTicks = intAtLeast("pursuit.delay-ticks", 8, 1);
        pursuitDamageMultiplier = nonNegative("pursuit.damage-multiplier", 0.5);
        lifestealHealPerLevel = nonNegative("lifesteal.heal-per-level", 2.0);
        lastStandHealthThreshold = chance("last-stand.health-threshold", 0.3);
        lastStandDurationTicks = intAtLeast("last-stand.duration-ticks", 200, 1);
        lastStandSpeedAmplifier = intAtLeast("last-stand.speed-amplifier", 1, 0);
        lastStandStrengthAmplifier = intAtLeast("last-stand.strength-amplifier", 0, 0);
        lastStandCooldownMillis = intAtLeast("last-stand.cooldown-seconds", 900, 0) * 1000L;
        harvestChancePerLevel = chance("harvest.chance-per-level", 0.05);
        glideBoostIntervalSeconds = perLevel("glide-boost.interval-seconds", new double[]{15, 12, 10});
        glideBoostStrength = nonNegative("glide-boost.strength", 0.6);
        glideBoostExhaustion = (float) nonNegative("glide-boost.exhaustion", 1.5);
        launchChargeTicks = intAtLeast("launch.charge-ticks", 30, 1);
        launchVelocity = perLevel("launch.velocity", new double[]{1.4, 1.9});
        launchExhaustion = (float) nonNegative("launch.exhaustion", 3.0);
        doubleJumpVelocityY = nonNegative("double-jump.velocity-y", 0.6);
        doubleJumpForward = nonNegative("double-jump.forward", 0.3);
        doubleJumpExhaustion = (float) nonNegative("double-jump.exhaustion", 1.0);
        anglerStreakWindowMillis = intAtLeast("angler.streak-window-seconds", 60, 1) * 1000L;
        anglerBaseAmount = intAtLeast("angler.base-amount", 5, 0);
        anglerMaxStreak = intAtLeast("angler.max-streak", 10, 1);
        anglerLevelMultipliers = perLevel("angler.level-multipliers", new double[]{1.0, 1.5, 2.0});
        anglerDailyCap = intAtLeast("angler.daily-cap", 3000, 0);
    }

    private ConfigManager config() {
        return plugin.getConfigManager();
    }

    private void warnInvalid(String path, Object fallback) {
        plugin.getLogger().warning("config.yml の " + ROOT + path + " が不正なため、既定値 " + fallback + " を使います。");
    }

    private double chance(String path, double def) {
        double value = config().getDouble(ROOT + path, def);
        if (value < 0 || value > 1) {
            warnInvalid(path, def);
            return def;
        }
        return value;
    }

    private double nonNegative(String path, double def) {
        double value = config().getDouble(ROOT + path, def);
        if (value < 0) {
            warnInvalid(path, def);
            return def;
        }
        return value;
    }

    private int intAtLeast(String path, int def, int min) {
        int value = config().getInt(ROOT + path, def);
        if (value < min) {
            warnInvalid(path, def);
            return def;
        }
        return value;
    }

    private double[] perLevel(String path, double[] def) {
        List<String> raw = config().getStringList(ROOT + path);
        if (raw.isEmpty()) {
            return def;
        }
        try {
            double[] values = raw.stream().mapToDouble(Double::parseDouble).toArray();
            if (Arrays.stream(values).anyMatch(value -> value < 0)) {
                warnInvalid(path, Arrays.toString(def));
                return def;
            }
            return values;
        } catch (NumberFormatException e) {
            warnInvalid(path, Arrays.toString(def));
            return def;
        }
    }

    public int movementMinFoodLevel() { return movementMinFoodLevel; }
    public double pursuitChancePerLevel() { return pursuitChancePerLevel; }
    public int pursuitDelayTicks() { return pursuitDelayTicks; }
    public double pursuitDamageMultiplier() { return pursuitDamageMultiplier; }
    public double lifestealHealPerLevel() { return lifestealHealPerLevel; }
    public double lastStandHealthThreshold() { return lastStandHealthThreshold; }
    public int lastStandDurationTicks() { return lastStandDurationTicks; }
    public int lastStandSpeedAmplifier() { return lastStandSpeedAmplifier; }
    public int lastStandStrengthAmplifier() { return lastStandStrengthAmplifier; }
    public long lastStandCooldownMillis() { return lastStandCooldownMillis; }
    public double harvestChancePerLevel() { return harvestChancePerLevel; }
    public double[] glideBoostIntervalSeconds() { return glideBoostIntervalSeconds; }
    public double glideBoostStrength() { return glideBoostStrength; }
    public float glideBoostExhaustion() { return glideBoostExhaustion; }
    public int launchChargeTicks() { return launchChargeTicks; }
    public double[] launchVelocity() { return launchVelocity; }
    public float launchExhaustion() { return launchExhaustion; }
    public double doubleJumpVelocityY() { return doubleJumpVelocityY; }
    public double doubleJumpForward() { return doubleJumpForward; }
    public float doubleJumpExhaustion() { return doubleJumpExhaustion; }
    public long anglerStreakWindowMillis() { return anglerStreakWindowMillis; }
    public long anglerBaseAmount() { return anglerBaseAmount; }
    public int anglerMaxStreak() { return anglerMaxStreak; }
    public double[] anglerLevelMultipliers() { return anglerLevelMultipliers; }
    public long anglerDailyCap() { return anglerDailyCap; }
}
