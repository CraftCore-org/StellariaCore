package org.craftcore.stellaria.utils;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;

/**
 * ロックアウト窓中の対象ワールドへの再入場を「警告→10秒以内の再実行で強制許可」で確認する
 * フロー。TeleportSafetyUtilと同じ確認パターンだが、判定対象が「不安全な着地点」ではなく
 * 「リセット待ちワールドへの入場」であるため別クラスに切り出している。
 *
 * 保留中の確認状態はこのクラスでは保持しない。呼び出し側（WorldResetListener）が
 * static Mapを引数で渡す（TeleportSafetyUtilと同じ設計）。
 */
public final class WorldResetSafetyUtil {

    private WorldResetSafetyUtil() {
    }

    private static final long CONFIRM_WINDOW_MILLIS = 10_000L;

    public record PendingConfirm(String worldName, long expiresAtMillis) {
    }

    public enum Result {
        /** 確認済みで通過を許可した */
        ALLOWED,
        /** ロックアウト対象のため警告を出し、確認待ちにした（まだ許可していない） */
        WARNED
    }

    /**
     * targetWorldNameへの入場を試みる。直前10秒以内に同じワールドへの警告が残っていれば
     * ALLOWED（強制許可）、無ければ新しく警告状態を積んでWARNEDを返す。
     * ロックアウト対象かどうかの判定は呼び出し側（WorldResetListener）が先に行う。
     */
    public static Result attempt(Player player, String targetWorldName, Map<UUID, PendingConfirm> pending) {
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();

        PendingConfirm existing = pending.get(playerId);
        if (existing != null && existing.expiresAtMillis() >= now && existing.worldName().equals(targetWorldName)) {
            pending.remove(playerId);
            return Result.ALLOWED;
        }

        pending.put(playerId, new PendingConfirm(targetWorldName, now + CONFIRM_WINDOW_MILLIS));
        return Result.WARNED;
    }
}
