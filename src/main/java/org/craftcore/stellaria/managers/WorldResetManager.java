package org.craftcore.stellaria.managers;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.WorldNameUtil;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.core.world.LoadedMultiverseWorld;
import org.mvplugins.multiverse.core.world.options.RegenWorldOptions;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * config.yml の world-reset.* に設定されたワールドを、一定周期（毎週/隔週/毎月）で
 * Multiverse-Core経由でランダムシード再生成する。AutoBroadcastManagerと同じ
 * Bukkit.getGlobalRegionScheduler().runAtFixedRate パターンで1秒毎にスケジュールをチェックし、
 * 60/30/10/5分前の告知、30分前の強制退避、リセット実行までを一本のtickで進行させる。
 *
 * 次回リセット時刻は状態を永続化せず、現在時刻とconfigのスケジュール設定から都度計算する
 * （HeadshopManager#shopDate() と同じ「基準時刻を跨ぐ日付」の考え方）。
 */
public class WorldResetManager {

    private static final long TICK_INTERVAL_TICKS = 20L; // 1秒毎
    private static final int[] ANNOUNCE_MINUTES = {60, 30, 10, 5};
    private static final int EVACUATION_MINUTES = 30;
    // 2024-01-01は月曜日。interval-weeksが2以上の隔週判定を、状態を持たずに固定周期で
    // 行うための基準日（この日からの経過週数がinterval-weeksの倍数になる週だけを対象にする）。
    private static final LocalDate WEEK_ANCHOR_MONDAY = LocalDate.of(2024, 1, 1);
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");
    // すてらりあはJP向け運用のため、JVMのタイムゾーン設定(ZoneId.systemDefault())に依存せず固定する
    private static final ZoneId SCHEDULE_ZONE = ZoneId.of("Asia/Tokyo");

    private final StellariaCore plugin;
    private ScheduledTask task;
    private final Set<Integer> announcedMinutes = new HashSet<>();
    private Instant currentCycleResetAt;
    private final Set<String> lockoutWorlds = new HashSet<>();
    private final Set<String> pendingLockCleanup = new HashSet<>();
    private final Set<String> scheduledLockCleanupRetries = new HashSet<>();
    // resetNow()とスケジュール実行(tick())の両方から辿り着くperformReset()が、
    // 同じワールドを二重に処理しないよう「今まさに退避〜再生成パイプライン中」を示す
    private final Set<String> resettingWorlds = new HashSet<>();
    // completeReset()でのhomes/warps削除に失敗したワールド。ロックは解除せず再試行する
    private final Set<String> pendingDataCleanup = new HashSet<>();
    private final Set<String> scheduledDataCleanupRetries = new HashSet<>();
    // 退避(evacuate/evacuateRemainingPlayers)に失敗したワールド。lockoutWorldsは解除せず維持し、
    // resetNow()/tick()どちらの経路で失敗しても同じ仕組みで自動再試行する。
    private final Set<String> pendingEvacuationRetry = new HashSet<>();
    private final Set<String> scheduledEvacuationRetries = new HashSet<>();

    public WorldResetManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** 既存タスクがあれば止めてから、config.yml の設定に従って（有効なら）再登録する。 */
    public void start() {
        if (task != null && !task.isCancelled()) {
            task.cancel();
            task = null;
        }
        if (!plugin.getConfigManager().getBoolean("world-reset.enabled", false)) {
            return;
        }
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin, scheduled -> tick(), TICK_INTERVAL_TICKS, TICK_INTERVAL_TICKS);
    }

    /**
     * /stellariareload から呼ばれる想定。告知済み状態をリセットしてからstart()する。
     * ロックアウト中の状態（lockoutWorlds）はリロードで消さない
     * ——設定再読み込みが安全対策そのものを解除してしまわないようにするため。
     */
    public void restart() {
        announcedMinutes.clear();
        currentCycleResetAt = null;
        start();
    }

    public boolean isResetTarget(String worldName) {
        return plugin.getConfigManager().getStringList("world-reset.worlds").contains(worldName);
    }

    public boolean isLockedOut(String worldName) {
        return lockoutWorlds.contains(worldName);
    }

    public String formattedNextResetTime() {
        return DISPLAY_FORMAT.withZone(SCHEDULE_ZONE).format(nextResetInstant());
    }

    /**
     * 管理者用の即時リセット。対象ワールドでなければfalseを返して何もしない。
     * 既に退避〜再生成パイプライン中（手動連打または定期リセットと重複）ならfalseを返す。
     * 通常スケジュールの4段階告知は行わず、即座に退避させてからリセットする（短縮版）。
     */
    public boolean resetNow(String worldName) {
        if (!isResetTarget(worldName)) {
            return false;
        }
        if (!resettingWorlds.add(worldName)) {
            return false;
        }
        lockoutWorlds.add(worldName);
        // evacuate()のteleportAsyncは即座には完了しないため、プレイヤーが残った状態で
        // Multiverseの再生成を始めないよう、退避完了を待ってからリセットする。
        evacuate(List.of(worldName)).thenAccept(evacuated -> {
            if (!evacuated) {
                plugin.getLogger().warning("ワールド '" + worldName + "' の退避に失敗したため、今回の再生成を見送りました。"
                        + " ロックは維持したまま自動的に退避を再試行します。");
                resettingWorlds.remove(worldName);
                pendingEvacuationRetry.add(worldName);
                scheduleEvacuationRetry(worldName);
                return;
            }
            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> performReset(List.of(worldName)));
        });
        return true;
    }

    private void tick() {
        boolean enabled = plugin.getConfigManager().getBoolean("world-reset.enabled", false);
        if (shouldRetryPendingLockCleanup(enabled, !pendingLockCleanup.isEmpty())) {
            retryPendingLockCleanup();
        }
        if (!pendingDataCleanup.isEmpty()) {
            retryPendingDataCleanup();
        }
        if (!pendingEvacuationRetry.isEmpty()) {
            retryPendingEvacuation();
        }
        if (!enabled) {
            return;
        }
        List<String> worlds = plugin.getConfigManager().getStringList("world-reset.worlds");
        if (worlds.isEmpty()) {
            return;
        }

        Instant resetAt = nextResetInstant();
        if (currentCycleResetAt == null || !currentCycleResetAt.equals(resetAt)) {
            // 新しいリセットサイクルに入った（前回分が完了した、または初回tick）ので告知状態を仕切り直す
            currentCycleResetAt = resetAt;
            announcedMinutes.clear();
        }

        long secondsUntilReset = Duration.between(Instant.now(), resetAt).getSeconds();

        // 起動直後や設定変更直後で残り時間が既に閾値を下回っている場合、経過済みの閾値も
        // このtickでまとめて発火する（例: 残り20分でプラグインが起動したら60分・30分告知＋
        // 強制退避を即座に行う）。取りこぼして退避を飛ばすより安全側に倒す。
        for (int minutes : ANNOUNCE_MINUTES) {
            if (secondsUntilReset <= minutes * 60L && announcedMinutes.add(minutes)) {
                announce(worlds, minutes);
                if (minutes == EVACUATION_MINUTES) {
                    evacuate(worlds);
                    lockoutWorlds.addAll(worlds);
                }
            }
        }

        if (secondsUntilReset <= 0) {
            // 手動 /worldreset now と重複しているワールドは既にresettingWorldsに入っているためスキップし、
            // 二重に退避/再生成パイプラインが走らないようにする（次サイクルで改めて対象になる）。
            List<String> claimedWorlds = worlds.stream().filter(resettingWorlds::add).toList();
            for (String worldName : worlds) {
                if (!claimedWorlds.contains(worldName)) {
                    plugin.getLogger().info("ワールド '" + worldName + "' は既に再生成処理中のため、今回の定期リセットはスキップしました。");
                }
            }
            if (!claimedWorlds.isEmpty()) {
                performReset(claimedWorlds);
            }
            currentCycleResetAt = null;
            announcedMinutes.clear();
        }
    }

    private void announce(List<String> worldNames, int minutes) {
        String displayNames = worldNames.stream()
                .map(worldName -> WorldNameUtil.displayName(plugin.getConfigManager(), worldName))
                .collect(java.util.stream.Collectors.joining(", "));
        String message = plugin.getConfigManager().getMessage("world-reset.announce", null);
        message = FormatUtil.replace(message, "%worlds%", displayNames);
        message = FormatUtil.replace(message, "%minutes%", String.valueOf(minutes));
        Bukkit.broadcast(ColorUtil.component(message));
    }

    private CompletableFuture<Boolean> evacuate(List<String> worldNames) {
        Location destination = evacuationDestination(worldNames);
        if (destination == null) {
            plugin.getLogger().warning("退避先ワールドを決定できないため、ワールド再生成を中止します。");
            return CompletableFuture.completedFuture(false);
        }
        List<CompletableFuture<Boolean>> teleports = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (worldNames.contains(player.getWorld().getName())) {
                teleports.add(player.teleportAsync(destination));
                player.sendMessage(plugin.getConfigManager().getMessage("world-reset.evacuated", player));
            }
        }
        return CompletableFuture.allOf(teleports.toArray(CompletableFuture[]::new))
                .handle((ignored, error) -> error == null && teleports.stream().allMatch(future -> future.getNow(false)));
    }

    private Location evacuationDestination(List<String> resetWorlds) {
        String worldName = plugin.getConfigManager().getString("world-reset.evacuate-to-world", "");
        World world = worldName.isEmpty() ? null : Bukkit.getWorld(worldName);
        if (world != null && resetWorlds.contains(world.getName())) {
            world = null;
        }
        if (world == null) {
            world = Bukkit.getWorlds().stream()
                    .filter(candidate -> !resetWorlds.contains(candidate.getName()))
                    .findFirst().orElse(null);
        }
        return world == null ? null : world.getSpawnLocation();
    }

    /**
     * 再生成対象ワールドに残っているプレイヤーを全員退避させてから、
     * Multiverseで再生成と新しいスポーンの保存が成功したワールドだけDB整理とロック解除を行う。
     * teleportAsync()は非同期のため、Multiverseのワールドアンロードがプレイヤー残留で
     * 失敗しないよう、全ワールド分の退避が完了してからグローバルリージョンスレッドに戻って再生成する。
     */
    /**
     * 呼び出し元(resetNow()またはtick())は、渡すworldNames全てを事前にresettingWorldsへ
     * 登録済みである前提。ここでは各ワールドの強制退避が実際に成功したかを確認し、
     * 失敗したワールドは再生成に進めずlockoutを維持したまま処理から外す。
     */
    private void performReset(List<String> worldNames) {
        Location destination = evacuationDestination(worldNames);
        Map<String, CompletableFuture<Boolean>> evacuations = new LinkedHashMap<>();
        for (String worldName : worldNames) {
            evacuations.put(worldName, evacuateRemainingPlayers(worldName, destination));
        }
        CompletableFuture.allOf(evacuations.values().toArray(CompletableFuture[]::new))
                .whenComplete((ignored, error) -> {
                    List<String> readyWorlds = new ArrayList<>();
                    for (Map.Entry<String, CompletableFuture<Boolean>> entry : evacuations.entrySet()) {
                        boolean evacuated = error == null && Boolean.TRUE.equals(entry.getValue().getNow(false));
                        if (evacuated) {
                            readyWorlds.add(entry.getKey());
                        } else {
                            plugin.getLogger().severe("ワールド '" + entry.getKey()
                                    + "' の強制退避に失敗したため、今回の再生成をスキップしました。"
                                    + " ロックは維持したまま自動的に退避を再試行します。");
                            resettingWorlds.remove(entry.getKey());
                            pendingEvacuationRetry.add(entry.getKey());
                            scheduleEvacuationRetry(entry.getKey());
                        }
                    }
                    if (!readyWorlds.isEmpty()) {
                        Bukkit.getGlobalRegionScheduler().execute(plugin, () -> regenerateWorlds(readyWorlds));
                    }
                });
    }

    /**
     * resetNow()の初回退避、またはperformReset()内の強制退避に失敗したワールドを、
     * lockoutWorldsを維持したまま自動的に再試行する。他の経路(手動/worldreset now等)で
     * 既に再生成パイプラインへ入っているワールドは、二重実行を避けるためここでは何もしない。
     */
    private void retryPendingEvacuation() {
        for (String worldName : new ArrayList<>(pendingEvacuationRetry)) {
            if (!resettingWorlds.add(worldName)) {
                continue;
            }
            pendingEvacuationRetry.remove(worldName);
            performReset(List.of(worldName));
        }
    }

    private void scheduleEvacuationRetry(String worldName) {
        if (!scheduledEvacuationRetries.add(worldName)) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            scheduledEvacuationRetries.remove(worldName);
            if (pendingEvacuationRetry.contains(worldName)) {
                retryPendingEvacuation();
            }
        }, TICK_INTERVAL_TICKS);
    }

    private void regenerateWorlds(List<String> worldNames) {
        for (String worldName : worldNames) {
            if (!regenerateWorld(worldName)) {
                // 再生成自体が失敗した場合、古いワールドはそのまま残っているのでロックを解除して
                // プレイヤーが戻れるようにする（ロックしたまま永久に入れなくなるのを防ぐ）。
                lockoutWorlds.remove(worldName);
                resettingWorlds.remove(worldName);
                continue;
            }

            boolean lockCleanupSucceeded = plugin.getContainerLockManager().removeWorld(worldName);
            if (!shouldCompleteReset(lockCleanupSucceeded)) {
                plugin.getLogger().warning("ワールド '" + worldName + "' のコンテナロックを削除できませんでした。");
                pendingLockCleanup.add(worldName);
                scheduleLockCleanupRetry(worldName);
                continue;
            }
            completeReset(worldName);
        }
    }

    private void retryPendingLockCleanup() {
        for (String worldName : new ArrayList<>(pendingLockCleanup)) {
            if (!plugin.getContainerLockManager().removeWorld(worldName)) {
                scheduleLockCleanupRetry(worldName);
                continue;
            }
            completeReset(worldName);
        }
    }

    private void scheduleLockCleanupRetry(String worldName) {
        if (!scheduledLockCleanupRetries.add(worldName)) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            scheduledLockCleanupRetries.remove(worldName);
            if (pendingLockCleanup.contains(worldName)) {
                retryPendingLockCleanup();
            }
        }, TICK_INTERVAL_TICKS);
    }

    private void completeReset(String worldName) {
        boolean dataCleanupSucceeded = DatabaseManager.transaction(conn -> {
            if (DatabaseManager.execute("DELETE FROM homes WHERE world = ?", worldName) < 0) {
                throw new IllegalStateException("homesの削除に失敗しました: " + worldName);
            }
            if (DatabaseManager.execute("DELETE FROM warps WHERE world = ?", worldName) < 0) {
                throw new IllegalStateException("warpsの削除に失敗しました: " + worldName);
            }
        });
        plugin.getRailStationManager().removeAllInWorld(worldName);
        if (!dataCleanupSucceeded) {
            plugin.getLogger().severe("ワールド '" + worldName
                    + "' のhome/warp削除に失敗したため、ロックを維持したまま再試行します。"
                    + " 古いhome/warpが残ったままプレイヤーが古い座標へテレポートできてしまうのを防ぐため。");
            pendingDataCleanup.add(worldName);
            scheduleDataCleanupRetry(worldName);
            return;
        }
        pendingLockCleanup.remove(worldName);
        scheduledLockCleanupRetries.remove(worldName);
        pendingDataCleanup.remove(worldName);
        scheduledDataCleanupRetries.remove(worldName);
        pendingEvacuationRetry.remove(worldName);
        scheduledEvacuationRetries.remove(worldName);
        lockoutWorlds.remove(worldName);
        resettingWorlds.remove(worldName);
        plugin.getLogger().info("ワールド '" + worldName + "' を自動リセットしました。");
    }

    private void retryPendingDataCleanup() {
        for (String worldName : new ArrayList<>(pendingDataCleanup)) {
            completeReset(worldName);
        }
    }

    private void scheduleDataCleanupRetry(String worldName) {
        if (!scheduledDataCleanupRetries.add(worldName)) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> {
            scheduledDataCleanupRetries.remove(worldName);
            if (pendingDataCleanup.contains(worldName)) {
                completeReset(worldName);
            }
        }, TICK_INTERVAL_TICKS);
    }

    static boolean shouldCompleteReset(boolean lockCleanupSucceeded) {
        return lockCleanupSucceeded;
    }

    /** 保留中のロック削除は、リセット機能を無効化しても完了するまで再試行する。 */
    static boolean shouldRetryPendingLockCleanup(boolean worldResetEnabled, boolean hasPendingCleanup) {
        return hasPendingCleanup;
    }

    /**
     * evacuate()のテレポート漏れやワールド間移動のタイミング差で退避しきれず残ったプレイヤーを、
     * 再生成をブロックさせないよう world-reset.evacuate-to-world へ強制的にテレポートする。
     * 退避先が決定できない場合はキックして再生成をブロックさせない。
     * 戻り値のFutureは、このワールドに残っていた全プレイヤーの退避処理が完了した時点で完了する。
     */
    private CompletableFuture<Boolean> evacuateRemainingPlayers(String worldName, Location destination) {
        String displayName = WorldNameUtil.displayName(plugin.getConfigManager(), worldName);
        List<CompletableFuture<Boolean>> teleports = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.getWorld().getName().equals(worldName)) {
                continue;
            }
            if (destination != null) {
                teleports.add(player.teleportAsync(destination));
                player.sendMessage(plugin.getConfigManager().getMessage("world-reset.evacuated", player));
                plugin.getLogger().warning("ワールド '" + worldName + "' に残っていたプレイヤー '"
                        + player.getName() + "' を再生成のため強制退避させました。");
            } else {
                // kick済みのプレイヤーはワールドから既にいなくなっているため、退避成功として扱う
                String message = FormatUtil.replace(
                        plugin.getConfigManager().getMessage("world-reset.kicked", player), "%world%", displayName);
                player.kick(ColorUtil.component(message));
                plugin.getLogger().warning("ワールド '" + worldName + "' に残っていたプレイヤー '"
                        + player.getName() + "' の退避先が決定できないため強制退出させました。");
            }
        }
        return CompletableFuture.allOf(teleports.toArray(CompletableFuture[]::new))
                .handle((ignored, error) -> error == null && teleports.stream().allMatch(future -> future.getNow(false)));
    }

    private boolean regenerateWorld(String worldName) {
        if (!Bukkit.getPluginManager().isPluginEnabled("Multiverse-Core")) {
            plugin.getLogger().warning("Multiverse-Coreが有効でないため、ワールド '" + worldName + "' の再生成をスキップしました。");
            return false;
        }

        MultiverseCoreApi api;
        try {
            api = MultiverseCoreApi.get();
        } catch (IllegalStateException e) {
            plugin.getLogger().warning("Multiverse-Core APIを取得できないため、ワールド '" + worldName + "' の再生成をスキップしました。");
            return false;
        }

        LoadedMultiverseWorld loadedWorld = api.getWorldManager().getLoadedWorld(worldName).getOrNull();
        if (loadedWorld == null) {
            plugin.getLogger().warning("ワールド '" + worldName + "' がMultiverse-Coreのロード済みワールドに見つからないため、再生成をスキップしました。");
            return false;
        }

        RegenWorldOptions options = RegenWorldOptions.world(loadedWorld)
                .randomSeed(true)
                .keepWorldConfig(true)
                .keepGameRule(true)
                .keepWorldBorder(true);
        var attempt = api.getWorldManager().regenWorld(options);
        if (attempt.isFailure()) {
            plugin.getLogger().severe("ワールド '" + worldName
                    + "' のMultiverse-Coreによる再生成に失敗したため、home/warpデータは保持しました。"
                    + " 理由: " + attempt.getFailureReason()
                    + ", 詳細: " + attempt.getFailureMessage().formatted());
            return false;
        }

        LoadedMultiverseWorld recreated = attempt.get();
        World recreatedBukkitWorld = recreated.getBukkitWorld().getOrNull();
        if (recreatedBukkitWorld == null) {
            plugin.getLogger().severe("ワールド '" + worldName + "' の再生成後のBukkitワールドを取得できないため、home/warpデータは保持しました。");
            return false;
        }
        var spawnResult = recreated.setSpawnLocation(recreatedBukkitWorld.getSpawnLocation());
        if (spawnResult.isFailure()) {
            plugin.getLogger().severe("ワールド '" + worldName + "' の新しいスポーン保存に失敗したため、home/warpデータは保持しました。");
            return false;
        }
        return true;
    }

    private Instant nextResetInstant() {
        LocalTime time = parseScheduleTime();
        ZoneId zone = SCHEDULE_ZONE;
        LocalDateTime now = LocalDateTime.now(zone);
        String type = plugin.getConfigManager().getString("world-reset.schedule.type", "weekly");

        LocalDateTime candidate = "monthly".equalsIgnoreCase(type)
                ? nextMonthlyCandidate(now, time)
                : nextWeeklyCandidate(now, time);

        return candidate.atZone(zone).toInstant();
    }

    private LocalTime parseScheduleTime() {
        String raw = plugin.getConfigManager().getString("world-reset.schedule.time", "12:00");
        try {
            return LocalTime.parse(raw);
        } catch (DateTimeParseException e) {
            plugin.getLogger().warning("world-reset.schedule.time の形式が不正です(HH:mm形式で指定してください): " + raw);
            return LocalTime.NOON;
        }
    }

    private LocalDateTime nextMonthlyCandidate(LocalDateTime now, LocalTime time) {
        int dayOfMonth = plugin.getConfigManager().getInt("world-reset.schedule.day-of-month", 1);
        LocalDateTime candidate = clampedMonthlyDateTime(now.getYear(), now.getMonthValue(), dayOfMonth, time);
        if (!candidate.isAfter(now)) {
            YearMonth nextMonth = YearMonth.from(now).plusMonths(1);
            candidate = clampedMonthlyDateTime(nextMonth.getYear(), nextMonth.getMonthValue(), dayOfMonth, time);
        }
        return candidate;
    }

    /** dayOfMonthがその月に存在しない日（例: 31日指定で2月）の場合、その月の末日に丸める。 */
    private LocalDateTime clampedMonthlyDateTime(int year, int month, int dayOfMonth, LocalTime time) {
        int clampedDay = Math.clamp(dayOfMonth, 1, YearMonth.of(year, month).lengthOfMonth());
        return LocalDateTime.of(year, month, clampedDay, time.getHour(), time.getMinute());
    }

    private LocalDateTime nextWeeklyCandidate(LocalDateTime now, LocalTime time) {
        DayOfWeek dayOfWeek = parseDayOfWeek();
        int intervalWeeks = Math.max(1, plugin.getConfigManager().getInt("world-reset.schedule.interval-weeks", 1));

        LocalDateTime candidate = now.with(TemporalAdjusters.nextOrSame(dayOfWeek)).with(time);
        if (!candidate.isAfter(now)) {
            candidate = candidate.plusWeeks(1);
        }
        while (weeksSinceAnchor(candidate.toLocalDate()) % intervalWeeks != 0) {
            candidate = candidate.plusWeeks(1);
        }
        return candidate;
    }

    private DayOfWeek parseDayOfWeek() {
        String raw = plugin.getConfigManager().getString("world-reset.schedule.day-of-week", "FRIDAY");
        try {
            return DayOfWeek.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("world-reset.schedule.day-of-week の形式が不正です: " + raw);
            return DayOfWeek.FRIDAY;
        }
    }

    private long weeksSinceAnchor(LocalDate date) {
        LocalDate mondayOfWeek = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return ChronoUnit.WEEKS.between(WEEK_ANCHOR_MONDAY, mondayOfWeek);
    }
}
