package org.craftcore.stellaria.rail;

import org.bukkit.Color;
import org.bukkit.Location;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ParticleUtil;

/**
 * 駅の位置に常時、色付きパーティクルの目印を表示する（land.border-particleと同じ発想の演出）。
 * ただし駅は動かない固定点なので、land側のようにプレイヤーごとに境界を追従計算する必要はない
 * — Bukkitのspawnparticleは指定座標の近くにいるプレイヤーにだけ自動配信されるため、
 * 駅の数ぶんだけ描画すれば済む。config.ymlのrail.station-particle.enabledで丸ごとON/OFFでき、
 * /stellariareloadで（tickの中で毎回設定を見るだけなので）即座に反映される。
 */
public final class RailStationParticleManager {

    private final StellariaCore plugin;

    public RailStationParticleManager(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** GlobalRegionSchedulerから設定間隔ごとに呼ぶ。 */
    public void tick() {
        RailConfig config = plugin.getRailConfig();
        if (!config.isStationParticleEnabled()) {
            return;
        }
        Color color = ParticleUtil.parseColorOrFallback(
                config.getStationParticleColorHex(), Color.fromRGB(0x3399FF), plugin.getLogger()::warning);
        float size = (float) config.getStationParticleSize();
        double radius = config.getStationParticleRadius();

        for (RailStationManager.Station station : plugin.getRailStationManager().listAll()) {
            Location location = station.resolveLocation();
            if (location == null) {
                continue;
            }
            // 足元と少し上、2段のリングで「目印」らしく見せる
            ParticleUtil.spawnCircle(location.clone().add(0, 0.2, 0), radius, 24, color, size);
            ParticleUtil.spawnCircle(location.clone().add(0, 1.8, 0), radius, 24, color, size);
        }
    }
}
