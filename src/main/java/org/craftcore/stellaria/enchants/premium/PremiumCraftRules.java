package org.craftcore.stellaria.enchants.premium;

/**
 * クラフトグリッドの中身から、上質作物を含むクラフトを許可するかを決める。
 * 上質作物 1 個だけを置いたときは通常作物 3 個への逆変換、それ以外で上質作物が混ざっていれば禁止する。
 */
public final class PremiumCraftRules {

    public enum Verdict {
        ALLOW,
        BLOCK,
        CONVERT_TO_NORMAL
    }

    private PremiumCraftRules() {
    }

    /**
     * @param premiumSlots  上質作物が入っているスロット数
     * @param nonEmptySlots 何かが入っているスロット数
     */
    public static Verdict judge(int premiumSlots, int nonEmptySlots) {
        if (premiumSlots == 0) {
            return Verdict.ALLOW;
        }
        if (premiumSlots == 1 && nonEmptySlots == 1) {
            return Verdict.CONVERT_TO_NORMAL;
        }
        return Verdict.BLOCK;
    }
}
