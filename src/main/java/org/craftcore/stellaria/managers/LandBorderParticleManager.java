package org.craftcore.stellaria.managers;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ParticleUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /land border の常時表示を管理する。
 *
 * プレイヤーごとに半径と「境界になるXZ座標・隣接点の組」をキャッシュする。毎回走査するのは
 * 表示中のパーティクル座標だけで、対象チャンクの走査はON時、中心チャンクの移動時、または
 * claim一覧が変更された時に限る。
 */
public class LandBorderParticleManager {

    private static final int CHUNK_SIZE = 16;
    private static final int[][] NEIGHBOR_OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final StellariaCore plugin;
    private final Map<UUID, DisplayState> displays = new HashMap<>();

    private record BorderPoint(int x, int z) {
    }

    /** 高低差の縦補完に使う、XZ平面で隣り合う2つの境界点。 */
    private record BorderSegment(BorderPoint first, BorderPoint second) {
        static BorderSegment of(BorderPoint first, BorderPoint second) {
            Comparator<BorderPoint> order = Comparator.comparingInt(BorderPoint::x)
                    .thenComparingInt(BorderPoint::z);
            return order.compare(first, second) <= 0
                    ? new BorderSegment(first, second)
                    : new BorderSegment(second, first);
        }
    }

    private record BorderCache(LandManager.ChunkKey center, int radius, long claimsVersion,
                               List<BorderPoint> points, List<BorderSegment> segments) {
    }

    private static final class DisplayState {
        private final int radius;
        private BorderCache cache;

        private DisplayState(int radius) {
            this.radius = radius;
        }
    }

    public LandBorderParticleManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** 表示をONにする。同じプレイヤーがON済みでも半径を更新して座標キャッシュを作り直す。 */
    public void enable(Player player, int radius) {
        DisplayState state = new DisplayState(radius);
        state.cache = buildCache(LandManager.ChunkKey.of(player.getLocation()), radius);
        displays.put(player.getUniqueId(), state);
    }

    /** ON/OFFを反転する。反転後の状態（true=ON）を返す。 */
    public boolean toggle(Player player, int radius) {
        UUID uuid = player.getUniqueId();
        if (displays.remove(uuid) != null) {
            return false;
        }
        enable(player, radius);
        return true;
    }

    public void disable(UUID uuid) {
        displays.remove(uuid);
    }

    public boolean isEnabled(Player player) {
        return displays.containsKey(player.getUniqueId());
    }

    /** GlobalRegionSchedulerから設定間隔ごとに呼ぶ。 */
    public void tick() {
        LandManager land = plugin.getLandManager();
        Iterator<Map.Entry<UUID, DisplayState>> iterator = displays.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, DisplayState> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                iterator.remove();
                continue;
            }

            DisplayState state = entry.getValue();
            LandManager.ChunkKey center = LandManager.ChunkKey.of(player.getLocation());
            BorderCache cache = state.cache;
            if (cache == null || !cache.center().equals(center)
                    || cache.radius() != state.radius || cache.claimsVersion() != land.claimsVersion()) {
                cache = buildCache(center, state.radius);
                state.cache = cache;
            }
            render(player, cache);
        }
    }

    private BorderCache buildCache(LandManager.ChunkKey center, int radius) {
        LandManager land = plugin.getLandManager();
        Set<BorderPoint> points = new HashSet<>();
        Set<BorderSegment> segments = new HashSet<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LandManager.ChunkKey chunk = new LandManager.ChunkKey(
                        center.world(), center.chunkX() + dx, center.chunkZ() + dz);
                if (!land.isClaimed(chunk)) {
                    continue;
                }

                int minX = chunk.chunkX() * CHUNK_SIZE;
                int minZ = chunk.chunkZ() * CHUNK_SIZE;
                int maxX = minX + CHUNK_SIZE;
                int maxZ = minZ + CHUNK_SIZE;
                if (!land.isClaimed(new LandManager.ChunkKey(center.world(), chunk.chunkX(), chunk.chunkZ() - 1))) {
                    addHorizontalSide(points, segments, minX, maxX, minZ);
                }
                if (!land.isClaimed(new LandManager.ChunkKey(center.world(), chunk.chunkX(), chunk.chunkZ() + 1))) {
                    addHorizontalSide(points, segments, minX, maxX, maxZ);
                }
                if (!land.isClaimed(new LandManager.ChunkKey(center.world(), chunk.chunkX() - 1, chunk.chunkZ()))) {
                    addVerticalSide(points, segments, minX, minZ, maxZ);
                }
                if (!land.isClaimed(new LandManager.ChunkKey(center.world(), chunk.chunkX() + 1, chunk.chunkZ()))) {
                    addVerticalSide(points, segments, maxX, minZ, maxZ);
                }
            }
        }

        return new BorderCache(center, radius, land.claimsVersion(), List.copyOf(points), List.copyOf(segments));
    }

    private void addHorizontalSide(Set<BorderPoint> points, Set<BorderSegment> segments,
                                   int minX, int maxX, int z) {
        BorderPoint previous = null;
        for (int x = minX; x <= maxX; x++) {
            BorderPoint point = new BorderPoint(x, z);
            points.add(point);
            if (previous != null) {
                segments.add(BorderSegment.of(previous, point));
            }
            previous = point;
        }
    }

    private void addVerticalSide(Set<BorderPoint> points, Set<BorderSegment> segments,
                                 int x, int minZ, int maxZ) {
        BorderPoint previous = null;
        for (int z = minZ; z <= maxZ; z++) {
            BorderPoint point = new BorderPoint(x, z);
            points.add(point);
            if (previous != null) {
                segments.add(BorderSegment.of(previous, point));
            }
            previous = point;
        }
    }

    /**
     * 境界XZごとに現在の最高ブロックの1つ上を表示する。高さはキャッシュしないため、地形が変わっても
     * 次回表示時に追従する。隣接点の高低差が2以上なら低い側の座標に縦の補完点を足す。
     */
    private void render(Player player, BorderCache cache) {
        World world = player.getWorld();
        Map<BorderPoint, Integer> heights = new HashMap<>();
        for (BorderPoint point : cache.points()) {
            int y = world.getHighestBlockYAt(point.x(), point.z()) + 1;
            heights.put(point, y);
        }

        Particle particle = resolveParticle();
        Particle.DustOptions dust = particle == Particle.DUST
                ? new Particle.DustOptions(resolveColor(), (float) plugin.getConfigManager()
                .getDouble("land.border-particle.size", 1.0))
                : null;
        for (Map.Entry<BorderPoint, Integer> entry : heights.entrySet()) {
            spawn(player, particle, dust, entry.getKey(), entry.getValue());
        }
        for (BorderSegment segment : cache.segments()) {
            int firstY = heights.get(segment.first());
            int secondY = heights.get(segment.second());
            if (Math.abs(firstY - secondY) < 2) {
                continue;
            }
            BorderPoint lowerPoint = firstY < secondY ? segment.first() : segment.second();
            int lowerY = Math.min(firstY, secondY);
            int higherY = Math.max(firstY, secondY);
            for (int y = lowerY + 1; y < higherY; y++) {
                spawn(player, particle, dust, lowerPoint, y);
            }
        }
    }

    private void spawn(Player player, Particle particle, Particle.DustOptions dust, BorderPoint point, int y) {
        if (dust != null) {
            player.spawnParticle(Particle.DUST, point.x() + 0.5, y, point.z() + 0.5,
                    1, 0, 0, 0, 0, dust);
        } else {
            player.spawnParticle(particle, point.x() + 0.5, y, point.z() + 0.5,
                    1, 0, 0, 0, 0);
        }
    }

    private Particle resolveParticle() {
        try {
            return Particle.valueOf(plugin.getConfigManager().getString("land.border-particle.particle", "DUST"));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("land.border-particle.particle の値が不正なため、DUSTにフォールバックします: " + e.getMessage());
            return Particle.DUST;
        }
    }

    private Color resolveColor() {
        try {
            return ParticleUtil.parseColor(plugin.getConfigManager().getString("land.border-particle.color", "#55FF55"));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("land.border-particle.color の値が不正なため、デフォルト色にフォールバックします: " + e.getMessage());
            return ParticleUtil.parseColor("#55FF55");
        }
    }
}
