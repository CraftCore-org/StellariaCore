package org.craftcore.stellaria.rail;

import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailSpeedControllerTest {

    @Test
    void classifiesCurveAndSlopeShapes() {
        assertTrue(RailSpeedController.isCurve(Rail.Shape.NORTH_EAST));
        assertTrue(RailSpeedController.isCurve(Rail.Shape.SOUTH_WEST));
        assertFalse(RailSpeedController.isCurve(Rail.Shape.NORTH_SOUTH));
        assertFalse(RailSpeedController.isCurve(Rail.Shape.ASCENDING_NORTH));

        assertTrue(RailSpeedController.isSlope(Rail.Shape.ASCENDING_EAST));
        assertFalse(RailSpeedController.isSlope(Rail.Shape.EAST_WEST));
        assertFalse(RailSpeedController.isSlope(Rail.Shape.NORTH_EAST));
    }

    @Test
    void endpointsOfReturnsTheTwoConnectedDirections() {
        assertArrayEquals(new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH},
                RailSpeedController.endpointsOf(Rail.Shape.NORTH_SOUTH));
        assertArrayEquals(new BlockFace[]{BlockFace.SOUTH, BlockFace.EAST},
                RailSpeedController.endpointsOf(Rail.Shape.SOUTH_EAST));
    }

    @Test
    void nextDirectionContinuesStraightThroughAStraightRail() {
        // 北向きに走行中、直線区間(NORTH_SOUTH)を通過しても北向きのまま。
        assertEquals(BlockFace.NORTH,
                RailSpeedController.nextDirection(Rail.Shape.NORTH_SOUTH, BlockFace.NORTH));
    }

    @Test
    void nextDirectionTurnsThroughACurve() {
        // SOUTH_EASTは南隣・東隣を接続するカーブ。北向きに進入(=南側から入る)すると東向きに曲がる。
        assertEquals(BlockFace.EAST,
                RailSpeedController.nextDirection(Rail.Shape.SOUTH_EAST, BlockFace.NORTH));
        // 西向きに進入(=東側から入る)すると南向きに曲がる。
        assertEquals(BlockFace.SOUTH,
                RailSpeedController.nextDirection(Rail.Shape.SOUTH_EAST, BlockFace.WEST));
    }

    @Test
    void nextDirectionReturnsNullWhenConnectionIsBroken() {
        // NORTH_SOUTHの直線区間に東向きで進入するのは接続不整合（脱線扱い）
        assertNull(RailSpeedController.nextDirection(Rail.Shape.NORTH_SOUTH, BlockFace.EAST));
    }

    @Test
    void verticalOffsetIsOneOnlyForTheHighSideExit() {
        // ASCENDING_NORTHは北側が高い端。北へ抜ける(=登る)時だけ+1、南へ抜ける(=低い側)時は0。
        assertEquals(1, RailSpeedController.verticalOffset(Rail.Shape.ASCENDING_NORTH, BlockFace.NORTH));
        assertEquals(0, RailSpeedController.verticalOffset(Rail.Shape.ASCENDING_NORTH, BlockFace.SOUTH));

        assertEquals(1, RailSpeedController.verticalOffset(Rail.Shape.ASCENDING_EAST, BlockFace.EAST));
        assertEquals(0, RailSpeedController.verticalOffset(Rail.Shape.ASCENDING_EAST, BlockFace.WEST));

        // 平坦区間・カーブは坂道ではないので常に0。
        assertEquals(0, RailSpeedController.verticalOffset(Rail.Shape.NORTH_SOUTH, BlockFace.NORTH));
        assertEquals(0, RailSpeedController.verticalOffset(Rail.Shape.SOUTH_EAST, BlockFace.EAST));
    }

    @Test
    void brakingDistanceUsesKinematicFormula() {
        // v=20bps, a=10bps^2 -> 20^2/(2*10) = 20 blocks
        assertEquals(20.0, RailSpeedController.brakingDistance(20.0, 10.0), 1e-9);
        assertEquals(Double.MAX_VALUE, RailSpeedController.brakingDistance(20.0, 0.0));
    }

    @Test
    void nextSpeedRampsTowardTargetWithoutOvershooting() {
        // 加速: 1tickあたりaccel/20 = 4.0/20 = 0.2 bps ずつ増える。目標を超えない。
        assertEquals(0.2, RailSpeedController.nextSpeed(0.0, 40.0, 4.0, 8.0), 1e-9);
        assertEquals(5.0, RailSpeedController.nextSpeed(4.9, 5.0, 4.0, 8.0), 1e-9);

        // 減速: 1tickあたりdecel/20 = 8.0/20 = 0.4 bps ずつ減る。目標を下回らない。
        assertEquals(9.6, RailSpeedController.nextSpeed(10.0, 0.0, 4.0, 8.0), 1e-9);
        assertEquals(0.0, RailSpeedController.nextSpeed(0.3, 0.0, 4.0, 8.0), 1e-9);
    }
}
