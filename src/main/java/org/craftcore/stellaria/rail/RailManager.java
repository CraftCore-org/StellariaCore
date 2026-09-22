package org.craftcore.stellaria.rail;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.WorldBlacklistUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 高速鉄道セッションの状態管理と、毎tick(VehicleMoveEventのたび)の速度更新・チャンク先読みを行う。
 * 「対象トロッコかどうか」は sessions.containsKey() だけで判定する
 * （PersistentDataContainerの stellaria:rail_mode タグは外部から見て分かるようにする付随マーカーで、
 * 判定ロジックの真実源はこのMapの方）。
 */
public class RailManager {

    public enum EndReason { ARRIVED, OFF_RAIL, DESTROYED, WORLD_DISABLED, ERROR, COLLISION, DISMOUNTED }

    /** /rail depart のバリデーション用。実際の発車判定はstartSessionが（このメソッドと独立に）改めて行う。 */
    public enum DepartureCheck { OK, ALREADY_RAIL_MODE, NOT_ON_RAIL, NO_ROUTE, WRONG_DIRECTION }

    private final StellariaCore plugin;
    private final RailConfig config;
    private final RailStationManager stationManager;
    private final RailLineManager lineManager;
    private final NamespacedKey railModeKey;
    private final Map<UUID, RailSession> sessions = new ConcurrentHashMap<>();
    /** チャンク先読みチケットの参照カウント。addPluginChunkTicket/removePluginChunkTicketは
     *  (チャンク, plugin)単位でしか管理できず、セッション単位の区別が無い。複数のトロッコが
     *  同じチャンクを先読みしている時、片方が単純にremoveすると、まだそのチャンクを必要としている
     *  もう片方の先読みまで巻き添えで消えてしまうため、ここで参照カウントして最後の1件まで実削除しない。 */
    private final Map<ChunkKey, Integer> chunkTicketRefCounts = new ConcurrentHashMap<>();

    private record ChunkKey(World world, int x, int z) {
    }

    public RailManager(StellariaCore plugin, RailConfig config, RailStationManager stationManager, RailLineManager lineManager) {
        this.plugin = plugin;
        this.config = config;
        this.stationManager = stationManager;
        this.lineManager = lineManager;
        this.railModeKey = new NamespacedKey(plugin, "rail_mode");
    }

    public boolean isRailMode(UUID minecartId) {
        return sessions.containsKey(minecartId);
    }

    /** /rail depart から呼ぶ。理由を特定したエラーメッセージを出すための事前チェック。 */
    public DepartureCheck checkDeparture(Minecart cart, RailStationManager.Station target) {
        if (sessions.containsKey(cart.getUniqueId())) {
            return DepartureCheck.ALREADY_RAIL_MODE;
        }
        Block block = cart.getLocation().getBlock();
        Rail.Shape shape = RailSpeedController.shapeAt(block);
        if (shape == null || RailSpeedController.isCurve(shape)) {
            return DepartureCheck.NOT_ON_RAIL;
        }
        if (findDepartureDirection(block, shape, target) != null) {
            return DepartureCheck.OK;
        }
        // findDepartureDirectionは「到達可能かつ一方通行に従う」方向しか返さないため、nullだけでは
        // 経路が無いのか一方通行で弾かれたのか分からない。メッセージを分けるため、一方通行を無視した
        // 純粋な到達可能性を別途調べる。
        return isReachableIgnoringOneWay(block, shape, target) ? DepartureCheck.WRONG_DIRECTION : DepartureCheck.NO_ROUTE;
    }

    /** GUI表示用の1件。approxDistanceBlocksは経路探索で辿った実測ブロック数（直線距離ではない）。 */
    public record ReachableStation(RailStationManager.Station station, BlockFace direction, int approxDistanceBlocks) {
    }

    /**
     * cartの現在位置から実際にレールをたどって発車できる駅の一覧を返す（RailDepartGui専用）。
     * originBlockの2つの端点方向をそれぞれ1回だけ歩き、その1回の経路上で見つかった駅をまとめて
     * 拾う（旧実装は「登録駅の数 × 経路探索」を毎回フルで行っていたため、駅・路線が増えるほど
     * GUIを開くたびに重くなっていた）。コマンドのタブ補完（毎キー入力で呼ばれる）では使わず、
     * GUIを開く瞬間だけ呼ぶこと。カーブ上や未接続なら空リストを返す。
     */
    public List<ReachableStation> findReachableStations(Minecart cart) {
        Block block = cart.getLocation().getBlock();
        Rail.Shape shape = RailSpeedController.shapeAt(block);
        if (shape == null || RailSpeedController.isCurve(shape)) {
            return List.of();
        }
        BlockFace[] endpoints = RailSpeedController.endpointsOf(shape);
        if (endpoints == null) {
            return List.of();
        }
        List<ReachableStation> reachable = new ArrayList<>();
        for (BlockFace candidate : endpoints) {
            walkForReachableStations(block, candidate, reachable);
        }
        return reachable;
    }

    /**
     * originBlockからdirectionへレールを1回だけ歩き、到達できる駅（一方通行の逆走なし）を
     * resultに積む。isDirectionAllowedと同じ「一方通行路線の駅の並び順が単調増加か」ロジックを
     * 使うが、駅ごとに歩き直さず、この1回の経路上でまとめて判定する点が異なる。
     */
    private void walkForReachableStations(Block originBlock, BlockFace direction, List<ReachableStation> result) {
        List<RailLineManager.RailLine> oneWayLines = lineManager.listAll().stream()
                .filter(RailLineManager.RailLine::oneWay)
                .toList();
        double arrivalRadiusSq = config.getStationArrivalRadius() * config.getStationArrivalRadius();
        Map<String, Integer> lastSequenceByLine = new HashMap<>();
        if (!oneWayLines.isEmpty()) {
            collectNearestSequencePerLine(originBlock, direction.getOppositeFace(), oneWayLines, arrivalRadiusSq, lastSequenceByLine);
        }

        Block scanBlock = originBlock;
        BlockFace scanDirection = direction;
        int maxBlocks = config.getPathSearchMaxBlocks();
        for (int i = 0; i < maxBlocks; i++) {
            Location blockCenter = scanBlock.getLocation().add(0.5, 0.5, 0.5);
            for (RailLineManager.RailLine line : oneWayLines) {
                RailStationManager.Station lineStation = stationOnLineAt(line, blockCenter, arrivalRadiusSq);
                if (lineStation == null) {
                    continue;
                }
                int sequence = line.sequenceOf(lineStation.name());
                Integer previous = lastSequenceByLine.get(line.name());
                if (previous != null && sequence < previous) {
                    return; // ここから先は一方通行の逆走になるため、この方向の探索を打ち切る
                }
                lastSequenceByLine.put(line.name(), sequence);
            }
            for (RailStationManager.Station station : stationManager.listAll()) {
                if (containsStation(result, station)) {
                    continue;
                }
                Location stationLocation = station.resolveLocation();
                if (stationLocation != null && stationLocation.getWorld().equals(blockCenter.getWorld())
                        && stationLocation.distanceSquared(blockCenter) <= arrivalRadiusSq) {
                    result.add(new ReachableStation(station, direction, i));
                }
            }
            RailStep step = stepAlongRail(scanBlock, scanDirection);
            if (step == null) {
                return;
            }
            scanBlock = step.block();
            scanDirection = step.direction();
        }
    }

    private static boolean containsStation(List<ReachableStation> result, RailStationManager.Station station) {
        for (ReachableStation entry : result) {
            if (entry.station().name().equalsIgnoreCase(station.name())) {
                return true;
            }
        }
        return false;
    }

    /**
     * targetへ向けて高速モードを開始する。cartが現在乗っている直線レールから、targetまで実際に
     * たどり着ける方向（左右どちらか）を経路探索で決める。カーブ上からの発車、レールが無い、
     * targetへの経路が見つからない、一方通行路線を逆走する場合はfalseを返す
     * （呼び出し側がcheckDepartureで理由を特定してエラーメッセージを出す想定）。
     */
    public boolean startSession(Minecart cart, RailStationManager.Station target) {
        if (sessions.containsKey(cart.getUniqueId())) {
            return false;
        }
        Block block = cart.getLocation().getBlock();
        Rail.Shape shape = RailSpeedController.shapeAt(block);
        if (shape == null) {
            return false;
        }
        if (RailSpeedController.isCurve(shape)) {
            // カーブ上での発車はnextDirectionの「入ってきた方向」計算と噛み合わず、
            // 1tick目で即脱線扱いになるため許可しない（駅は直線区間に置く運用とする）
            return false;
        }
        // findDepartureDirectionは「到達可能かつ一方通行に従う」方向しか返さないため、
        // 追加のisDirectionAllowedチェックは不要（環状線で片方の候補が逆走になるケースの対応）。
        BlockFace direction = findDepartureDirection(block, shape, target);
        if (direction == null) {
            return false;
        }

        double originalMaxSpeed = cart.getMaxSpeed();
        RailSession session = new RailSession(cart.getUniqueId(), target.name(), direction, config.getMinSpeedBps(), originalMaxSpeed);
        // 発車地点が既にどこかの駅の到着範囲内にある場合（＝その駅から発車した場合）、
        // 通過/到着タイトルをここで先に「発行済み」にしておく。そうしないと発車直後の最初の数tickで
        // notifyStationPassageがこの「発車元の駅」を通過扱いで検知し、出発した瞬間にタイトルが
        // 出てしまう。
        markNearbyStationsAsNotified(cart, session);
        sessions.put(cart.getUniqueId(), session);
        cart.getPersistentDataContainer().set(railModeKey, PersistentDataType.BOOLEAN, true);
        cart.setMaxSpeed(config.getMaxVelocityClampBpt());

        // 静止したトロッコにはVehicleMoveEventが一切発火しないため、tickMovementの物理演算ループが
        // 永遠に始動しない（ウォッチドッグが「レールに乗っていない」と誤判定して即解除する原因になる）。
        // ここで最低速度ぶんの初速を直接与えて、最初のVehicleMoveEventを確実に発生させる。
        double initialBlocksPerTick = config.getMinSpeedBps() / 20.0;
        cart.setVelocity(new Vector(direction.getModX(), 0, direction.getModZ()).multiply(initialBlocksPerTick));

        UUID minecartId = cart.getUniqueId();
        ScheduledTask watchdogTask = cart.getScheduler().runAtFixedRate(
                plugin,
                task -> watchdogTick(cart),
                () -> onEntityRetired(minecartId),
                20L, 20L
        );
        session.setWatchdogTask(watchdogTask);
        notifyStart(cart, target);
        return true;
    }

    /** 発車時、乗客のアクションバーに常設の「〇〇駅へ移動中」表示を出す（セッション終了時にnotifyEndでクリアされる）。 */
    private void notifyStart(Minecart cart, RailStationManager.Station target) {
        for (Entity passenger : cart.getPassengers()) {
            if (passenger instanceof Player player) {
                String message = FormatUtil.replace(
                        plugin.getConfigManager().getMessage("rail.moving_to_actionbar", player), "%name%", target.displayName());
                plugin.getActionBarManager().setChannel(player, "rail", FormatUtil.component(message));
            }
        }
    }

    public void endSession(Minecart cart, EndReason reason) {
        endSession(cart, reason, null);
    }

    /**
     * stationNameはARRIVED時のみ意味を持つ（messages.ymlのrail.arrivedにある%station%の置換用）。
     * それ以外の理由ではnullでよい。
     */
    private void endSession(Minecart cart, EndReason reason, String stationName) {
        RailSession session = sessions.remove(cart.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.watchdogTask() != null) {
            session.watchdogTask().cancel();
        }
        releaseChunkTickets(session);
        if (cart.isValid()) {
            cart.getPersistentDataContainer().remove(railModeKey);
            cart.setMaxSpeed(session.originalMaxSpeed());
            cart.setVelocity(new Vector(0, 0, 0));
        }
        notifyEnd(cart, reason, stationName);
    }

    private void notifyEnd(Minecart cart, EndReason reason, String stationName) {
        String key = switch (reason) {
            case ARRIVED -> "rail.arrived";
            case OFF_RAIL -> "rail.off_rail_cancelled";
            case COLLISION -> "rail.collision_cancelled";
            default -> null; // DESTROYED/WORLD_DISABLED/ERROR/DISMOUNTEDは無言で終了する
        };
        for (Entity passenger : cart.getPassengers()) {
            if (passenger instanceof Player player) {
                plugin.getActionBarManager().clearChannel(player, "rail");
            }
        }
        if (key == null) {
            return;
        }
        for (Entity passenger : cart.getPassengers()) {
            if (passenger instanceof Player player) {
                String message = plugin.getConfigManager().getMessage(key, player);
                if (stationName != null) {
                    message = FormatUtil.replace(message, "%station%", RailStationManager.formatDisplayName(stationName));
                }
                player.sendMessage(message);
            }
        }
        if (reason == EndReason.ARRIVED && stationName != null) {
            for (Entity passenger : cart.getPassengers()) {
                if (passenger instanceof Player player) {
                    RailStationAnnouncement.play(plugin, player, stationName, true);
                }
            }
        }
    }

    /** startSessionから呼ぶ。発車地点の到着範囲に入っている駅を、最初から「通知済み」として扱う。 */
    private void markNearbyStationsAsNotified(Minecart cart, RailSession session) {
        double arrivalRadiusSq = config.getStationArrivalRadius() * config.getStationArrivalRadius();
        for (RailStationManager.Station station : stationManager.listAll()) {
            Location stationLocation = station.resolveLocation();
            if (stationLocation != null && stationLocation.getWorld().equals(cart.getWorld())
                    && stationLocation.distanceSquared(cart.getLocation()) <= arrivalRadiusSq) {
                session.markStationNotified(station.name());
            }
        }
    }

    /** target以外の駅の到着範囲に入った瞬間、通過タイトル＋通知音を1回だけ出す（急行運転の演出）。 */
    private void notifyStationPassage(Minecart cart, RailSession session, RailStationManager.Station target) {
        double arrivalRadiusSq = config.getStationArrivalRadius() * config.getStationArrivalRadius();
        for (RailStationManager.Station station : stationManager.listAll()) {
            if (station.name().equalsIgnoreCase(target.name()) || session.hasNotifiedStation(station.name())) {
                continue;
            }
            Location stationLocation = station.resolveLocation();
            if (stationLocation == null || !stationLocation.getWorld().equals(cart.getWorld())
                    || stationLocation.distanceSquared(cart.getLocation()) > arrivalRadiusSq) {
                continue;
            }
            session.markStationNotified(station.name());
            for (Entity passenger : cart.getPassengers()) {
                if (passenger instanceof Player player) {
                    RailStationAnnouncement.play(plugin, player, station.name(), false);
                }
            }
        }
    }

    /** VehicleMoveEventから毎tick呼ぶ。対象外のトロッコなら何もしない。 */
    public void tickMovement(Minecart cart) {
        RailSession session = sessions.get(cart.getUniqueId());
        if (session == null) {
            return;
        }
        if (WorldBlacklistUtil.isBlacklisted(config.getDisabledWorlds(), cart.getWorld().getName())) {
            endSession(cart, EndReason.WORLD_DISABLED);
            return;
        }

        Block currentBlock = cart.getLocation().getBlock();
        Rail.Shape shape = RailSpeedController.shapeAt(currentBlock);

        // 「レールが無い」だけでなく「レールはあるが今の進行方向と接続しない」（平面交差点で
        // 別路線が直交して使っている等）も同じ扱いにする。以前は後者を即OFF_RAILにしていたため、
        // markOnRailNowが直前まで毎tick更新され続けていて猶予期間が一切効かず、
        // 交差点に差し掛かった瞬間に問答無用でキャンセルされていた。
        boolean blocked = shape == null;
        boolean directionChanged = false;
        if (!blocked && session.hasEnteredBlock(currentBlock.getX(), currentBlock.getY(), currentBlock.getZ())) {
            BlockFace nextDirection = RailSpeedController.nextDirection(shape, session.direction());
            if (nextDirection == null) {
                blocked = true;
            } else {
                directionChanged = nextDirection != session.direction();
                session.setDirection(nextDirection);
                session.rememberBlock(currentBlock.getX(), currentBlock.getY(), currentBlock.getZ());
            }
        }

        if (blocked) {
            if (System.currentTimeMillis() - session.lastOnRailMillis() > config.getOffRailGraceMillis()) {
                endSession(cart, EndReason.OFF_RAIL);
                return;
            }
            // 平面交差点などレールが1ブロックだけ途切れている（または直交する別路線のレールで
            // 塞がれている）区間を、バニラの慣性任せにせず猶予期間の最初の数tickだけ能動的に
            // 押し続けて確実に跨がせる。ここでapplyVelocityを呼ばずただ待つだけだと、バニラ側の
            // 摩擦で急減速し、次tickでも対岸のレールに届かないまま…を繰り返して結局OFF_RAILに
            // なることがあった（以前の実装はこの猶予期間を「何もせず待つだけ」に使っていた）。
            // directionは更新していない（＝曲がる前の方向のまま）ので、通り抜けた先でまた
            // hasEnteredBlockの判定に戻り、そこで初めて次の方向を確定する。
            if (session.incrementAndGetOffRailTicks() <= config.getOffRailActivePushTicks()) {
                applyVelocity(cart, session, session.currentSpeedBps());
            }
            return;
        }
        session.markOnRailNow();

        RailStationManager.Station target = stationManager.get(session.targetStationName());
        if (target == null) {
            // 走行中に管理者が目的駅を削除した場合。目的地判定が失われて走り続けてしまうため、
            // 安全側に倒してここで解除する（無言。既にプレイヤーの操作で消えたものではないため）。
            endSession(cart, EndReason.ERROR);
            return;
        }
        notifyStationPassage(cart, session, target);

        double targetSpeed = computeTargetSpeed(currentBlock, shape, session);
        double newSpeed = RailSpeedController.nextSpeed(
                session.currentSpeedBps(), targetSpeed, config.getAccelerationBps2(), config.getDecelerationBps2());
        session.setCurrentSpeedBps(newSpeed);

        Location targetLocation = target.resolveLocation();
        boolean atTarget = targetLocation != null
                && targetLocation.getWorld().equals(cart.getWorld())
                && targetLocation.distanceSquared(cart.getLocation()) <= config.getStationArrivalRadius() * config.getStationArrivalRadius();
        if (atTarget && newSpeed <= config.getMinSpeedBps()) {
            endSession(cart, EndReason.ARRIVED, target.name());
            return;
        }

        applyVelocity(cart, session, newSpeed);
        if (!sessions.containsKey(cart.getUniqueId())) {
            return; // applyVelocity内でNaN検知によりERROR終了した場合、破棄済みセッションでチャンク先読みを行わない
        }
        updateChunkPreload(cart, session, directionChanged);
    }

    /** 20tick(1秒)ごとにcart.getScheduler()から呼ばれる。VehicleMoveEventが発火しない（停止・詰まった）場合でも
     *  レールを外れてからの猶予タイムアウトを確実に評価するための安全網。 */
    private void watchdogTick(Minecart cart) {
        RailSession session = sessions.get(cart.getUniqueId());
        if (session == null) {
            return;
        }
        if (System.currentTimeMillis() - session.lastOnRailMillis() > config.getOffRailGraceMillis()) {
            endSession(cart, EndReason.OFF_RAIL);
        }
    }

    /** エンティティがFoliaのスケジューラから見て無効化された時に呼ばれる（VehicleDestroyEvent以外の理由での
     *  消滅でも確実にチャンクチケットを解放するため）。cartオブジェクトは既に無効な可能性があるため参照しない。 */
    private void onEntityRetired(UUID minecartId) {
        RailSession session = sessions.remove(minecartId);
        if (session != null) {
            releaseChunkTickets(session);
        }
    }

    /**
     * 現在ブロックから進行方向へ辿りながら、制動距離内に迫っているカーブ/坂道/目的駅の制限速度を
     * 反映した今tickの目標速度を返す（元設計書「カーブ進入前に減速する」を満たすための先読み）。
     * 目的駅以外の駅は通過する（急行運転）ため速度に影響しない。
     * 制動距離ぶん先まで見れば十分なので、それを超えて延々と辿ることはしない。
     */
    private double computeTargetSpeed(Block currentBlock, Rail.Shape currentShape, RailSession session) {
        double limit = RailSpeedController.maxSpeedFor(currentShape, config);
        double brakingNeeded = RailSpeedController.brakingDistance(session.currentSpeedBps(), config.getDecelerationBps2())
                * config.getSlowDownMargin();
        double maxLookahead = Math.max(brakingNeeded, 1.0) + 2.0;

        RailStationManager.Station target = stationManager.get(session.targetStationName());
        Location targetLocation = target != null ? target.resolveLocation() : null;
        double arrivalRadiusSq = config.getStationArrivalRadius() * config.getStationArrivalRadius();

        Block scanBlock = currentBlock;
        BlockFace scanDirection = session.direction();
        double distance = 0.0;
        World world = currentBlock.getWorld();

        while (distance < maxLookahead) {
            if (targetLocation != null && world.equals(targetLocation.getWorld())) {
                Location blockCenter = scanBlock.getLocation().add(0.5, 0.5, 0.5);
                if (blockCenter.distanceSquared(targetLocation) <= arrivalRadiusSq && distance <= brakingNeeded) {
                    return 0.0;
                }
            }

            // stepAlongRailを使うことで、カーブ・坂道の接続バグ（曲がる前の方向で隣を見てしまう等）を
            // この先読みでも踏まないようにする。以前はここだけ簡易な独自実装（scanBlock.getRelative
            // (scanDirection)固定）を持っていて、カーブや坂の先にある駅の手前で先読みが早期に打ち切られ、
            // 減速が始まらないまま駅を通過してしまうことがあった。
            RailStep step = stepAlongRail(scanBlock, scanDirection, true);
            if (step == null) {
                break; // この先はレールが無い、または未ロードのチャンク。今のブロックの制限だけで判断する
            }
            Rail.Shape nextShape = RailSpeedController.shapeAt(step.block());
            double aheadLimit = RailSpeedController.maxSpeedFor(nextShape, config);
            if (aheadLimit < limit && distance <= brakingNeeded) {
                limit = Math.min(limit, aheadLimit);
            }
            scanBlock = step.block();
            scanDirection = step.direction();
            distance += 1.0;
        }
        return limit;
    }

    /**
     * originBlockの直線区間が持つ2つの進行方向のうち、実際にレールをたどってtargetへ到達でき、
     * かつ一方通行にも従っている方を返す。駅の「向き」設定は廃止したため、発車方向はここで実際の
     * レール接続を歩いて確定する（分岐点はバニラのRail.Shapeが常に単一の接続を持つため、特別な
     * 分岐処理なしで自然に追従できる）。
     * 「到達可能」と「一方通行に従う」は別々にではなく候補ごとにまとめて判定する — 環状線では
     * 片方の候補が到達可能でも逆走になり、もう片方の候補（同じく到達可能）が正しい向き、という
     * ケースがあるため（先に見つかった到達可能な候補を即returnすると、後者を試す前に
     * WRONG_DIRECTION扱いで弾いてしまっていた）。
     */
    private BlockFace findDepartureDirection(Block originBlock, Rail.Shape originShape, RailStationManager.Station target) {
        Location targetLocation = target.resolveLocation();
        if (targetLocation == null) {
            return null;
        }
        BlockFace[] endpoints = RailSpeedController.endpointsOf(originShape);
        if (endpoints == null) {
            return null;
        }
        double arrivalRadiusSq = config.getStationArrivalRadius() * config.getStationArrivalRadius();
        for (BlockFace candidate : endpoints) {
            if (pathReachesStation(originBlock, candidate, targetLocation, arrivalRadiusSq)
                    && isDirectionAllowed(originBlock, candidate, target)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 一方通行を無視して、targetへ到達できる方向がそもそも存在するかどうかだけを調べる。
     * checkDepartureでNO_ROUTE（経路自体が無い）とWRONG_DIRECTION（経路はあるが逆走になる）を
     * 分けたメッセージにするためだけに使う診断用メソッド。
     */
    private boolean isReachableIgnoringOneWay(Block originBlock, Rail.Shape originShape, RailStationManager.Station target) {
        Location targetLocation = target.resolveLocation();
        if (targetLocation == null) {
            return false;
        }
        BlockFace[] endpoints = RailSpeedController.endpointsOf(originShape);
        if (endpoints == null) {
            return false;
        }
        double arrivalRadiusSq = config.getStationArrivalRadius() * config.getStationArrivalRadius();
        for (BlockFace candidate : endpoints) {
            if (pathReachesStation(originBlock, candidate, targetLocation, arrivalRadiusSq)) {
                return true;
            }
        }
        return false;
    }

    /** レールに沿って1マス進んだ結果（次のブロックと、そこから続ける進行方向）。 */
    private record RailStep(Block block, BlockFace direction) {
    }

    /**
     * fromBlockからdirection方向へ1マス進んだ結果を返す。通常はfromBlockの形状に従って辿るが、
     * fromBlockに互換性の無いレール（平面交差点で別路線が直交して使っている等）や、レールが
     * 全く無い隙間があった場合は、直進方向にもう1マス先を試す（バニラの「一方通行同士なら
     * 勢いで交差点を直進突破できる」平面交差点トリックに対応するため）。それでも進めなければnull。
     * 坂道の高い側へ抜ける場合はY方向にも+1する（水平移動だけだとレールが途切れている扱いになる）。
     * 次ブロックの水平方向はカーブで曲がった後のnextDirection側（進行方向）を使う。曲がる前の
     * directionのまま隣を見てしまうと、カーブでは実際に接続していない側を見ることになる。
     * コマンド実行時の経路探索（駅の少し先まで確認できれば十分）からは2引数版を、毎tick走る
     * computeTargetSpeedの先読みからは requireChunkLoaded=true の3引数版を呼ぶこと
     * （毎tick未ロードチャンクを強制ロードしてしまうと重くなるため）。
     */
    private RailStep stepAlongRail(Block fromBlock, BlockFace direction) {
        return stepAlongRail(fromBlock, direction, false);
    }

    private RailStep stepAlongRail(Block fromBlock, BlockFace direction, boolean requireChunkLoaded) {
        Rail.Shape shape = RailSpeedController.shapeAt(fromBlock);
        if (shape != null) {
            BlockFace nextDirection = RailSpeedController.nextDirection(shape, direction);
            if (nextDirection != null) {
                int verticalOffset = RailSpeedController.verticalOffset(shape, nextDirection);
                Block nextBlock = fromBlock.getRelative(nextDirection.getModX(), verticalOffset, nextDirection.getModZ());
                if (requireChunkLoaded && !isChunkLoaded(nextBlock)) {
                    return null;
                }
                if (verticalOffset == 0 && RailSpeedController.shapeAt(nextBlock) == null) {
                    // 平坦レール→1段下にある坂道の高い側入口、という接続を試す
                    // （verticalOffsetはfromBlock自身が坂道から抜ける時しか+1にならず、平坦区間から
                    // 坂道へ下りて入る時のオフセットまでは分からないため、ここで補う）。
                    Block loweredBlock = fromBlock.getRelative(nextDirection.getModX(), -1, nextDirection.getModZ());
                    if ((!requireChunkLoaded || isChunkLoaded(loweredBlock)) && RailSpeedController.shapeAt(loweredBlock) != null) {
                        nextBlock = loweredBlock;
                    }
                }
                return new RailStep(nextBlock, nextDirection);
            }
        }
        // fromBlockのレールが無い、または直進方向と互換性が無い（別路線が直交している平面交差点）。
        // 直進方向にもう1マス先（交差点の反対側）を試す。
        Block hopBlock = fromBlock.getRelative(direction);
        if (requireChunkLoaded && !isChunkLoaded(hopBlock)) {
            return null;
        }
        Rail.Shape hopShape = RailSpeedController.shapeAt(hopBlock);
        if (hopShape == null) {
            return null;
        }
        BlockFace hopNextDirection = RailSpeedController.nextDirection(hopShape, direction);
        if (hopNextDirection == null) {
            return null;
        }
        return new RailStep(hopBlock, hopNextDirection);
    }

    private static boolean isChunkLoaded(Block block) {
        return block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    /** originBlockからcandidateDirection方向へレールをたどり、targetLocationの到着範囲内に入れるか調べる。
     *  config.getPathSearchMaxBlocks() を上限に、行き止まり・接続不整合でも探索を打ち切る。
     *  毎tick走る先読み(computeTargetSpeed)と違い、この探索はコマンド実行時に1回だけ動くため、
     *  未ロードチャンクを踏んでも強制ロードのガードは入れない（起きても一度きりの軽いヒッチで済む）。 */
    private boolean pathReachesStation(Block originBlock, BlockFace candidateDirection, Location targetLocation, double arrivalRadiusSq) {
        Block scanBlock = originBlock;
        BlockFace scanDirection = candidateDirection;
        int maxBlocks = config.getPathSearchMaxBlocks();
        for (int i = 0; i < maxBlocks; i++) {
            Location blockCenter = scanBlock.getLocation().add(0.5, 0.5, 0.5);
            if (blockCenter.getWorld().equals(targetLocation.getWorld())
                    && blockCenter.distanceSquared(targetLocation) <= arrivalRadiusSq) {
                return true;
            }
            RailStep step = stepAlongRail(scanBlock, scanDirection);
            if (step == null) {
                return false;
            }
            scanBlock = step.block();
            scanDirection = step.direction();
        }
        return false;
    }

    /**
     * chosenDirection側への発車が、経路上のどの一方通行路線の駅も逆順で通過しないかを検証する。
     * target自身がどの路線に属していなくても、経路の途中で一方通行路線の駅を逆順に通過するなら拒否する
     * （路線に未登録の駅が一方通行区間の途中・先にあり、そこへ逆走できてしまうケースをカバーするため）。
     * 起点の背後（逆方向）で最初に見つかる各路線の駅の順序を基準値にし、そこから発車方向へ実際に
     * 目的地までたどりながら、各路線の駅の順序が単調増加になっているかを見る。
     */
    private boolean isDirectionAllowed(Block originBlock, BlockFace chosenDirection, RailStationManager.Station target) {
        List<RailLineManager.RailLine> oneWayLines = lineManager.listAll().stream()
                .filter(RailLineManager.RailLine::oneWay)
                .toList();
        if (oneWayLines.isEmpty()) {
            return true;
        }
        double arrivalRadiusSq = config.getStationArrivalRadius() * config.getStationArrivalRadius();
        Location targetLocation = target.resolveLocation();

        Map<String, Integer> lastSequenceByLine = new HashMap<>();
        collectNearestSequencePerLine(originBlock, chosenDirection.getOppositeFace(), oneWayLines, arrivalRadiusSq, lastSequenceByLine);

        Block scanBlock = originBlock;
        BlockFace scanDirection = chosenDirection;
        int maxBlocks = config.getPathSearchMaxBlocks();
        for (int i = 0; i < maxBlocks; i++) {
            Location blockCenter = scanBlock.getLocation().add(0.5, 0.5, 0.5);
            for (RailLineManager.RailLine line : oneWayLines) {
                RailStationManager.Station station = stationOnLineAt(line, blockCenter, arrivalRadiusSq);
                if (station == null) {
                    continue;
                }
                int sequence = line.sequenceOf(station.name());
                Integer previous = lastSequenceByLine.get(line.name());
                if (previous != null && sequence < previous) {
                    return false; // この路線を逆順に通過している
                }
                lastSequenceByLine.put(line.name(), sequence);
            }
            if (targetLocation != null && blockCenter.getWorld().equals(targetLocation.getWorld())
                    && blockCenter.distanceSquared(targetLocation) <= arrivalRadiusSq) {
                return true; // 目的地に到達。ここまで逆順は無かった
            }
            RailStep step = stepAlongRail(scanBlock, scanDirection);
            if (step == null) {
                return true; // これ以上進めない。ここまでの範囲で逆順は無かったので許可する
            }
            scanBlock = step.block();
            scanDirection = step.direction();
        }
        return true;
    }

    /** originBlockからdirection方向へレールをたどり、oneWayLinesそれぞれについて最初に見つかった駅の
     *  順序番号をresultに記録する（全路線ぶん見つかるか、探索上限に達したら打ち切る）。 */
    private void collectNearestSequencePerLine(
            Block originBlock, BlockFace direction, List<RailLineManager.RailLine> oneWayLines,
            double arrivalRadiusSq, Map<String, Integer> result) {
        Block scanBlock = originBlock;
        BlockFace scanDirection = direction;
        int maxBlocks = config.getPathSearchMaxBlocks();
        for (int i = 0; i < maxBlocks && result.size() < oneWayLines.size(); i++) {
            Location blockCenter = scanBlock.getLocation().add(0.5, 0.5, 0.5);
            for (RailLineManager.RailLine line : oneWayLines) {
                if (result.containsKey(line.name())) {
                    continue;
                }
                RailStationManager.Station station = stationOnLineAt(line, blockCenter, arrivalRadiusSq);
                if (station != null) {
                    result.put(line.name(), line.sequenceOf(station.name()));
                }
            }
            RailStep step = stepAlongRail(scanBlock, scanDirection);
            if (step == null) {
                return;
            }
            scanBlock = step.block();
            scanDirection = step.direction();
        }
    }

    /** lineに属する駅のうち、blockCenterからarrivalRadiusSq以内にあるものを返す（無ければnull）。 */
    private RailStationManager.Station stationOnLineAt(RailLineManager.RailLine line, Location blockCenter, double arrivalRadiusSq) {
        for (String stationName : line.stationNamesInOrder()) {
            RailStationManager.Station station = stationManager.get(stationName);
            Location stationLocation = station != null ? station.resolveLocation() : null;
            if (stationLocation != null && stationLocation.getWorld().equals(blockCenter.getWorld())
                    && stationLocation.distanceSquared(blockCenter) <= arrivalRadiusSq) {
                return station;
            }
        }
        return null;
    }

    private void applyVelocity(Minecart cart, RailSession session, double speedBps) {
        double blocksPerTick = speedBps / 20.0;
        Vector velocity = new Vector(session.direction().getModX(), 0, session.direction().getModZ())
                .multiply(blocksPerTick);

        double clamp = config.getMaxVelocityClampBpt();
        if (velocity.lengthSquared() > clamp * clamp) {
            velocity.normalize().multiply(clamp);
        }
        if (!Double.isFinite(velocity.getX()) || !Double.isFinite(velocity.getY()) || !Double.isFinite(velocity.getZ())) {
            plugin.getLogger().log(Level.WARNING, "レール高速モードで異常なVelocityを検知したため強制解除しました: " + cart.getUniqueId());
            endSession(cart, EndReason.ERROR);
            return;
        }
        cart.setVelocity(velocity);
    }

    /**
     * チャンクをまたいだ時、またはこのtickでカーブに入って進行方向が変わった時にチケットを張り替える。
     * 方向転換をチャンク跨ぎ以外でも見るのは、チャンクの真ん中で90度曲がった場合に古い方向のチャンクを
     * 保持したまま先読みが更新されず、曲がった先が未ロードで引っかかる可能性があるため。
     */
    private void updateChunkPreload(Minecart cart, RailSession session, boolean directionChanged) {
        if (!config.isChunkPreloadEnabled()) {
            return;
        }
        Chunk currentChunk = cart.getLocation().getChunk();
        boolean enteredNewChunk = session.hasEnteredChunk(currentChunk.getX(), currentChunk.getZ());
        if (!enteredNewChunk && !directionChanged) {
            return;
        }
        releaseChunkTickets(session);

        World world = cart.getWorld();
        session.setTicketWorld(world);
        int chunkDx = (int) Math.signum(session.direction().getModX());
        int chunkDz = (int) Math.signum(session.direction().getModZ());
        for (int i = 1; i <= config.getChunkPreloadDistance(); i++) {
            int cx = currentChunk.getX() + chunkDx * i;
            int cz = currentChunk.getZ() + chunkDz * i;
            acquireChunkTicket(world, cx, cz);
            session.heldChunkTickets().add(packChunk(cx, cz));
        }
        session.rememberChunk(currentChunk.getX(), currentChunk.getZ());
    }

    private void releaseChunkTickets(RailSession session) {
        World ticketWorld = session.ticketWorld();
        if (ticketWorld != null) {
            for (long packed : session.heldChunkTickets()) {
                releaseChunkTicket(ticketWorld, (int) (packed >> 32), (int) packed);
            }
        }
        session.heldChunkTickets().clear();
        session.setTicketWorld(null);
    }

    private void acquireChunkTicket(World world, int x, int z) {
        ChunkKey key = new ChunkKey(world, x, z);
        int count = chunkTicketRefCounts.merge(key, 1, Integer::sum);
        if (count == 1) {
            world.addPluginChunkTicket(x, z, plugin);
        }
    }

    private void releaseChunkTicket(World world, int x, int z) {
        ChunkKey key = new ChunkKey(world, x, z);
        chunkTicketRefCounts.computeIfPresent(key, (k, count) -> {
            if (count <= 1) {
                world.removePluginChunkTicket(x, z, plugin);
                return null;
            }
            return count - 1;
        });
    }

    private static long packChunk(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }

    /** onDisableから呼ぶ。保持中のチャンクチケットを全て解放し、セッションを破棄する。 */
    public void shutdown() {
        for (RailSession session : sessions.values()) {
            if (session.watchdogTask() != null) {
                session.watchdogTask().cancel();
            }
            try {
                releaseChunkTickets(session);
                Entity entity = Bukkit.getEntity(session.minecartId());
                if (entity instanceof Minecart cart && cart.isValid()) {
                    cart.getPersistentDataContainer().remove(railModeKey);
                    cart.setMaxSpeed(session.originalMaxSpeed());
                }
            } catch (Exception e) {
                plugin.getLogger().warning("レール高速モードのシャットダウン処理中にエラーが発生しました（セッション: "
                        + session.minecartId() + "）: " + e.getMessage());
            }
        }
        sessions.clear();
    }
}
