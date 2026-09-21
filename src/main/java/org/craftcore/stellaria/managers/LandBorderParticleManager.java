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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * チャンク境界パーティクルの常時表示を管理する。2つのモードがあり、同じプレイヤーが
 * 同時に両方を表示することはできない（片方をONにするともう片方は自動的に置き換わる）。
 * <ul>
 *   <li>{@link Mode#CLAIMED} — /land border。保護済みチャンクの外周を、
 *       エリア（隣接claimの集合）ごとに色分けして表示する。別エリア同士が隣接していれば
 *       同じ保護状態でも境界線を引く（他人の土地と繋がって見えないようにするため）。</li>
 *   <li>{@link Mode#ALL_CHUNKS} — /chunkborder。保護状態に関係なく全チャンクの境界を
 *       単色で表示する。</li>
 * </ul>
 *
 * プレイヤーごとに半径と「エリアごとの境界点・隣接点の組・色」をキャッシュする。毎回走査するのは
 * 表示中のパーティクル座標だけで、対象チャンクの走査はON時、中心チャンクの移動時、または
 * （CLAIMEDモードのみ）claim一覧が変更された時に限る。
 */
public class LandBorderParticleManager {

    public enum Mode { CLAIMED, ALL_CHUNKS }

    private static final int CHUNK_SIZE = 16;

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

    /** 1エリア（またはALL_CHUNKSモードの全体）分の境界点・隣接点・表示色。 */
    private record AreaBorder(Color color, List<BorderPoint> points, List<BorderSegment> segments) {
    }

    private record BorderCache(LandManager.ChunkKey center, int radius, long claimsVersion,
                               List<AreaBorder> areas) {
    }

    private static final class DisplayState {
        private final int radius;
        private final Mode mode;
        private BorderCache cache;

        private DisplayState(int radius, Mode mode) {
            this.radius = radius;
            this.mode = mode;
        }
    }

    public LandBorderParticleManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** 表示をONにする。他のモードで表示中だった場合は置き換わる。DBにも永続化する。 */
    public void enable(Player player, int radius, Mode mode) {
        DisplayState state = new DisplayState(radius, mode);
        state.cache = buildCache(LandManager.ChunkKey.of(player.getLocation()), radius, mode);
        displays.put(player.getUniqueId(), state);
        persist(player.getUniqueId(), mode, radius);
    }

    /**
     * 指定モードでON/OFFを反転する。既に「別モード」で表示中だった場合はOFFにせず、
     * 指定モードに切り替える（/land border 表示中に /chunkborder を打つと切り替わる）。
     * 反転後の状態（true=ON）を返す。
     */
    public boolean toggle(Player player, int radius, Mode mode) {
        UUID uuid = player.getUniqueId();
        DisplayState existing = displays.get(uuid);
        if (existing != null && existing.mode == mode) {
            displays.remove(uuid);
            forget(uuid);
            return false;
        }
        enable(player, radius, mode);
        return true;
    }

    /** ログアウト時のクリーンアップ専用。DBの永続状態はそのまま残す（再ログインで復元するため）。 */
    public void disable(UUID uuid) {
        displays.remove(uuid);
    }

    /** ユーザーが明示的にOFFを指示した時用。メモリとDBの両方から消す。 */
    public void disableAndForget(UUID uuid) {
        displays.remove(uuid);
        forget(uuid);
    }

    /**
     * ログイン時にDBの永続状態を読み込んで表示を復元する。非同期でDBを読み、
     * 見つかればプレイヤー自身のリージョンスレッドに戻ってから{@link #enable}する。
     */
    public void restoreOnJoin(Player player) {
        UUID uuid = player.getUniqueId();
        DatabaseManager.queryOneAsync(
                "SELECT mode, radius FROM land_border_displays WHERE uuid = ?",
                rs -> new DisplayState(rs.getInt("radius"), Mode.valueOf(rs.getString("mode"))),
                state -> {
                    if (state == null) {
                        return;
                    }
                    player.getScheduler().run(plugin, task -> {
                        if (player.isOnline()) {
                            enable(player, state.radius, state.mode);
                        }
                    }, null);
                },
                uuid.toString()
        );
    }

    private void persist(UUID uuid, Mode mode, int radius) {
        DatabaseManager.execute(
                "INSERT OR REPLACE INTO land_border_displays (uuid, mode, radius) VALUES (?, ?, ?)",
                uuid.toString(), mode.name(), radius
        );
    }

    private void forget(UUID uuid) {
        DatabaseManager.execute(
                "DELETE FROM land_border_displays WHERE uuid = ?",
                uuid.toString()
        );
    }

    /** 指定モードで現在ONかどうか（別モードでONの場合はfalse）。 */
    public boolean isEnabled(Player player, Mode mode) {
        DisplayState state = displays.get(player.getUniqueId());
        return state != null && state.mode == mode;
    }

    /** 現在キャッシュしている境界パーティクルの表示点数の合計（診断用）。表示OFFなら0。 */
    public int currentPointCount(UUID uuid) {
        DisplayState state = displays.get(uuid);
        if (state == null || state.cache == null) {
            return 0;
        }
        return state.cache.areas().stream().mapToInt(area -> area.points().size()).sum();
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
            if (land.isWorldDisabled(player.getWorld().getName())) {
                continue;
            }

            DisplayState state = entry.getValue();
            LandManager.ChunkKey center = LandManager.ChunkKey.of(player.getLocation());
            BorderCache cache = state.cache;
            boolean claimsChanged = state.mode == Mode.CLAIMED && cache != null && cache.claimsVersion() != land.claimsVersion();
            if (cache == null || !cache.center().equals(center) || cache.radius() != state.radius || claimsChanged) {
                cache = buildCache(center, state.radius, state.mode);
                state.cache = cache;
            }
            render(player, cache);
        }
    }

    private BorderCache buildCache(LandManager.ChunkKey center, int radius, Mode mode) {
        radius = Math.min(radius, plugin.getConfigManager().getInt("land.border-particle.max-radius", 64));
        LandManager land = plugin.getLandManager();

        if (mode == Mode.ALL_CHUNKS) {
            Set<BorderPoint> points = new HashSet<>();
            Set<BorderSegment> segments = new HashSet<>();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int minX = (center.chunkX() + dx) * CHUNK_SIZE;
                    int minZ = (center.chunkZ() + dz) * CHUNK_SIZE;
                    int maxX = minX + CHUNK_SIZE;
                    int maxZ = minZ + CHUNK_SIZE;
                    // 保護状態を問わず、範囲内の全チャンクの4辺をそのまま描画する
                    // （隣接チャンクとの共有辺はSetで自然に重複排除される）。
                    addHorizontalSide(points, segments, minX, maxX, minZ);
                    addHorizontalSide(points, segments, minX, maxX, maxZ);
                    addVerticalSide(points, segments, minX, minZ, maxZ);
                    addVerticalSide(points, segments, maxX, minZ, maxZ);
                }
            }
            AreaBorder area = new AreaBorder(resolveColor(), List.copyOf(points), List.copyOf(segments));
            return new BorderCache(center, radius, land.claimsVersion(), List.of(area));
        }

        // CLAIMEDモード: エリア（隣接claimの集合）ごとに境界点・色をまとめる。
        // 「別エリアと隣接している辺」は、相手が未claimでも別オーナーのclaimでも境界を引く
        // （＝自分の判定は「隣接チャンクのエリアIDが自分と違うか」だけで、isClaimed()は使わない）。
        Map<String, Set<BorderPoint>> pointsByArea = new HashMap<>();
        Map<String, Set<BorderSegment>> segmentsByArea = new HashMap<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                LandManager.ChunkKey chunk = new LandManager.ChunkKey(
                        center.world(), center.chunkX() + dx, center.chunkZ() + dz);
                String areaId = land.areaIdOf(chunk);
                if (areaId == null) {
                    continue;
                }

                Set<BorderPoint> points = pointsByArea.computeIfAbsent(areaId, id -> new HashSet<>());
                Set<BorderSegment> segments = segmentsByArea.computeIfAbsent(areaId, id -> new HashSet<>());

                int minX = chunk.chunkX() * CHUNK_SIZE;
                int minZ = chunk.chunkZ() * CHUNK_SIZE;
                int maxX = minX + CHUNK_SIZE;
                int maxZ = minZ + CHUNK_SIZE;

                if (!Objects.equals(areaId, land.areaIdOf(new LandManager.ChunkKey(center.world(), chunk.chunkX(), chunk.chunkZ() - 1)))) {
                    addHorizontalSide(points, segments, minX, maxX, minZ);
                }
                if (!Objects.equals(areaId, land.areaIdOf(new LandManager.ChunkKey(center.world(), chunk.chunkX(), chunk.chunkZ() + 1)))) {
                    addHorizontalSide(points, segments, minX, maxX, maxZ);
                }
                if (!Objects.equals(areaId, land.areaIdOf(new LandManager.ChunkKey(center.world(), chunk.chunkX() - 1, chunk.chunkZ())))) {
                    addVerticalSide(points, segments, minX, minZ, maxZ);
                }
                if (!Objects.equals(areaId, land.areaIdOf(new LandManager.ChunkKey(center.world(), chunk.chunkX() + 1, chunk.chunkZ())))) {
                    addVerticalSide(points, segments, maxX, minZ, maxZ);
                }
            }
        }

        List<AreaBorder> areas = new ArrayList<>();
        for (Map.Entry<String, Set<BorderPoint>> entry : pointsByArea.entrySet()) {
            String areaId = entry.getKey();
            areas.add(new AreaBorder(colorForArea(areaId), List.copyOf(entry.getValue()),
                    List.copyOf(segmentsByArea.getOrDefault(areaId, Set.of()))));
        }
        return new BorderCache(center, radius, land.claimsVersion(), areas);
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
     * エリアごとに色（DustOptions）を切り替えて描画する。
     */
    private void render(Player player, BorderCache cache) {
        World world = player.getWorld();
        Particle particle = resolveParticle();
        float size = (float) plugin.getConfigManager().getDouble("land.border-particle.size", 1.0);

        for (AreaBorder area : cache.areas()) {
            Map<BorderPoint, Integer> heights = new HashMap<>();
            for (BorderPoint point : area.points()) {
                heights.put(point, world.getHighestBlockYAt(point.x(), point.z()) + 1);
            }

            Particle.DustOptions dust = particle == Particle.DUST
                    ? new Particle.DustOptions(area.color(), size)
                    : null;
            for (Map.Entry<BorderPoint, Integer> entry : heights.entrySet()) {
                spawn(player, particle, dust, entry.getKey(), entry.getValue());
            }
            for (BorderSegment segment : area.segments()) {
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
    }

    /**
     * {@code point}の座標はチャンク境界そのもの（16の倍数）なので、ブロック中心に寄せる
     * +0.5オフセットは付けない（付けると境界線がブロック半分ズレて見える）。
     */
    private void spawn(Player player, Particle particle, Particle.DustOptions dust, BorderPoint point, int y) {
        if (dust != null) {
            player.spawnParticle(Particle.DUST, point.x(), y, point.z(),
                    1, 0, 0, 0, 0, dust);
        } else {
            player.spawnParticle(particle, point.x(), y, point.z(),
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

    /** ALL_CHUNKSモードや、resolveParticleがDUST以外の色を無視するケースで使う固定色。 */
    private Color resolveColor() {
        try {
            return ParticleUtil.parseColorOrFallback(
                    plugin.getConfigManager().getString("land.border-particle.color", "#55FF55"), Color.fromRGB(0x55FF55),
                    plugin.getLogger()::warning);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("land.border-particle.color の値が不正なため、デフォルト色にフォールバックします: " + e.getMessage());
            return Color.fromRGB(0x55FF55);
        }
    }

    /**
     * エリアIDから決定論的に色相を割り当てる（同じエリアは常に同じ色、別エリアは高確率で別の色）。
     * 彩度・明度は固定で見やすさを確保する。configでの色指定は不要。
     */
    private Color colorForArea(String areaId) {
        float hue = (Math.abs(areaId.hashCode()) % 360) / 360f;
        int rgb = java.awt.Color.HSBtoRGB(hue, 0.85f, 1.0f) & 0xFFFFFF;
        return Color.fromRGB(rgb);
    }
}
