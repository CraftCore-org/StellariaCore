package org.craftcore.stellaria.utils;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ScoreboardManager（サイドバー）とTabListManager（名前の右側の値）が、お互いのobjective/team
 * を消し合わないように、同じプレイヤー個人の{@link Scoreboard}に相乗りするための共有ヘルパー。
 * orelia-serverutil の {@code BoardUtil} を移植したもの。
 */
public final class BoardUtil {

    private static final Map<UUID, Scoreboard> STELLARIA_BOARDS = new ConcurrentHashMap<>();

    private BoardUtil() {
    }

    /**
     * プレイヤーに既に個人ボード（このプラグインが割り当てたもの）が設定済みならそれを返す。
     * まだサーバー共通のメインスコアボードのままなら、新しい個人ボードを作って割り当ててから返す。
     * サーバーのメインスコアボードそのものは絶対に返さない（他プレイヤーに影響が出ないようにするため）。
     */
    public static Scoreboard ensurePersonalBoard(Player player) {
        Scoreboard current = player.getScoreboard();
        Scoreboard owned = STELLARIA_BOARDS.get(player.getUniqueId());
        if (current == owned) {
            return current;
        }
        if (current != Bukkit.getScoreboardManager().getMainScoreboard()) {
            return null;
        }
        Scoreboard fresh = Bukkit.getScoreboardManager().getNewScoreboard();
        STELLARIA_BOARDS.put(player.getUniqueId(), fresh);
        player.setScoreboard(fresh);
        return fresh;
    }

    /** 退出者に紐づく所有権記録を破棄する。 */
    public static void forgetPlayer(UUID uuid) {
        STELLARIA_BOARDS.remove(uuid);
    }

    /**
     * {@code player}が現在使っているScoreboardが、StellariaCoreがこの{@link #ensurePersonalBoard}で
     * 割り当てたもの（＝所有しているボード）かどうかを判定する。他プラグインが独自Scoreboardを
     * viewerへセットしているタイミングでは{@code false}になるので、team unregister等の直接操作を
     * 呼び出し側でスキップするために使う。
     */
    public static boolean isOwnedBoard(Player player) {
        Scoreboard current = player.getScoreboard();
        Scoreboard owned = STELLARIA_BOARDS.get(player.getUniqueId());
        return owned != null && current == owned;
    }
}
