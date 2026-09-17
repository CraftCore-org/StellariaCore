package org.craftcore.stellaria.managers;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.JapanTimeUtil;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

public class JapanTimeSyncManager {

    private static final long TICK_INTERVAL_TICKS = 20L;

    private final StellariaCore plugin;
    private ScheduledTask task;
    private final Set<String> warnedMissingWorlds = new HashSet<>();

    public JapanTimeSyncManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        warnedMissingWorlds.clear();

        if (!plugin.getConfigManager().getBoolean("world-time-sync.enabled", false)) {
            return;
        }

        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin, scheduled -> tick(), TICK_INTERVAL_TICKS, TICK_INTERVAL_TICKS);
    }

    public void restart() {
        start();
    }

    public void stop() {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
        task = null;
    }

    private void tick() {
        long ticks = JapanTimeUtil.minecraftTicks(Instant.now());
        for (String worldName : plugin.getConfigManager().getStringList("world-time-sync.worlds")) {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                if (warnedMissingWorlds.add(worldName)) {
                    plugin.getLogger().warning("world-time-sync.worlds に指定されたワールドが見つかりません: " + worldName);
                }
                continue;
            }
            world.setTime(ticks);
        }
    }
}
