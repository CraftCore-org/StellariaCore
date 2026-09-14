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
 * 名前の下（{@link DisplaySlot#BELOW_NAME}）に体力（ハート数換算）を表示するクラス。
 * orelia-serverutil の {@code BelownameManager} を移植したもの（他プラグイン向けの
 * Provider拡張ポイントは持たない・体力固定表示）。
 */
public class BelownameManager {

    private static final String OBJECTIVE_NAME = "stellaria_bn";
    private static final String VALUE_TEMPLATE = "%health%";

    private final PlaceholderManager placeholders;
    private volatile String titleTemplate;

    public BelownameManager(PlaceholderManager placeholders, String titleTemplate) {
        this.placeholders = placeholders;
        this.titleTemplate = titleTemplate;
    }

    /** /stellariareload から呼ばれる想定。次のtickで反映される。 */
    public void updateSettings(String titleTemplate) {
        this.titleTemplate = titleTemplate;
    }

    public void tick() {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();

        for (Player viewer : online) {
            Scoreboard board = BoardUtil.ensurePersonalBoard(viewer);
            Objective objective = board.getObjective(OBJECTIVE_NAME);
            if (objective == null) {
                objective = board.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY,
                        ColorUtil.component(placeholders.resolve(titleTemplate, viewer)));
                objective.setDisplaySlot(DisplaySlot.BELOW_NAME);
            } else {
                objective.displayName(ColorUtil.component(placeholders.resolve(titleTemplate, viewer)));
            }
            for (Player target : online) {
                String value = placeholders.resolve(VALUE_TEMPLATE, target);
                var score = objective.getScore(target.getName());
                score.setScore(0);
                score.numberFormat(NumberFormat.fixed(ColorUtil.component(value)));
            }
        }
    }
}
