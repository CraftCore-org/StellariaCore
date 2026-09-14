package org.craftcore.stellaria.utils;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;

/**
 * ScoreboardManager（サイドバー）とTabListManager（名前の右側の値）が、お互いのobjective/team
 * を消し合わないように、同じプレイヤー個人の{@link Scoreboard}に相乗りするための共有ヘルパー。
 * orelia-serverutil の {@code BoardUtil} を移植したもの。
 */
public final class BoardUtil {

    private BoardUtil() {
    }

    /**
     * プレイヤーに既に個人ボード（このプラグインが割り当てたもの）が設定済みならそれを返す。
     * まだサーバー共通のメインスコアボードのままなら、新しい個人ボードを作って割り当ててから返す。
     * サーバーのメインスコアボードそのものは絶対に返さない（他プレイヤーに影響が出ないようにするため）。
     */
    public static Scoreboard ensurePersonalBoard(Player player) {
        Scoreboard current = player.getScoreboard();
        if (current != Bukkit.getScoreboardManager().getMainScoreboard()) {
            return current;
        }
        Scoreboard fresh = Bukkit.getScoreboardManager().getNewScoreboard();
        player.setScoreboard(fresh);
        return fresh;
    }
}
