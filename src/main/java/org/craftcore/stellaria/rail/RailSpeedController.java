package org.craftcore.stellaria.rail;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * レール1マスぶんの形状(Rail.Shape)から「進行方向がどう変わるか」「最大速度はいくつか」を
 * 導出する純粋ロジック。Bukkitイベントやセッション状態には触れない（RailManagerが呼び出す側）。
 *
 * 方向の考え方: BlockFaceは「これから進む先」を表す。ある区間の形状には必ず2つの端点
 * （例: NORTH_EAST は北隣・東隣と接続）があり、入ってきた側（進行方向の逆）と一致する端点の
 * 反対側が、通過後の新しい進行方向になる。
 */
public final class RailSpeedController {

    private RailSpeedController() {
    }

    private static final Map<Rail.Shape, BlockFace[]> ENDPOINTS = new EnumMap<>(Rail.Shape.class);
    static {
        ENDPOINTS.put(Rail.Shape.NORTH_SOUTH, new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH});
        ENDPOINTS.put(Rail.Shape.EAST_WEST, new BlockFace[]{BlockFace.EAST, BlockFace.WEST});
        ENDPOINTS.put(Rail.Shape.ASCENDING_NORTH, new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH});
        ENDPOINTS.put(Rail.Shape.ASCENDING_SOUTH, new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH});
        ENDPOINTS.put(Rail.Shape.ASCENDING_EAST, new BlockFace[]{BlockFace.EAST, BlockFace.WEST});
        ENDPOINTS.put(Rail.Shape.ASCENDING_WEST, new BlockFace[]{BlockFace.EAST, BlockFace.WEST});
        ENDPOINTS.put(Rail.Shape.NORTH_EAST, new BlockFace[]{BlockFace.NORTH, BlockFace.EAST});
        ENDPOINTS.put(Rail.Shape.NORTH_WEST, new BlockFace[]{BlockFace.NORTH, BlockFace.WEST});
        ENDPOINTS.put(Rail.Shape.SOUTH_EAST, new BlockFace[]{BlockFace.SOUTH, BlockFace.EAST});
        ENDPOINTS.put(Rail.Shape.SOUTH_WEST, new BlockFace[]{BlockFace.SOUTH, BlockFace.WEST});
    }

    private static final Set<Rail.Shape> CURVE_SHAPES = Set.of(
            Rail.Shape.NORTH_EAST, Rail.Shape.NORTH_WEST, Rail.Shape.SOUTH_EAST, Rail.Shape.SOUTH_WEST
    );

    /** ブロックがレール（通常/パワード/ディテクター/アクティベーター）なら形状を返す。それ以外はnull。 */
    public static Rail.Shape shapeAt(Block block) {
        return block.getBlockData() instanceof Rail rail ? rail.getShape() : null;
    }

    /** その形状が接続する2方向。交差点等バニラに存在しない形状ならnull。 */
    public static BlockFace[] endpointsOf(Rail.Shape shape) {
        BlockFace[] endpoints = ENDPOINTS.get(shape);
        return endpoints != null ? endpoints.clone() : null;
    }

    public static boolean isCurve(Rail.Shape shape) {
        return shape != null && CURVE_SHAPES.contains(shape);
    }

    public static boolean isSlope(Rail.Shape shape) {
        return shape != null && shape.name().startsWith("ASCENDING_");
    }

    /** その区間の設定上の最大速度 (blocks/s)。形状がnullなら0。 */
    public static double maxSpeedFor(Rail.Shape shape, RailConfig config) {
        if (shape == null) {
            return 0.0;
        }
        if (isCurve(shape)) {
            return config.getCurveSpeedBps();
        }
        if (isSlope(shape)) {
            return config.getSlopeSpeedBps();
        }
        return config.getStraightSpeedBps();
    }

    /**
     * shapeの区間を通過した後の進行方向。
     * 入ってきた方向（incomingDirectionの逆）がこの形状の端点に含まれなければ、
     * T字分岐や脱線などバニラの直線接続が壊れている状態を意味するのでnullを返す
     * （呼び出し側はこれを「脱線」として扱う）。
     */
    public static BlockFace nextDirection(Rail.Shape shape, BlockFace incomingDirection) {
        BlockFace[] endpoints = ENDPOINTS.get(shape);
        if (endpoints == null) {
            return null;
        }
        BlockFace enteredFrom = incomingDirection.getOppositeFace();
        if (endpoints[0] == enteredFrom) {
            return endpoints[1];
        }
        if (endpoints[1] == enteredFrom) {
            return endpoints[0];
        }
        return null;
    }

    /** 等加速度運動の制動距離 (v^2 / 2a)。blocks単位。decelerationBps2が0以下ならMAX_VALUE。 */
    public static double brakingDistance(double speedBps, double decelerationBps2) {
        if (decelerationBps2 <= 0) {
            return Double.MAX_VALUE;
        }
        return (speedBps * speedBps) / (2.0 * decelerationBps2);
    }

    /**
     * 現在速度をtargetBpsへ、1tick(=1/20秒)あたりaccelerationBps2/decelerationBps2の範囲で近づける。
     * 加速中と減速中で異なる変化率を使うことで「急停止しない」を満たす。
     */
    public static double nextSpeed(double currentBps, double targetBps, double accelerationBps2, double decelerationBps2) {
        boolean speedingUp = targetBps >= currentBps;
        double ratePerSecond = speedingUp ? accelerationBps2 : decelerationBps2;
        double deltaPerTick = ratePerSecond / 20.0;
        if (speedingUp) {
            return Math.min(targetBps, currentBps + deltaPerTick);
        }
        return Math.max(targetBps, currentBps - deltaPerTick);
    }
}
