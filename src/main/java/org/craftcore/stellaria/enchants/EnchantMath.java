package org.craftcore.stellaria.enchants;

import java.util.function.DoubleSupplier;

/** カスタムエンチャントの確率・回復量などの計算。Bukkit に依存しないため単体テストできる。 */
public final class EnchantMath {

    private EnchantMath() {
    }

    /** random() が chance 未満なら成功。 */
    public static boolean roll(double chance, DoubleSupplier random) {
        return chance > 0 && random.getAsDouble() < chance;
    }

    /** レベルごとの確率。1.0 を超えない。 */
    public static double chanceForLevel(double perLevel, int level) {
        if (level <= 0) {
            return 0.0;
        }
        return Math.min(1.0, perLevel * level);
    }

    /** amount 個それぞれを独立に判定し、成功した個数を返す。 */
    public static int rollSuccesses(int amount, double chance, DoubleSupplier random) {
        int successes = 0;
        for (int i = 0; i < amount; i++) {
            if (roll(chance, random)) {
                successes++;
            }
        }
        return successes;
    }

    /** 小数の経験値を整数にする。小数部分を確率として 1 を足すか決める（かまどと同じ考え方）。 */
    public static int roundExperience(double exp, DoubleSupplier random) {
        if (exp <= 0) {
            return 0;
        }
        int whole = (int) Math.floor(exp);
        double fraction = exp - whole;
        return whole + (roll(fraction, random) ? 1 : 0);
    }

    public static boolean hasEnoughFood(int foodLevel, int minFoodLevel) {
        return foodLevel >= minFoodLevel;
    }

    /** レベル別の値。範囲外のレベルは最も近い端の値を使う。 */
    public static double perLevel(double[] values, int level) {
        int index = Math.max(0, Math.min(values.length - 1, level - 1));
        return values[index];
    }

    public static double healedHealth(double current, double max, double amount) {
        return Math.min(max, current + amount);
    }

    /** ダメージ後も生きていて、残り体力が最大体力×threshold 以下なら発動する。 */
    public static boolean shouldTriggerLastStand(double remainingHealth, double maxHealth, double threshold) {
        return remainingHealth > 0 && remainingHealth <= maxHealth * threshold;
    }
}
