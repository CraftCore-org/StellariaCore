package org.craftcore.stellaria.managers;

import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.AdvancementDefinitions;
import org.craftcore.stellaria.utils.AdvancementDefinitions.Definition;
import org.craftcore.stellaria.utils.AdvancementDefinitions.TriggerType;
import org.craftcore.stellaria.utils.AdvancementJson;
import org.craftcore.stellaria.utils.AdvancementRules;
import org.craftcore.stellaria.utils.FormatUtil;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;

/**
 * 独自進捗の受け口。各機能は increment / addDistinct を呼ぶだけでよく、どの進捗がそのキーを見ているかは
 * ここで判断する。DB（AdvancementStore）が正で、バニラの進捗は表示係。オンラインのプレイヤーの状態は
 * ログイン時に同期で読み込んでキャッシュし、ログアウトで破棄する。
 */
public class AdvancementManager implements Listener {

    private final StellariaCore plugin;
    private final AdvancementRegistrar registrar;
    private final Map<UUID, AdvancementRules.State> cache = new ConcurrentHashMap<>();
    private boolean enabled;
    private AdvancementDefinitions.Parsed parsed = new AdvancementDefinitions.Parsed(Map.of(), List.of());
    private Map<String, List<Definition>> byKey = Map.of();
    private List<Definition> dependents = List.of();
    private List<Definition> statDefinitions = List.of();

    public AdvancementManager(StellariaCore plugin) {
        this.plugin = plugin;
        this.registrar = new AdvancementRegistrar(plugin);
    }

    /** 起動時に 1 回呼ぶ。advancements.yml の変更は再起動で反映する（登録し直しは重いため）。 */
    public void enable() {
        enabled = plugin.getConfigManager().getBoolean("advancements.enabled", true, true);
        if (!enabled) {
            return;
        }
        parsed = AdvancementDefinitions.parse(plugin.getConfigManager().get("advancements.yml").get(),
                AdvancementManager::isItemIcon, message -> plugin.getLogger().warning(message));
        byKey = AdvancementRules.indexByKey(parsed.definitions());
        dependents = AdvancementRules.dependents(parsed.definitions());
        statDefinitions = AdvancementRules.ofType(parsed.definitions(), TriggerType.STAT);
        registrar.register(parsed, this::announces);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            onJoin(online);
        }
    }

    /** /minecraft:reload などでデータパックが読み直されると独自進捗が消えるため、登録し直して表示を合わせる。 */
    @EventHandler
    public void onResourcesReloaded(ServerResourcesReloadedEvent event) {
        if (!enabled) {
            return;
        }
        registrar.register(parsed, this::announces);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (cache.containsKey(online.getUniqueId())) {
                syncVanilla(online);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public AdvancementDefinitions.Parsed getParsed() {
        return parsed;
    }

    private static boolean isItemIcon(String name) {
        Material material = Material.matchMaterial(name);
        return material != null && material.isItem() && !material.isAir();
    }

    private boolean announces(AdvancementDefinitions.Difficulty difficulty) {
        boolean fallback = difficulty == AdvancementDefinitions.Difficulty.HARD
                || difficulty == AdvancementDefinitions.Difficulty.CHALLENGE;
        return plugin.getConfigManager().getBoolean(
                "advancements.difficulties." + difficulty.configKey() + ".announce", fallback, true);
    }

    public long rewardFor(Definition def) {
        if (def.reward() != null) {
            return def.reward();
        }
        long fallback = switch (def.difficulty()) {
            case EASY -> 50L;
            case NORMAL -> 300L;
            case HARD -> 1500L;
            case CHALLENGE -> 5000L;
        };
        return plugin.getConfigManager().getInt(
                "advancements.difficulties." + def.difficulty().configKey() + ".reward", (int) fallback, true);
    }

    // ------------------------------------------------------------------
    // ログイン・ログアウト
    // ------------------------------------------------------------------

    public void onJoin(Player player) {
        if (!enabled) {
            return;
        }
        AdvancementStore.Loaded loaded = AdvancementStore.load(player.getUniqueId());
        cache.put(player.getUniqueId(), new AdvancementRules.State(
                new HashMap<>(loaded.counters()), new HashMap<>(loaded.distinctCounts()), new HashSet<>(loaded.completed())));
        syncVanilla(player);
        increment(player, "join.count", 1);
        checkStats(player);
        evaluate(player, parsed.definitions(), key -> 0L);
    }

    public void onQuit(Player player) {
        cache.remove(player.getUniqueId());
    }

    /** DB を正として、バニラ側の達成状況を合わせる。ここでは報酬を払わない。 */
    private void syncVanilla(Player player) {
        for (AdvancementDefinitions.Tab tab : parsed.tabs().values()) {
            setVanilla(player, AdvancementJson.rootPath(tab.id()), true);
        }
        Set<String> completed = cache.get(player.getUniqueId()).completed();
        for (Definition def : parsed.definitions()) {
            setVanilla(player, AdvancementJson.path(def.tab(), def.id()), completed.contains(def.id()));
        }
    }

    private void setVanilla(Player player, String path, boolean done) {
        Advancement advancement = plugin.getServer().getAdvancement(AdvancementRegistrar.key(path));
        if (advancement == null) {
            return;
        }
        AdvancementProgress progress = player.getAdvancementProgress(advancement);
        if (done && !progress.isDone()) {
            progress.awardCriteria(AdvancementJson.CRITERION);
        } else if (!done && progress.isDone()) {
            progress.revokeCriteria(AdvancementJson.CRITERION);
        }
    }

    // ------------------------------------------------------------------
    // カウンターの受け口
    // ------------------------------------------------------------------

    public void increment(Player player, String key, long amount) {
        addToCounter(player.getUniqueId(), key, amount);
    }

    /**
     * オフラインのプレイヤーにも使えるカウンターの加算（ショップの売上がオフラインのオーナーに入る場合など）。
     * DB には独自進捗が無効でも必ず加算する（稼いだお金ランキングのように、進捗以外も同じカウンターを読むため）。
     * オンラインなら、メインスレッドでキャッシュに反映して判定する。
     */
    public void addToCounter(UUID uuid, String key, long amount) {
        if (amount <= 0) {
            return;
        }
        AdvancementStore.addCounterAsync(uuid, key, amount);
        if (!enabled) {
            return;
        }
        Runnable apply = () -> {
            Player player = plugin.getServer().getPlayer(uuid);
            AdvancementRules.State state = cache.get(uuid);
            if (player == null || state == null) {
                return;
            }
            state.counters().merge(key, amount, Long::sum);
            evaluate(player, byKey.getOrDefault(key, List.of()), k -> 0L);
        };
        if (plugin.getServer().isPrimaryThread()) {
            apply.run();
        } else {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, apply);
        }
    }

    public void addDistinct(Player player, String key, String member) {
        if (!enabled || !AdvancementStore.addMember(player.getUniqueId(), key, member)) {
            return;
        }
        AdvancementRules.State state = cache.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        state.distinctCounts().merge(key, 1L, Long::sum);
        evaluate(player, byKey.getOrDefault(key, List.of()), k -> 0L);
    }

    /** stat 型の進捗を、その場の統計で判定する（ログイン時）。 */
    public void checkStats(Player player) {
        if (!enabled || statDefinitions.isEmpty()) {
            return;
        }
        Map<String, Long> values = new HashMap<>();
        for (Definition def : statDefinitions) {
            values.computeIfAbsent(def.trigger().key(), key -> statValue(player, key));
        }
        evaluate(player, statDefinitions, key -> values.getOrDefault(key, 0L));
    }

    /** 統計スナップショットの書き出しで読んだ値を使って stat 型を判定する（重い統計を読み直さないため）。 */
    public void onStatsRead(Player player, Map<String, Long> values) {
        if (!enabled || statDefinitions.isEmpty() || !cache.containsKey(player.getUniqueId())) {
            return;
        }
        long playtime = plugin.getPlaytimeManager().getPlaytimeSeconds(player.getUniqueId());
        evaluate(player, statDefinitions, key -> key.equals("playtime") ? playtime : values.getOrDefault(key, 0L));
    }

    private long statValue(Player player, String key) {
        return key.equals("playtime")
                ? plugin.getPlaytimeManager().getPlaytimeSeconds(player.getUniqueId())
                : plugin.getStatSnapshotManager().readLive(player, key);
    }

    // ------------------------------------------------------------------
    // 判定と達成
    // ------------------------------------------------------------------

    private void evaluate(Player player, List<Definition> candidates, ToLongFunction<String> stat) {
        AdvancementRules.State state = cache.get(player.getUniqueId());
        if (state == null) {
            return;
        }
        for (Definition def : candidates) {
            if (!state.completed().contains(def.id())
                    && AdvancementRules.isMet(def, state, stat, parsed.definitions())) {
                complete(player, def);
            }
        }
    }

    /** 達成を記録し、バニラに反映し、報酬を払い、依存する進捗を判定する。二重処理は DB の結果で防ぐ。 */
    private void complete(Player player, Definition def) {
        UUID uuid = player.getUniqueId();
        if (!AdvancementStore.recordCompletion(uuid, def.id(), System.currentTimeMillis())) {
            return;
        }
        AdvancementRules.State state = cache.get(uuid);
        if (state != null) {
            state.completed().add(def.id());
        }
        setVanilla(player, AdvancementJson.path(def.tab(), def.id()), true);
        long reward = rewardFor(def);
        if (AdvancementStore.claimReward(uuid, def.id(), reward) && reward > 0) {
            EconomyResponse response = plugin.getEconomyManager().depositPlayer(player, reward);
            if (response.transactionSuccess()) {
                plugin.getEconomyManager().recordEarning(uuid, reward);
                player.sendMessage(FormatUtil.replace(FormatUtil.replace(
                        plugin.getConfigManager().getMessage("advancements.reward", player),
                        "%title%", def.title()),
                        "%amount%", plugin.getEconomyManager().formatExact(reward)));
            } else {
                AdvancementStore.unclaimReward(uuid, def.id());
                plugin.getLogger().warning("進捗 " + def.id() + " の報酬を " + player.getName()
                        + " に入金できませんでした: " + response.errorMessage);
            }
        }
        evaluate(player, dependents, key -> 0L);
    }

    // ------------------------------------------------------------------
    // GUI・管理者用
    // ------------------------------------------------------------------

    public boolean isCompleted(Player player, String id) {
        AdvancementRules.State state = cache.get(player.getUniqueId());
        return state != null && state.completed().contains(id);
    }

    public long progress(Player player, Definition def) {
        AdvancementRules.State state = cache.get(player.getUniqueId());
        if (state == null) {
            return 0L;
        }
        return AdvancementRules.progress(def, state, key -> statValue(player, key), parsed.definitions());
    }

    /** 管理者用。通常の達成処理（報酬を含む）を行う。すでに達成済みなら false。 */
    public boolean grant(Player player, String id) {
        Definition def = parsed.find(id).orElse(null);
        if (def == null || isCompleted(player, id)) {
            return false;
        }
        complete(player, def);
        return true;
    }

    /** 管理者用。未達成に戻す。支払済みの報酬は回収せず、再達成しても二重には払わない。 */
    public boolean revoke(Player player, String id) {
        Definition def = parsed.find(id).orElse(null);
        if (def == null || !AdvancementStore.revoke(player.getUniqueId(), id)) {
            return false;
        }
        AdvancementRules.State state = cache.get(player.getUniqueId());
        if (state != null) {
            state.completed().remove(id);
        }
        setVanilla(player, AdvancementJson.path(def.tab(), def.id()), false);
        return true;
    }
}
