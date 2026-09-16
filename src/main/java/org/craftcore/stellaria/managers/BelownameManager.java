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
 *
 * <p>{@code titleTemplate}（objectiveの{@code displayName}）は視聴者1人につき1つしか持てず、
 * 画面内の全プレイヤーの名札に共通で表示される。プレイヤーごとに変わる値（AFK状態など）は
 * 必ず{@code valueTemplate}（{@link Objective#getScore(String)}の{@link NumberFormat}、
 * targetごとに個別設定可能）側に入れること。titleTemplateに{@code %afk%}のような対象依存の
 * プレースホルダーを入れると、「視聴者自身の状態が全員の名札に表示される」というバグになる
 * （過去に実際に発生した不具合）。
 */
public class BelownameManager {

    private static final String OBJECTIVE_NAME = "stellaria_bn";

    private final PlaceholderManager placeholders;
    private volatile String titleTemplate;
    private volatile String valueTemplate;

    public BelownameManager(PlaceholderManager placeholders, String titleTemplate, String valueTemplate) {
        this.placeholders = placeholders;
        this.titleTemplate = titleTemplate;
        this.valueTemplate = valueTemplate;
    }

    /** /stellariareload から呼ばれる想定。次のtickで反映される。 */
    public void updateSettings(String titleTemplate, String valueTemplate) {
        this.titleTemplate = titleTemplate;
        this.valueTemplate = valueTemplate;
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
                String value = placeholders.resolve(valueTemplate, target, viewer);
                var score = objective.getScore(target.getName());
                score.setScore(0);
                score.numberFormat(NumberFormat.fixed(ColorUtil.component(value)));
            }
        }
    }
}
