package org.craftcore.stellaria.rail;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.DatabaseManager;
import org.craftcore.stellaria.utils.ParticleUtil;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 駅の位置に色付きパーティクルの目印を表示する（land.border-particleと同じ発想の演出）。
 * ただし表示は完全にプレイヤーごとのオプトイン（既定OFF）— config.ymlのrail.station-particle.enabled
 * は機能全体のON/OFFスイッチで、実際に見えるかどうかは各プレイヤーが /rail particle でON/OFFした
 * 状態（rail_station_particle_prefsに永続化、行が存在する=ON）による。駅は動かない固定点なので、
 * land側のような境界の追従計算は不要 — 表示ON中のプレイヤーだけを毎tick回り、近くの駅の分だけ
 * そのプレイヤー個別にspawnParticleする。
 */
public final class RailStationParticleManager {

    private final StellariaCore plugin;
    private final Set<UUID> enabled = ConcurrentHashMap.newKeySet();

    public RailStationParticleManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    public boolean isEnabled(Player player) {
        return enabled.contains(player.getUniqueId());
    }

    /** /rail particle から呼ぶ。反転後の状態（true=ON）を返す。DBにも永続化する。 */
    public boolean toggle(Player player) {
        UUID uuid = player.getUniqueId();
        if (enabled.remove(uuid)) {
            DatabaseManager.execute("DELETE FROM rail_station_particle_prefs WHERE uuid = ?", uuid.toString());
            return false;
        }
        enabled.add(uuid);
        DatabaseManager.execute("INSERT OR REPLACE INTO rail_station_particle_prefs (uuid) VALUES (?)", uuid.toString());
        return true;
    }

    /** ログイン時にPlayerJoinListenerから呼ぶ。DBの永続状態を読み込んで表示を復元する。 */
    public void restoreOnJoin(Player player) {
        UUID uuid = player.getUniqueId();
        DatabaseManager.queryOneAsync(
                "SELECT uuid FROM rail_station_particle_prefs WHERE uuid = ?",
                rs -> rs.getString("uuid"),
                found -> {
                    if (found != null) {
                        enabled.add(uuid);
                    }
                },
                uuid.toString()
        );
    }

    /** GlobalRegionSchedulerから設定間隔ごとに呼ぶ。 */
    public void tick() {
        RailConfig config = plugin.getRailConfig();
        if (!config.isStationParticleEnabled() || enabled.isEmpty()) {
            return;
        }
        var stations = plugin.getRailStationManager().listAll();
        if (stations.isEmpty()) {
            return;
        }
        Color color = ParticleUtil.parseColorOrFallback(
                config.getStationParticleColorHex(), Color.fromRGB(0x3399FF), plugin.getLogger()::warning);
        Particle.DustOptions dust = new Particle.DustOptions(color, (float) config.getStationParticleSize());
        double radius = config.getStationParticleRadius();
        double viewDistanceSq = config.getStationParticleViewDistance() * config.getStationParticleViewDistance();

        for (UUID uuid : enabled) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            for (RailStationManager.Station station : stations) {
                Location location = station.resolveLocation();
                if (location == null || !location.getWorld().equals(player.getWorld())
                        || location.distanceSquared(player.getLocation()) > viewDistanceSq) {
                    continue;
                }
                // 足元と少し上、2段のリングで「目印」らしく見せる
                spawnRing(player, location.clone().add(0, 0.2, 0), radius, dust);
                spawnRing(player, location.clone().add(0, 1.8, 0), radius, dust);
            }
        }
    }

    /** ParticleUtilのspawnCircleはワールド全体へ配信するため、プレイヤー個別配信用にここで直接描く。 */
    private void spawnRing(Player player, Location center, double radius, Particle.DustOptions dust) {
        int points = 24;
        double step = (Math.PI * 2) / points;
        for (int i = 0; i < points; i++) {
            double angle = step * i;
            double x = center.getX() + radius * Math.cos(angle);
            double z = center.getZ() + radius * Math.sin(angle);
            player.spawnParticle(Particle.DUST, x, center.getY(), z, 1, 0, 0, 0, 0, dust);
        }
    }
}
