package org.craftcore.stellaria.enchants;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 植樹で「どの苗木を」「どこに」植えるかを決める。Bukkit に依存しないため単体テストできる。 */
public final class SaplingPlanner {

    public record Pos(int x, int y, int z) {
    }

    private SaplingPlanner() {
    }

    /** 丸太・木の Material 名から苗木の Material 名を返す。植えられない種類（皮むき、ネザーの幹など）は null。 */
    public static String saplingFor(String logMaterialName) {
        String base;
        if (logMaterialName.endsWith("_LOG")) {
            base = logMaterialName.substring(0, logMaterialName.length() - "_LOG".length());
        } else if (logMaterialName.endsWith("_WOOD")) {
            base = logMaterialName.substring(0, logMaterialName.length() - "_WOOD".length());
        } else {
            return null;
        }
        return switch (base) {
            case "OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "CHERRY", "PALE_OAK" -> base + "_SAPLING";
            case "MANGROVE" -> "MANGROVE_PROPAGULE";
            default -> null;
        };
    }

    /**
     * roots は伐採前に最下段にあった丸太の座標。2x2 の正方形が含まれていればその 4 点、無ければ先頭の 1 点を返す。
     */
    public static List<Pos> plan(List<Pos> roots) {
        if (roots.isEmpty()) {
            return List.of();
        }
        Set<Pos> set = new HashSet<>(roots);
        for (Pos corner : roots) {
            Pos east = new Pos(corner.x() + 1, corner.y(), corner.z());
            Pos south = new Pos(corner.x(), corner.y(), corner.z() + 1);
            Pos southEast = new Pos(corner.x() + 1, corner.y(), corner.z() + 1);
            if (set.contains(east) && set.contains(south) && set.contains(southEast)) {
                return List.of(corner, east, south, southEast);
            }
        }
        return List.of(roots.get(0));
    }
}
