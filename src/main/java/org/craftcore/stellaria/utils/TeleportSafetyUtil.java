package org.craftcore.stellaria.utils;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * home/warpのテレポート先の安全判定と、不安全な場合の「警告→10秒以内の再実行で強制テレポート」
 * フローをまとめたユーティリティ。HomeCommand・WarpCommandの両方から共有する。
 *
 * 保留中の確認状態（{@link PendingConfirm}）はこのクラスでは保持しない。呼び出し側
 * （HomeCommand/WarpCommand）がコマンド種別ごとに持つstatic Mapを毎回引数で受け取って読み書きする。
 */
public final class TeleportSafetyUtil {

    private TeleportSafetyUtil() {
    }

    private static final long CONFIRM_WINDOW_MILLIS = 10_000L;

    // isPassable()がtrueでも着地させたくない危険な素材（溶岩・火・サボテン等）
    private static final Set<Material> HAZARD_MATERIALS = EnumSet.of(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK,
            Material.CACTUS, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE,
            Material.POWDER_SNOW, Material.CAMPFIRE, Material.SOUL_CAMPFIRE
    );

    /**
     * destinationが「安全」かどうかを判定する。
     * 足元・頭上のブロックが通行可能（{@link Block#isPassable()}）で、床(1つ下)に実体があり、
     * 足元/床がハザード素材でないことを見る。{@code isPassable()}はBukkit標準の当たり判定を使うため、
     * フェンス・塀・階段・ハーフブロックなどの複雑な形状も正しく「通れる/通れない」を判定できる。
     */
    public static boolean isSafe(Location destination) {
        Block feet = destination.getBlock();
        Block head = feet.getRelative(BlockFace.UP);
        Block floor = feet.getRelative(BlockFace.DOWN);

        if (!feet.isPassable() || !head.isPassable()) {
            return false;
        }
        if (floor.isPassable()) {
            return false;
        }
        return !HAZARD_MATERIALS.contains(feet.getType()) && !HAZARD_MATERIALS.contains(floor.getType());
    }

    /** 不安全時に保留する確認状態。destinationは再実行時に「同じ行き先への再実行か」の比較に使う。 */
    public record PendingConfirm(Location destination, long expiresAtMillis) {
    }

    public enum Result {
        /** 安全だった、または確認済みで強制テレポートした */
        TELEPORTED,
        /** 不安全だったため警告を出し、確認待ちにした（まだテレポートしていない） */
        WARNED
    }

    /**
     * destinationが安全なら即テレポートする。不安全なら、同じ行き先への直前の警告が10秒以内に
     * 残っていれば強制テレポートし、無ければ新しく警告状態を積んで{@link Result#WARNED}を返す。
     *
     * @param pending 呼び出し元（HomeCommand/WarpCommand）が保持する、コマンド種別ごとの
     *                保留確認static Map（プレイヤー1人につき1件）
     */
    public static Result attempt(Player player, Location destination, Map<UUID, PendingConfirm> pending) {
        UUID playerId = player.getUniqueId();

        if (isSafe(destination)) {
            pending.remove(playerId);
            player.teleport(destination);
            return Result.TELEPORTED;
        }

        long now = System.currentTimeMillis();
        PendingConfirm existing = pending.get(playerId);
        if (existing != null && existing.expiresAtMillis() >= now && sameBlock(existing.destination(), destination)) {
            pending.remove(playerId);
            player.teleport(destination);
            return Result.TELEPORTED;
        }

        pending.put(playerId, new PendingConfirm(destination, now + CONFIRM_WINDOW_MILLIS));
        return Result.WARNED;
    }

    private static boolean sameBlock(Location a, Location b) {
        return a.getWorld() != null && a.getWorld().equals(b.getWorld())
                && a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }
}
