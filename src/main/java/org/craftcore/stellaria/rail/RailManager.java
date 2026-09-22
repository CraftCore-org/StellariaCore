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

    public enum EndReason { ARRIVED, OFF_RAIL, DESTROYED, WORLD_DISABLED, ERROR, COLLISION }

    /** /rail depart のバリデーション用。実際の発車判定はstartSessionが（このメソッドと独立に）改めて行う。 */
    public enum DepartureCheck { OK, ALREADY_RAIL_MODE, NOT_ON_RAIL, NO_ROUTE, WRONG_DIRECTION }

    private final StellariaCore plugin;
    private final RailConfig config;
    private final RailStationManager stationManager;
    private final RailLineManager lineManager;
    private final NamespacedKey railModeKey;
    private final Map<UUID, RailSession> sessions = new ConcurrentHashMap<>();

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
        BlockFace direction = findDepartureDirection(block, shape, target);
        if (direction == null) {
            return DepartureCheck.NO_ROUTE;
        }
        if (!isDirectionAllowed(block, direction, target)) {
            return DepartureCheck.WRONG_DIRECTION;
        }
        return DepartureCheck.OK;
    }

    /**
     * cartの現在位置から実際にレールをたどって発車できる駅の一覧を返す（RailDepartGui専用）。
     * 登録駅の数だけ経路探索を行うため、コマンドのタブ補完（毎キー入力で呼ばれる）では使わず、
     * GUIを開く瞬間だけ呼ぶこと。カーブ上や未接続なら空リストを返す。
     */
    public List<RailStationManager.Station> findReachableStations(Minecart cart) {
        Block block = cart.getLocation().getBlock();
        Rail.Shape shape = RailSpeedController.shapeAt(block);
        if (shape == null || RailSpeedController.isCurve(shape)) {
            return List.of();
        }
        List<RailStationManager.Station> reachable = new ArrayList<>();
        for (RailStationManager.Station station : stationManager.listAll()) {
            BlockFace direction = findDepartureDirection(block, shape, station);
            if (direction != null && isDirectionAllowed(block, direction, station)) {
                reachable.add(station);
            }
        }
        return reachable;
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
        BlockFace direction = findDepartureDirection(block, shape, target);
        if (direction == null) {
            return false;
        }
        if (!isDirectionAllowed(block, direction, target)) {
            return false;
        }

        double originalMaxSpeed = cart.getMaxSpeed();
        RailSession session = new RailSession(cart.getUniqueId(), target.name(), direction, config.getMinSpeedBps(), originalMaxSpeed);
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
                        plugin.getConfigManager().getMessage("rail.moving_to_actionbar", player), "%name%", target.name());
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
            default -> null; // DESTROYED/WORLD_DISABLED/ERRORは無言で終了する
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
                    message = FormatUtil.replace(message, "%station%", stationName);
                }
                player.sendMessage(message);
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
        if (shape == null) {
            if (System.currentTimeMillis() - session.lastOnRailMillis() > config.getOffRailGraceMillis()) {
                endSession(cart, EndReason.OFF_RAIL);
            }
            return;
        }
        session.markOnRailNow();

        if (session.hasEnteredBlock(currentBlock.getX(), currentBlock.getY(), currentBlock.getZ())) {
            BlockFace nextDirection = RailSpeedController.nextDirection(shape, session.direction());
            if (nextDirection == null) {
                // T字分岐・接続不整合。バニラ側で何が起きるか予測できないため安全側に倒して制御を手放す。
                endSession(cart, EndReason.OFF_RAIL);
                return;
            }
            session.setDirection(nextDirection);
            session.rememberBlock(currentBlock.getX(), currentBlock.getY(), currentBlock.getZ());
        }

        double targetSpeed = computeTargetSpeed(currentBlock, shape, session);
        double newSpeed = RailSpeedController.nextSpeed(
                session.currentSpeedBps(), targetSpeed, config.getAccelerationBps2(), config.getDecelerationBps2());
        session.setCurrentSpeedBps(newSpeed);

        RailStationManager.Station target = stationManager.get(session.targetStationName());
        Location targetLocation = target != null ? target.resolveLocation() : null;
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
        updateChunkPreload(cart, session);
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

            Block nextBlock = scanBlock.getRelative(scanDirection);
            if (!world.isChunkLoaded(nextBlock.getX() >> 4, nextBlock.getZ() >> 4)) {
                break; // 先読みのために未ロードのチャンクを強制ロードしない
            }
            Rail.Shape nextShape = RailSpeedController.shapeAt(nextBlock);
            if (nextShape == null) {
                break; // この先はレールが無い（行き止まり・未敷設）。今のブロックの制限だけで判断する
            }
            double aheadLimit = RailSpeedController.maxSpeedFor(nextShape, config);
            if (aheadLimit < limit && distance <= brakingNeeded) {
                limit = Math.min(limit, aheadLimit);
            }
            BlockFace nextDirection = RailSpeedController.nextDirection(nextShape, scanDirection);
            if (nextDirection == null) {
                break;
            }
            scanBlock = nextBlock;
            scanDirection = nextDirection;
            distance += 1.0;
        }
        return limit;
    }

    /**
     * originBlockの直線区間が持つ2つの進行方向のうち、実際にレールをたどってtargetへ到達できる方を返す。
     * どちらの方向でも到達できなければnull（呼び出し側は「経路が見つからない」として扱う）。
     * 駅の「向き」設定は廃止したため、発車方向はここで実際のレール接続を歩いて確定する
     * （分岐点はバニラのRail.Shapeが常に単一の接続を持つため、特別な分岐処理なしで自然に追従できる）。
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
            if (pathReachesStation(originBlock, candidate, targetLocation, arrivalRadiusSq)) {
                return candidate;
            }
        }
        return null;
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
            Rail.Shape shape = RailSpeedController.shapeAt(scanBlock);
            if (shape == null) {
                return false;
            }
            Location blockCenter = scanBlock.getLocation().add(0.5, 0.5, 0.5);
            if (blockCenter.getWorld().equals(targetLocation.getWorld())
                    && blockCenter.distanceSquared(targetLocation) <= arrivalRadiusSq) {
                return true;
            }
            BlockFace nextDirection = RailSpeedController.nextDirection(shape, scanDirection);
            if (nextDirection == null) {
                return false;
            }
            scanBlock = scanBlock.getRelative(scanDirection);
            scanDirection = nextDirection;
        }
        return false;
    }

    /**
     * targetが一方通行路線に属する場合、chosenDirection側への発車が路線の正順序（駅の登録順）と
     * 一致しているかを検証する。両方通行の路線、またはtargetがどの路線にも属していなければ常にtrue。
     * 起点の背後（chosenDirectionの逆側）で最初に見つかる同路線の駅とtargetの順序を比較する
     * （背後に同路線の駅が見つからなければ、起点は路線の端にいるとみなして許可する）。
     */
    private boolean isDirectionAllowed(Block originBlock, BlockFace chosenDirection, RailStationManager.Station target) {
        RailLineManager.RailLine line = lineManager.findLineForStation(target.name());
        if (line == null || !line.oneWay()) {
            return true;
        }
        int targetSequence = line.sequenceOf(target.name());
        double arrivalRadiusSq = config.getStationArrivalRadius() * config.getStationArrivalRadius();
        RailStationManager.Station behindStation = findNearestLineStation(
                originBlock, chosenDirection.getOppositeFace(), line, target.name(), arrivalRadiusSq);
        if (behindStation == null) {
            return true;
        }
        int behindSequence = line.sequenceOf(behindStation.name());
        return behindSequence < targetSequence;
    }

    /** originBlockからdirection方向へレールをたどり、lineに属する駅（excludeStationNameを除く）のうち
     *  最初に見つかったものを返す。config.getPathSearchMaxBlocks() を上限に打ち切る。 */
    private RailStationManager.Station findNearestLineStation(
            Block originBlock, BlockFace direction, RailLineManager.RailLine line, String excludeStationName, double arrivalRadiusSq) {
        Block scanBlock = originBlock;
        BlockFace scanDirection = direction;
        int maxBlocks = config.getPathSearchMaxBlocks();
        for (int i = 0; i < maxBlocks; i++) {
            Rail.Shape shape = RailSpeedController.shapeAt(scanBlock);
            if (shape == null) {
                return null;
            }
            Location blockCenter = scanBlock.getLocation().add(0.5, 0.5, 0.5);
            for (String stationName : line.stationNamesInOrder()) {
                if (stationName.equalsIgnoreCase(excludeStationName)) {
                    continue;
                }
                RailStationManager.Station station = stationManager.get(stationName);
                Location stationLocation = station != null ? station.resolveLocation() : null;
                if (stationLocation != null && stationLocation.getWorld().equals(blockCenter.getWorld())
                        && stationLocation.distanceSquared(blockCenter) <= arrivalRadiusSq) {
                    return station;
                }
            }
            BlockFace nextDirection = RailSpeedController.nextDirection(shape, scanDirection);
            if (nextDirection == null) {
                return null;
            }
            scanBlock = scanBlock.getRelative(scanDirection);
            scanDirection = nextDirection;
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

    /** チャンクをまたいだ時だけ、進行方向側のチャンクにチケットを張り替える。 */
    private void updateChunkPreload(Minecart cart, RailSession session) {
        if (!config.isChunkPreloadEnabled()) {
            return;
        }
        Chunk currentChunk = cart.getLocation().getChunk();
        if (!session.hasEnteredChunk(currentChunk.getX(), currentChunk.getZ())) {
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
            world.addPluginChunkTicket(cx, cz, plugin);
            session.heldChunkTickets().add(packChunk(cx, cz));
        }
        session.rememberChunk(currentChunk.getX(), currentChunk.getZ());
    }

    private void releaseChunkTickets(RailSession session) {
        World ticketWorld = session.ticketWorld();
        if (ticketWorld != null) {
            for (long packed : session.heldChunkTickets()) {
                ticketWorld.removePluginChunkTicket((int) (packed >> 32), (int) packed, plugin);
            }
        }
        session.heldChunkTickets().clear();
        session.setTicketWorld(null);
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
