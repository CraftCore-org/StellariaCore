package org.craftcore.stellaria.enchants;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 範囲破壊の判定ロジック。殴った面に垂直な 3x3 の平面のうち、中心を除いた 8 マスを求める。
 * 周囲のブロックは「道具の mineable タグに入っていて、ランクが足りていて、中心以下の硬さで、
 * ブロックエンティティや替えの利かないブロックではない」ものだけ壊す。
 * isPreferredTool は道具を選ばないブロック（ガラス・松明・作物・木材など）にも true を返すため、
 * mineable タグの判定と組み合わせてランクの確認にだけ使う。
 */
public final class ExcavationRules {

    public record Offset(int dx, int dy, int dz) {
    }

    public enum Tool { PICKAXE, SHOVEL }

    /** 壊すと二度と手に入らない、またはシルクタッチでも回収できないブロック。 */
    private static final Set<Material> IRREPLACEABLE = EnumSet.of(
            Material.BUDDING_AMETHYST,
            Material.REINFORCED_DEEPSLATE
    );

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

    /** 範囲破壊の対象になる道具。ツルハシ・シャベル以外は null。 */
    public static @Nullable Tool toolOf(Material material) {
        String name = material.name();
        if (name.endsWith("_PICKAXE")) {
            return Tool.PICKAXE;
        }
        if (name.endsWith("_SHOVEL")) {
            return Tool.SHOVEL;
        }
        return null;
    }

    public static boolean isIrreplaceable(Material material) {
        return IRREPLACEABLE.contains(material);
    }

    /**
     * @param mineableByTool 道具の mineable タグ（#mineable/pickaxe・#mineable/shovel）に入っているか
     * @param preferredTool  Block#isPreferredTool（道具のランクが足りているか）
     * @param irreplaceable  ブロックエンティティ（スポナー・容器・看板など）か、isIrreplaceable に当たるか
     */
    public static boolean canBreakAround(boolean airOrLiquid, boolean mineableByTool, boolean preferredTool,
                                         float hardness, float centerHardness, boolean irreplaceable) {
        return !airOrLiquid
                && mineableByTool
                && preferredTool
                && hardness >= 0
                && hardness <= centerHardness
                && !irreplaceable;
    }

    /** remaining が null（耐久値の無い道具）なら常に続ける。 */
    public static boolean hasEnoughDurability(@Nullable Integer remaining, int minDurability) {
        return remaining == null || remaining >= minDurability;
    }
}
