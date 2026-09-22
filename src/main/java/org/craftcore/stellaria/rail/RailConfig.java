package org.craftcore.stellaria.rail;

import org.craftcore.stellaria.StellariaCore;

import java.util.List;

/**
 * config.yml の rail.* を読み込みキャッシュするクラス。
 * RailManagerの毎tickの速度計算はVehicleMoveEventのたびに走るホットパスのため、
 * ConfigManager(ひいてはYamlConfiguration)への文字列引きを毎回行わずここでまとめて保持する。
 * /stellariareload 時は StellariaCore#reloadFeatureManagers() から reload() が呼ばれる。
 */
public final class RailConfig {

    private final StellariaCore plugin;

    private volatile double straightSpeedBps;
    private volatile double curveSpeedBps;
    private volatile double slopeSpeedBps;
    private volatile double accelerationBps2;
    private volatile double decelerationBps2;
    private volatile double minSpeedBps;
    private volatile double maxVelocityClampBpt;
    private volatile long offRailGraceMillis;
    private volatile int offRailActivePushTicks;
    private volatile boolean chunkPreloadEnabled;
    private volatile int chunkPreloadDistance;
    private volatile double stationArrivalRadius;
    private volatile double slowDownMargin;
    private volatile int pathSearchMaxBlocks;
    private volatile List<String> disabledWorlds;
    private volatile boolean openGuiOnInteractEnabled;
    private volatile boolean stationParticleEnabled;
    private volatile String stationParticleColorHex;
    private volatile double stationParticleRadius;
    private volatile double stationParticleSize;
    private volatile int stationParticleIntervalTicks;

    public RailConfig(StellariaCore plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var cfg = plugin.getConfigManager();
        straightSpeedBps = cfg.getDouble("rail.speed.straight", 40.0);
        curveSpeedBps = cfg.getDouble("rail.speed.curve", 12.0);
        slopeSpeedBps = cfg.getDouble("rail.speed.slope", 20.0);
        accelerationBps2 = Math.max(0.1, cfg.getDouble("rail.acceleration", 4.0));
        decelerationBps2 = Math.max(0.1, cfg.getDouble("rail.deceleration", 8.0));
        minSpeedBps = Math.max(0.0, cfg.getDouble("rail.min-speed", 2.0));
        maxVelocityClampBpt = Math.max(0.1, cfg.getDouble("rail.max-velocity-clamp", 3.0));
        offRailGraceMillis = (long) (Math.max(0.0, cfg.getDouble("rail.off-rail-grace-seconds", 2.0)) * 1000L);
        offRailActivePushTicks = Math.max(0, cfg.getInt("rail.off-rail-active-push-ticks", 4));
        chunkPreloadEnabled = cfg.getBoolean("rail.chunk-preload.enabled", true);
        chunkPreloadDistance = Math.max(0, cfg.getInt("rail.chunk-preload.distance", 2));
        stationArrivalRadius = Math.max(0.5, cfg.getDouble("rail.station.arrival-radius", 2.5));
        slowDownMargin = Math.max(1.0, cfg.getDouble("rail.station.slow-down-margin", 1.3));
        pathSearchMaxBlocks = Math.max(16, cfg.getInt("rail.station.max-path-search-blocks", 3000));
        disabledWorlds = cfg.getStringList("rail.disabled-worlds");
        openGuiOnInteractEnabled = cfg.getBoolean("rail.gui.open-on-interact", true);
        stationParticleEnabled = cfg.getBoolean("rail.station-particle.enabled", true);
        stationParticleColorHex = cfg.getString("rail.station-particle.color", "#3399FF");
        stationParticleRadius = Math.max(0.1, cfg.getDouble("rail.station-particle.radius", 1.3));
        stationParticleSize = Math.max(0.1, cfg.getDouble("rail.station-particle.size", 1.0));
        stationParticleIntervalTicks = Math.max(1, cfg.getInt("rail.station-particle.interval-ticks", 20));
    }

    public double getStraightSpeedBps() { return straightSpeedBps; }
    public double getCurveSpeedBps() { return curveSpeedBps; }
    public double getSlopeSpeedBps() { return slopeSpeedBps; }
    public double getAccelerationBps2() { return accelerationBps2; }
    public double getDecelerationBps2() { return decelerationBps2; }
    public double getMinSpeedBps() { return minSpeedBps; }
    public double getMaxVelocityClampBpt() { return maxVelocityClampBpt; }
    public long getOffRailGraceMillis() { return offRailGraceMillis; }
    public int getOffRailActivePushTicks() { return offRailActivePushTicks; }
    public boolean isChunkPreloadEnabled() { return chunkPreloadEnabled; }
    public int getChunkPreloadDistance() { return chunkPreloadDistance; }
    public double getStationArrivalRadius() { return stationArrivalRadius; }
    public double getSlowDownMargin() { return slowDownMargin; }
    public int getPathSearchMaxBlocks() { return pathSearchMaxBlocks; }
    public List<String> getDisabledWorlds() { return disabledWorlds; }
    public boolean isOpenGuiOnInteractEnabled() { return openGuiOnInteractEnabled; }
    public boolean isStationParticleEnabled() { return stationParticleEnabled; }
    public String getStationParticleColorHex() { return stationParticleColorHex; }
    public double getStationParticleRadius() { return stationParticleRadius; }
    public double getStationParticleSize() { return stationParticleSize; }
    public int getStationParticleIntervalTicks() { return stationParticleIntervalTicks; }
}
