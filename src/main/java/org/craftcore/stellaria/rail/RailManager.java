package org.craftcore.stellaria.rail;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
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

    public enum EndReason { ARRIVED, OFF_RAIL, DESTROYED, WORLD_DISABLED, ERROR }

    private final StellariaCore plugin;
    private final RailConfig config;
    private final RailStationManager stationManager;
    private final NamespacedKey railModeKey;
    private final Map<UUID, RailSession> sessions = new ConcurrentHashMap<>();

    public RailManager(StellariaCore plugin, RailConfig config, RailStationManager stationManager) {
        this.plugin = plugin;
        this.config = config;
        this.stationManager = stationManager;
        this.railModeKey = new NamespacedKey(plugin, "rail_mode");
    }

    public boolean isRailMode(UUID minecartId) {
        return sessions.containsKey(minecartId);
    }

    /**
     * 駅originから高速モードを開始する。cartが現在乗っているレールの形状から初期進行方向を決める。
     * レールが無い、または端点が判別できない場合はfalseを返す（呼び出し側がエラーメッセージを出す）。
     */
    public boolean startSession(Minecart cart, RailStationManager.Station origin) {
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
        BlockFace[] endpoints = RailSpeedController.endpointsOf(shape);
        if (endpoints == null) {
            return false;
        }
        // 駅の登録時の向きがこの区間の端点に一致すればそちらを初期進行方向にする。
        // 一致しなければ（プラットフォームの向きと実際のレールが斜めにずれている等）endpoints[0]にフォールバック。
        BlockFace direction = (endpoints[0] == origin.direction() || endpoints[1] == origin.direction())
                ? origin.direction() : endpoints[0];

        double originalMaxSpeed = cart.getMaxSpeed();
        RailSession session = new RailSession(cart.getUniqueId(), origin.name(), direction, config.getMinSpeedBps(), originalMaxSpeed);
        sessions.put(cart.getUniqueId(), session);
        cart.getPersistentDataContainer().set(railModeKey, PersistentDataType.BOOLEAN, true);
        cart.setMaxSpeed(config.getMaxVelocityClampBpt());
        return true;
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
            default -> null; // DESTROYED/WORLD_DISABLED/ERRORは無言で終了する
        };
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

        if (!session.hasDepartedOrigin()) {
            RailStationManager.Station origin = stationManager.get(session.originStationName());
            double clearRadius = config.getStationActivationRadius();
            boolean stillNearOrigin = origin != null
                    && origin.location().getWorld().equals(cart.getWorld())
                    && origin.location().distanceSquared(cart.getLocation()) <= clearRadius * clearRadius;
            if (!stillNearOrigin) {
                session.markDepartedOrigin();
            }
        }

        BlockFace nextDirection = RailSpeedController.nextDirection(shape, session.direction());
        if (nextDirection == null) {
            // T字分岐・接続不整合。バニラ側で何が起きるか予測できないため安全側に倒して制御を手放す。
            endSession(cart, EndReason.OFF_RAIL);
            return;
        }
        session.setDirection(nextDirection);

        double targetSpeed = computeTargetSpeed(currentBlock, shape, session);
        double newSpeed = RailSpeedController.nextSpeed(
                session.currentSpeedBps(), targetSpeed, config.getAccelerationBps2(), config.getDecelerationBps2());
        session.setCurrentSpeedBps(newSpeed);

        RailStationManager.Station nearStation = stationManager.findWithin(cart.getLocation(), config.getStationArrivalRadius());
        boolean eligibleForArrival = nearStation != null
                && (session.hasDepartedOrigin() || !nearStation.name().equalsIgnoreCase(session.originStationName()));
        if (eligibleForArrival && newSpeed <= config.getMinSpeedBps()) {
            endSession(cart, EndReason.ARRIVED, nearStation.name());
            return;
        }

        applyVelocity(cart, session, newSpeed);
        if (!sessions.containsKey(cart.getUniqueId())) {
            return; // applyVelocity内でNaN検知によりERROR終了した場合、破棄済みセッションでチャンク先読みを行わない
        }
        updateChunkPreload(cart, session);
    }

    /**
     * 現在ブロックから進行方向へ辿りながら、制動距離内に迫っているカーブ/坂道/駅の制限速度を
     * 反映した今tickの目標速度を返す（元設計書「カーブ進入前に減速する」を満たすための先読み）。
     * 制動距離ぶん先まで見れば十分なので、それを超えて延々と辿ることはしない。
     */
    private double computeTargetSpeed(Block currentBlock, Rail.Shape currentShape, RailSession session) {
        double limit = RailSpeedController.maxSpeedFor(currentShape, config);
        double brakingNeeded = RailSpeedController.brakingDistance(session.currentSpeedBps(), config.getDecelerationBps2())
                * config.getSlowDownMargin();
        double maxLookahead = Math.max(brakingNeeded, 1.0) + 2.0;

        Block scanBlock = currentBlock;
        BlockFace scanDirection = session.direction();
        double distance = 0.0;
        World world = currentBlock.getWorld();

        while (distance < maxLookahead) {
            RailStationManager.Station station = stationManager.findWithin(
                    scanBlock.getLocation().add(0.5, 0.5, 0.5), config.getStationArrivalRadius());
            boolean stationApplies = station != null
                    && (session.hasDepartedOrigin() || !station.name().equalsIgnoreCase(session.originStationName()));
            if (stationApplies && distance <= brakingNeeded) {
                return 0.0;
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
        int chunkDx = (int) Math.signum(session.direction().getModX());
        int chunkDz = (int) Math.signum(session.direction().getModZ());
        for (int i = 1; i <= config.getChunkPreloadDistance(); i++) {
            int cx = currentChunk.getX() + chunkDx * i;
            int cz = currentChunk.getZ() + chunkDz * i;
            world.addPluginChunkTicket(cx, cz, plugin);
            session.heldChunkTickets().add(packChunk(cx, cz));
        }
        if (!session.heldChunkTickets().isEmpty()) {
            session.setTicketWorld(world);
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
            releaseChunkTickets(session);
            Entity entity = Bukkit.getEntity(session.minecartId());
            if (entity instanceof Minecart cart && cart.isValid()) {
                cart.getPersistentDataContainer().remove(railModeKey);
                cart.setMaxSpeed(session.originalMaxSpeed());
            }
        }
        sessions.clear();
    }
}
