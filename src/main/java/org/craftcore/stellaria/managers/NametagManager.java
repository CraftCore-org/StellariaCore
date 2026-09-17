package org.craftcore.stellaria.managers;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.craftcore.stellaria.utils.BoardUtil;
import org.craftcore.stellaria.utils.ColorUtil;

import java.util.Collection;

/**
 * ランクを持つプレイヤーのネームタグ（頭上に出る名前）の一番左に、ランク色の●ドットを付ける。
 * テキストは付けず色付きドットのみ（タブリストの表示名とは別物）。名前自体の色は変えない
 * （{@link Team#color}を設定しないため、常にデフォルト色のまま）。ランク無しのプレイヤーは
 * どのランクTeamにも所属させず、ドット無しのまま。
 *
 * ScoreboardManager/TabListManager/BelownameManagerと同じく、視聴者ごとの個人ボード
 * （{@link BoardUtil#ensurePersonalBoard}）にTeamを登録する。Teamのentry所属はスコアボード単位で
 * ユニーク（1エントリは同時に1つのTeamにしか属せない）なので、ランクが変わった時も
 * {@link Team#addEntry}を呼ぶだけで前のTeamから自動的に外れる。
 */
public class NametagManager {

    private static final String TEAM_PREFIX = "stellaria_rank_";

    private final RankManager rankManager;
    private volatile boolean enabled;
    private volatile String dotSymbol;

    public NametagManager(RankManager rankManager, boolean enabled, String dotSymbol) {
        this.rankManager = rankManager;
        this.enabled = enabled;
        this.dotSymbol = dotSymbol;
    }

    /** /stellariareload から呼ばれる想定。次のtickで反映される。 */
    public void updateSettings(boolean enabled, String dotSymbol) {
        this.enabled = enabled;
        this.dotSymbol = dotSymbol;
    }

    public void tick() {
        if (!enabled) return;

        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        for (Player viewer : online) {
            Scoreboard board = BoardUtil.ensurePersonalBoard(viewer);
            if (board == null) continue;
            for (Player target : online) {
                applyNametag(board, target);
            }
        }
    }

    private void applyNametag(Scoreboard board, Player target) {
        RankManager.RankInfo rank = rankManager.getRank(target);

        if (rank.key().isEmpty()) {
            Team current = board.getEntryTeam(target.getName());
            if (current != null && current.getName().startsWith(TEAM_PREFIX)) {
                current.removeEntry(target.getName());
            }
            return;
        }

        String teamName = TEAM_PREFIX + rank.key();
        Team team = board.getTeam(teamName);
        if (team == null) {
            team = board.registerNewTeam(teamName);
        }
        team.prefix(dot(rank.color()));
        team.addEntry(target.getName());
    }

    private Component dot(String rankColor) {
        return ColorUtil.component(rankColor + dotSymbol + " ");
    }
}
