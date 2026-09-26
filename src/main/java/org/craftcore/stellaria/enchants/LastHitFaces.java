package org.craftcore.stellaria.enchants;

import org.bukkit.block.BlockFace;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤーが最後に掘り始めたブロックと、クライアントが送ってきた面（BlockDamageEvent#getBlockFace）を覚える。
 * 壊した瞬間の視線から面を推測すると、歩きながら足元の横のブロックを掘ったときに上面と判定されることがあるため、
 * 掘り始めの時点の面を優先する。プレイヤーごとに 1 件だけ持ち、退出時に forget() で消す。
 */
final class LastHitFaces {

    private record Hit(UUID world, int x, int y, int z, BlockFace face) {
    }

    private final Map<UUID, Hit> hits = new HashMap<>();

    void record(UUID player, UUID world, int x, int y, int z, BlockFace face) {
        hits.put(player, new Hit(world, x, y, z, face));
    }

    /** 同じブロックを掘り始めたときの面。記録が無い、または別のブロックなら null。 */
    @Nullable BlockFace faceFor(UUID player, UUID world, int x, int y, int z) {
        Hit hit = hits.get(player);
        if (hit == null || !hit.world().equals(world) || hit.x() != x || hit.y() != y || hit.z() != z) {
            return null;
        }
        return hit.face();
    }

    void forget(UUID player) {
        hits.remove(player);
    }
}
