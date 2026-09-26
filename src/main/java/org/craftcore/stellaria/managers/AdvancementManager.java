package org.craftcore.stellaria.managers;

import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.AdvancementDefinitions;
import org.craftcore.stellaria.utils.AdvancementDefinitions.Definition;
import org.craftcore.stellaria.utils.AdvancementDefinitions.TriggerType;
import org.craftcore.stellaria.utils.AdvancementJson;
import org.craftcore.stellaria.utils.AdvancementRules;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.rail.RailLineManager;
import org.craftcore.stellaria.utils.LoginDays;
import org.craftcore.stellaria.utils.RailRideRecord;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

    private static final ZoneId JAPAN = ZoneId.of("Asia/Tokyo");

    private final StellariaCore plugin;
    private final AdvancementRegistrar registrar;
    private final Map<UUID, AdvancementRules.State> cache = new ConcurrentHashMap<>();
    /** プレイヤーごとの [日本時間の epochDay, その日のチャット回数]。 */
    private final Map<UUID, long[]> chatToday = new ConcurrentHashMap<>();
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
        // 日付をまたいでログインし続けている人の当日分を記録する（連続ログインが途切れないように）。
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (cache.containsKey(online.getUniqueId())) {
                    recordLoginDay(online.getUniqueId());
                }
            }
        }, 12_000L, 12_000L);
    }

    /** ログイン時にわかる事実（時刻・初ログインからの日数・ログイン日）を記録する。 */
    private void recordJoinFacts(Player player) {
        UUID uuid = player.getUniqueId();
        if (LocalTime.now(JAPAN).getHour() == 3) {
            event(uuid, "join.3am");
        }
        long firstPlayed = player.getFirstPlayed();
        if (firstPlayed > 0) {
            long days = (System.currentTimeMillis() - firstPlayed) / 86_400_000L;
            if (days >= 7) event(uuid, "account.age7");
            if (days >= 30) event(uuid, "account.age30");
            if (days >= 100) event(uuid, "account.age100");
        }
        recordLoginDay(uuid);
    }

    private void recordLoginDay(UUID uuid) {
        LocalDate today = LoginDays.today();
        LoginDaysStore.record(uuid, today);
        Set<LocalDate> days = LoginDaysStore.since(uuid, today.minusDays(30));
        if (LoginDays.streak(days, today) >= 7) event(uuid, "login.streak7");
        if (LoginDays.countWithin(days, today, 30) >= 20) event(uuid, "login.active20of30");
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
        recordJoinFacts(player);
        checkStats(player);
        evaluate(player, parsed.definitions(), key -> 0L);
    }

    public void onQuit(Player player) {
        cache.remove(player.getUniqueId());
        chatToday.remove(player.getUniqueId());
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
        onMain(() -> {
            Player player = plugin.getServer().getPlayer(uuid);
            AdvancementRules.State state = cache.get(uuid);
            if (player == null || state == null) {
                return;
            }
            state.counters().merge(key, amount, Long::sum);
            evaluate(player, byKey.getOrDefault(key, List.of()), k -> 0L);
        });
    }

    /** 1 回起きたことを記録する（event 型の進捗用）。オフラインの相手・非同期スレッドからでもよい。 */
    public void event(UUID uuid, String key) {
        addToCounter(uuid, key, 1);
    }

    public void addDistinct(Player player, String key, String member) {
        addDistinct(player.getUniqueId(), key, member);
    }

    /**
     * distinct 型の値を記録する。オフラインの相手・非同期スレッドからでもよい。
     * DB には独自進捗が無効でも記録し、新しい値だったときだけ、オンラインならメインスレッドでキャッシュに反映して判定する。
     */
    public void addDistinct(UUID uuid, String key, String member) {
        if (!AdvancementStore.addMember(uuid, key, member) || !enabled) {
            return;
        }
        onMain(() -> {
            Player player = plugin.getServer().getPlayer(uuid);
            AdvancementRules.State state = cache.get(uuid);
            if (player == null || state == null) {
                return;
            }
            state.distinctCounts().merge(key, 1L, Long::sum);
            evaluate(player, byKey.getOrDefault(key, List.of()), k -> 0L);
        });
    }

    private void onMain(Runnable task) {
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
        } else {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, task);
        }
    }

    /** prefix.<日本時間の日付> に同期で加算して、その日の合計を返す。 */
    public long addDaily(UUID uuid, String prefix, long amount) {
        return AdvancementStore.addCounterAndGet(uuid, prefix + "." + LoginDays.today(), amount);
    }

    /** AsyncChatEvent から呼ばれる。日ごとの回数はメモリだけで数える（再起動でその日の回数はリセット）。 */
    public void onChat(Player player) {
        UUID uuid = player.getUniqueId();
        addToCounter(uuid, "chat.messages", 1);
        long today = LoginDays.today().toEpochDay();
        long[] entry = chatToday.compute(uuid, (id, old) ->
                old == null || old[0] != today ? new long[]{today, 1} : new long[]{today, old[1] + 1});
        if (entry[1] == 100) {
            event(uuid, "chat.day100");
        }
    }

    /** 時間投票・天気投票を始めたとき。 */
    public void onVoteStarted(Player player, boolean weather) {
        increment(player, "vote.started", 1);
        increment(player, weather ? "vote.weather_started" : "vote.time_started", 1);
        increment(player, "vote.participations", 1);
    }

    /** 投票で賛成・反対したとき。 */
    public void onVoteCast(Player player, boolean yes) {
        increment(player, yes ? "vote.yes" : "vote.no", 1);
        increment(player, "vote.participations", 1);
    }

    private static final Set<String> TOUR_COMMANDS = Set.of("home", "warp", "tpa", "shop", "land");

    /** 「すてらりあへようこそ」用に、/home・/warp・/tpa・/shop・/land を（別名も含めて）使ったことを記録する。 */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onCommandUsed(PlayerCommandPreprocessEvent event) {
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        label = label.substring(label.indexOf(':') + 1);
        PluginCommand command = plugin.getServer().getPluginCommand(label);
        if (command != null && command.getPlugin() == plugin && TOUR_COMMANDS.contains(command.getName())) {
            addDistinct(event.getPlayer(), "tour.commands", command.getName());
        }
    }

    /** 高速鉄道で駅に到着したとき（到着以外で終わった乗車は記録しない）。 */
    public void onRailArrival(Player player, String departure, String arrival, RailLineManager.RailLine line, long blocks) {
        increment(player, "rail.rides", 1);
        addDistinct(player, "rail.stations", arrival.toLowerCase(Locale.ROOT));
        if (line != null) {
            addDistinct(player, "rail.lines", line.name().toLowerCase(Locale.ROOT));
            if (RailRideRecord.isFullLine(line.stationNamesInOrder(), line.oneWay(), departure, arrival)) {
                increment(player, "rail.full_line", 1);
            }
        }
        increment(player, "rail.distance", blocks);
        if (blocks >= 5_000) increment(player, "rail.ride5k", 1);
        if (blocks >= 20_000) increment(player, "rail.ride20k", 1);
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
