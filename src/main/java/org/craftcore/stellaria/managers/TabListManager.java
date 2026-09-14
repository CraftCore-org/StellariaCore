package org.craftcore.stellaria.managers;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.craftcore.stellaria.utils.BoardUtil;
import org.craftcore.stellaria.utils.ColorUtil;

import java.util.Collection;

/**
 * タブリストのヘッダー/フッターと、名前の右側に出る値（Ping等）を管理するクラス。
 * 旧 {@code TabList}（Join時に1回だけヘッダー/フッターを更新する静的クラス）を置き換え、
 * tick処理で常に最新の状態を反映する。名前の色/prefix/suffixの変更は今のところ扱わない
 * （orelia-serverutil の {@code TabListManager} と違って、対応する権限グループ等の
 * 仕組みがまだ無いため）。
 */
public class TabListManager {

    private static final String VALUE_OBJECTIVE_NAME = "stellaria_tlv";

    private final PlaceholderManager placeholders;
    private final RankManager rankManager;
    private volatile String headerTemplate;
    private volatile String footerTemplate;
    private volatile String valueTemplate;
    private volatile String rankPrefix;

    public TabListManager(PlaceholderManager placeholders, RankManager rankManager, String headerTemplate, String footerTemplate, String valueTemplate, String rankPrefix) {
        this.placeholders = placeholders;
        this.rankManager = rankManager;
        this.headerTemplate = headerTemplate;
        this.footerTemplate = footerTemplate;
        this.valueTemplate = valueTemplate;
        this.rankPrefix = rankPrefix;
    }

    /** /stellariareload から呼ばれる想定。次のtickで全員分が更新される。 */
    public void updateSettings(String headerTemplate, String footerTemplate, String valueTemplate, String rankPrefix) {
        this.headerTemplate = headerTemplate;
        this.footerTemplate = footerTemplate;
        this.valueTemplate = valueTemplate;
        this.rankPrefix = rankPrefix;
    }

    public void tick() {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();

        for (Player target : online) {
            applyPlayerListName(target);
        }

        for (Player viewer : online) {
            viewer.setPlayerListHeaderFooter(
                    placeholders.resolve(headerTemplate, viewer),
                    placeholders.resolve(footerTemplate, viewer)
            );

            Objective objective = ensureValueObjective(BoardUtil.ensurePersonalBoard(viewer));
            for (Player target : online) {
                applyValue(objective, target);
            }
        }
    }

    /**
     * タブリストの名前欄に、ランクタグがあれば「rank-prefix + タグ」を色付きで前置きする。
     * ランク無しなら本来の表示名に戻す。名前部分は常に白固定（タグの色を継承させない）。
     */
    private void applyPlayerListName(Player target) {
        RankManager.RankInfo rank = rankManager.getRank(target);
        if (rank.tablistTag().isEmpty()) {
            target.playerListName(null);
            return;
        }
        Component tag = ColorUtil.component(rank.color() + rankPrefix + rank.tablistTag() + " ");
        Component name = Component.text(target.getName(), NamedTextColor.WHITE);
        target.playerListName(tag.append(name));
    }

    private Objective ensureValueObjective(Scoreboard board) {
        Objective objective = board.getObjective(VALUE_OBJECTIVE_NAME);
        if (objective == null) {
            objective = board.registerNewObjective(VALUE_OBJECTIVE_NAME, Criteria.DUMMY, "");
            objective.setDisplaySlot(DisplaySlot.PLAYER_LIST);
        }
        return objective;
    }

    private void applyValue(Objective objective, Player target) {
        // 値は見る側によらず同じ（targetの状態だけで決まる）
        String value = placeholders.resolve(valueTemplate, target);
        var score = objective.getScore(target.getName());
        score.setScore(0);
        score.numberFormat(NumberFormat.fixed(ColorUtil.component(value)));
    }
}
