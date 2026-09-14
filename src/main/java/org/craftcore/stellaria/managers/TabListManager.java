package org.craftcore.stellaria.managers;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
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
    private volatile String headerTemplate;
    private volatile String footerTemplate;
    private volatile String valueTemplate;

    public TabListManager(PlaceholderManager placeholders, String headerTemplate, String footerTemplate, String valueTemplate) {
        this.placeholders = placeholders;
        this.headerTemplate = headerTemplate;
        this.footerTemplate = footerTemplate;
        this.valueTemplate = valueTemplate;
    }

    /** /stellariareload から呼ばれる想定。次のtickで全員分が更新される。 */
    public void updateSettings(String headerTemplate, String footerTemplate, String valueTemplate) {
        this.headerTemplate = headerTemplate;
        this.footerTemplate = footerTemplate;
        this.valueTemplate = valueTemplate;
    }

    public void tick() {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();

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
