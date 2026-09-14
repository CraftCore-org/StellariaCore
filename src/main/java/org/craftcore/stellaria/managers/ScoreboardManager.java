package org.craftcore.stellaria.managers;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.craftcore.stellaria.utils.BoardUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * サイドバーのスコアボードを描画するクラス。config.yml の {@code scoreboard.lines} を毎tick
 * 全部プレースホルダー解決して、前回描画した内容と変わっていたときだけ実際に再描画する
 * （毎tick無条件に描画すると、点滅して見えることがあるため）。
 * orelia-serverutil の {@code ScoreboardManager} を移植したもの（他プラグイン向けの
 * Provider拡張ポイントは持たない・configの内容をそのまま出す）。
 */
public class ScoreboardManager {

    private static final String OBJECTIVE_NAME = "stellaria_sb";
    private static final String TEAM_PREFIX = "st_sb_";
    private static final int MAX_LINES = ChatColor.values().length;

    private final PlaceholderManager placeholders;
    private volatile String titleTemplate;
    private volatile List<String> lineTemplates;
    private volatile boolean hideNumbers;
    private final Map<UUID, List<String>> lastRenderedLines = new ConcurrentHashMap<>();

    public ScoreboardManager(PlaceholderManager placeholders, String titleTemplate, List<String> lineTemplates, boolean hideNumbers) {
        this.placeholders = placeholders;
        this.titleTemplate = titleTemplate;
        this.lineTemplates = lineTemplates;
        this.hideNumbers = hideNumbers;
    }

    /** /stellariareload から呼ばれる想定。次のtickで全員分が再描画される。 */
    public void updateSettings(String titleTemplate, List<String> lineTemplates, boolean hideNumbers) {
        this.titleTemplate = titleTemplate;
        this.lineTemplates = lineTemplates;
        this.hideNumbers = hideNumbers;
        lastRenderedLines.clear();
    }

    public void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            render(player);
        }
    }

    /** プレイヤーが退出した時に呼ぶと、次に同じ名前で入ってきたプレイヤーが誤って前回の内容を
     *  引き継いだ扱いにならずに済む（差分比較用キャッシュの掃除）。 */
    public void forget(Player player) {
        lastRenderedLines.remove(player.getUniqueId());
    }

    private void render(Player player) {
        List<String> lines = new ArrayList<>();
        for (String template : lineTemplates) {
            lines.add(placeholders.resolve(template, player));
        }
        if (lines.equals(lastRenderedLines.get(player.getUniqueId()))) {
            return;
        }
        lastRenderedLines.put(player.getUniqueId(), lines);

        Scoreboard board = BoardUtil.ensurePersonalBoard(player);
        Objective existing = board.getObjective(OBJECTIVE_NAME);
        if (existing != null) {
            existing.unregister();
        }
        if (lines.isEmpty()) {
            return;
        }

        Objective objective = board.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, placeholders.resolve(titleTemplate, player));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        int score = lines.size();
        for (int i = 0; i < lines.size() && i < MAX_LINES; i++) {
            String entry = ChatColor.values()[i].toString();
            String teamName = TEAM_PREFIX + i;
            Team team = board.getTeam(teamName);
            if (team == null) {
                team = board.registerNewTeam(teamName);
                team.addEntry(entry);
            }
            team.setPrefix(lines.get(i));
            var scoreEntry = objective.getScore(entry);
            scoreEntry.setScore(score--);
            if (hideNumbers) {
                scoreEntry.numberFormat(NumberFormat.blank());
            }
        }
    }
}
