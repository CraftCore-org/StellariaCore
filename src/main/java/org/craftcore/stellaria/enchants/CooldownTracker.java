package org.craftcore.stellaria.enchants;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤーごとのクールダウン。メモリのみで管理し、再ログインでは消さない（サーバー再起動でリセットされる）。
 * イベントはメインスレッドからしか呼ばれないため同期はしない。
 */
public final class CooldownTracker {

    private final Map<UUID, Long> readyAtMillis = new HashMap<>();

    /** クールダウン中でなければ使用済みにして true、クールダウン中なら false。 */
    public boolean tryUse(UUID id, long nowMillis, long cooldownMillis) {
        Long readyAt = readyAtMillis.get(id);
        if (readyAt != null && nowMillis < readyAt) {
            return false;
        }
        readyAtMillis.put(id, nowMillis + cooldownMillis);
        return true;
    }

    public long remainingMillis(UUID id, long nowMillis) {
        Long readyAt = readyAtMillis.get(id);
        return readyAt == null ? 0 : Math.max(0, readyAt - nowMillis);
    }
}
