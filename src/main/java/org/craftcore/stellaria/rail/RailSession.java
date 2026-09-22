package org.craftcore.stellaria.rail;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 高速モード中のトロッコ1台ぶんの状態。KikoriManagerの伐採状態・TpaCoreの保留リクエストと同じく
 * インメモリのみで永続化しない（サーバー再起動やRailManager#shutdown()でリセットされる）。
 */
public final class RailSession {

    private final UUID minecartId;
    private final String originStationName;

    private double currentSpeedBps;
    private BlockFace direction;
    private long lastOnRailMillis;
    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;
    private int lastBlockX = Integer.MIN_VALUE;
    private int lastBlockY = Integer.MIN_VALUE;
    private int lastBlockZ = Integer.MIN_VALUE;
    private ScheduledTask watchdogTask;
    private final Set<Long> heldChunkTickets = new HashSet<>();
    private boolean departedOrigin;
    private double originalMaxSpeed;
    private World ticketWorld;

    public RailSession(UUID minecartId, String originStationName, BlockFace direction, double initialSpeedBps, double originalMaxSpeed) {
        this.minecartId = minecartId;
        this.originStationName = originStationName;
        this.direction = direction;
        this.currentSpeedBps = initialSpeedBps;
        this.originalMaxSpeed = originalMaxSpeed;
        this.lastOnRailMillis = System.currentTimeMillis();
    }

    public UUID minecartId() { return minecartId; }

    public String originStationName() { return originStationName; }

    public double currentSpeedBps() { return currentSpeedBps; }

    public void setCurrentSpeedBps(double value) { this.currentSpeedBps = value; }

    public BlockFace direction() { return direction; }

    public void setDirection(BlockFace value) { this.direction = value; }

    public long lastOnRailMillis() { return lastOnRailMillis; }

    public void markOnRailNow() { this.lastOnRailMillis = System.currentTimeMillis(); }

    /** チャンク先読みの再計算を「チャンクをまたいだ時だけ」にするための比較。 */
    public boolean hasEnteredChunk(int chunkX, int chunkZ) {
        return chunkX != lastChunkX || chunkZ != lastChunkZ;
    }

    public void rememberChunk(int chunkX, int chunkZ) {
        this.lastChunkX = chunkX;
        this.lastChunkZ = chunkZ;
    }

    /** カーブ分岐の再計算を「ブロックをまたいだ時だけ」にするための比較。 */
    public boolean hasEnteredBlock(int x, int y, int z) {
        return x != lastBlockX || y != lastBlockY || z != lastBlockZ;
    }

    public void rememberBlock(int x, int y, int z) {
        this.lastBlockX = x;
        this.lastBlockY = y;
        this.lastBlockZ = z;
    }

    public ScheduledTask watchdogTask() { return watchdogTask; }

    public void setWatchdogTask(ScheduledTask task) { this.watchdogTask = task; }

    public Set<Long> heldChunkTickets() { return heldChunkTickets; }

    public double originalMaxSpeed() { return originalMaxSpeed; }

    public boolean hasDepartedOrigin() { return departedOrigin; }

    public void markDepartedOrigin() { this.departedOrigin = true; }

    public World ticketWorld() { return ticketWorld; }

    public void setTicketWorld(World world) { this.ticketWorld = world; }
}
