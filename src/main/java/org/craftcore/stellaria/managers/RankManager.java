package org.craftcore.stellaria.managers;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LuckPermsのグループ所属を読み取り、config.yml の rank.* で定義した表示情報
 * （色・タブリストタグ・表示名）に変換する。LuckPerms API に触れるのはこのクラスだけにして、
 * ChatListener/TabListManager はここから RankInfo を受け取るだけにする。
 * LuckPermsが無くても（未導入 or rank.enabled=false）例外を出さず、常にデフォルトを返す。
 */
public class RankManager {

    /**
     * 1グループ分の表示情報。tablistTag/displayNameが空文字ならランク無し扱い。
     * keyはluckperms-groupの名前（ランク無しは空文字）。ネームタグのTeam名など、
     * ランクを一意に識別する必要がある箇所で使う。
     */
    public record RankInfo(String key, String color, String tablistTag, String displayName) {
    }

    private record RankDefinition(String luckpermsGroup, RankInfo info) {
    }

    private final StellariaCore plugin;
    private LuckPerms luckPerms;
    private boolean enabled;
    private RankInfo defaultRank = new RankInfo("", "&%7", "", "");
    private List<RankDefinition> definitions = new ArrayList<>();

    public RankManager(StellariaCore plugin) {
        this.plugin = plugin;
        connectLuckPerms();
        reload();
    }

    /** LuckPermsプラグインが実際に導入されているか確認してから、ServicesManager経由でAPIを取得する。
     * 未導入なら luckPerms は null のままにし、以後 getRank は常にデフォルトを返す。 */
    private void connectLuckPerms() {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            plugin.getLogger().warning("LuckPerms が見つかりませんでした。ランク表示機能は無効化されます。");
            this.luckPerms = null;
            return;
        }
        this.luckPerms = Bukkit.getServicesManager().load(LuckPerms.class);
        if (this.luckPerms == null) {
            plugin.getLogger().warning("LuckPerms のAPI取得に失敗しました。ランク表示機能は無効化されます。");
        }
    }

    /** config.yml の rank.* を読み直す。/stellariareload から呼ばれる想定。 */
    public void reload() {
        ConfigManager config = plugin.getConfigManager();
        this.enabled = config.getBoolean("rank.enabled", true);
        this.defaultRank = new RankInfo("", config.getString("rank.default.color", "&%7"), "", "");

        List<RankDefinition> loaded = new ArrayList<>();
        for (Map<?, ?> entry : config.getMapList("rank.groups")) {
            Object group = entry.get("luckperms-group");
            if (!(group instanceof String groupName) || groupName.isBlank()) {
                plugin.getLogger().warning("rank.groups に luckperms-group の無いエントリがあるため、スキップします: " + entry);
                continue;
            }
            String color = valueOrDefault(entry.get("color"), "&%7");
            String tag = valueOrDefault(entry.get("tablist-tag"), "");
            String displayName = valueOrDefault(entry.get("display-name"), groupName);
            loaded.add(new RankDefinition(groupName, new RankInfo(groupName, color, tag, displayName)));
        }
        this.definitions = loaded;
    }

    private static String valueOrDefault(Object value, String def) {
        return value != null ? String.valueOf(value) : def;
    }

    /**
     * プレイヤーのランク情報を返す。LuckPerms未導入・機能無効・該当グループ無しは
     * すべて {@code rank.default.color} をcolorに持つ「ランク無し」のRankInfoを返す（例外を投げない）。
     */
    public RankInfo getRank(Player player) {
        if (!enabled || luckPerms == null) {
            return defaultRank;
        }

        User user = luckPerms.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return defaultRank;
        }

        QueryOptions options = luckPerms.getContextManager().getQueryOptions(player);
        Set<String> groupNames = user.getInheritedGroups(options).stream()
                .map(Group::getName)
                .collect(Collectors.toSet());

        for (RankDefinition definition : definitions) {
            if (groupNames.contains(definition.luckpermsGroup())) {
                return definition.info();
            }
        }
        return defaultRank;
    }
}
