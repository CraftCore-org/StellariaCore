package org.craftcore.stellaria.rail;

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

    public Set<Long> heldChunkTickets() { return heldChunkTickets; }

    public double originalMaxSpeed() { return originalMaxSpeed; }

    public boolean hasDepartedOrigin() { return departedOrigin; }

    public void markDepartedOrigin() { this.departedOrigin = true; }

    public World ticketWorld() { return ticketWorld; }

    public void setTicketWorld(World world) { this.ticketWorld = world; }
}
