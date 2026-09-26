package org.craftcore.stellaria.enchants;

import org.bukkit.block.BlockFace;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 範囲破壊の判定ロジック。殴った面に垂直な 3x3 の平面のうち、中心を除いた 8 マスを求める。
 * 周囲のブロックは「適正道具で掘れて、中心以下の硬さで、容器ではない」ものだけ壊す。
 */
public final class ExcavationRules {

    public record Offset(int dx, int dy, int dz) {
    }

    private ExcavationRules() {
    }

    public static List<Offset> offsets(BlockFace face) {
        List<Offset> result = new ArrayList<>(8);
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) {
                    continue;
                }
                switch (face) {
                    case UP, DOWN -> result.add(new Offset(a, 0, b));
                    case NORTH, SOUTH -> result.add(new Offset(a, b, 0));
                    case EAST, WEST -> result.add(new Offset(0, b, a));
                    default -> {
                        return List.of();
                    }
                }
            }
        }
        return result;
    }

    public static boolean canBreakAround(boolean airOrLiquid, boolean preferredTool,
                                         float hardness, float centerHardness, boolean container) {
        return !airOrLiquid
                && preferredTool
                && hardness >= 0
                && hardness <= centerHardness
                && !container;
    }

    /** remaining が null（耐久値の無い道具）なら常に続ける。 */
    public static boolean hasEnoughDurability(@Nullable Integer remaining, int minDurability) {
        return remaining == null || remaining >= minDurability;
    }
}
