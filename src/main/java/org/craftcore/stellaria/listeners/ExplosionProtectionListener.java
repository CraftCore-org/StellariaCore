package org.craftcore.stellaria.listeners;

import org.bukkit.entity.Creeper;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Wither;
import org.bukkit.entity.WitherSkull;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.craftcore.stellaria.StellariaCore;

import java.util.List;

/**
 * config.yml の explosion-protection で指定したワールドに限り、Creeper/TNT（TNT付きトロッコ含む）/エンドクリスタル/ウィザーによる
 * 地形破壊を種別ごとに無効化する。land.protect.explosions（LandProtectionListener）はclaim単位の保護なので、
 * こちらはワールド全体・爆発原因の種別単位で効く、別軸の設定として独立させている。
 * ダメージ・ノックバックは残したいのでevent自体はキャンセルせず、blockListだけ空にする。
 */
public class ExplosionProtectionListener implements Listener {

    private final StellariaCore plugin;

    public ExplosionProtectionListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!isTargetWorld(event.getLocation().getWorld().getName())) {
            return;
        }

        Entity entity = event.getEntity();
        String flagPath;
        if (entity instanceof Creeper) {
            flagPath = "explosion-protection.creeper";
        } else if (entity instanceof TNTPrimed || entity instanceof ExplosiveMinecart) {
            flagPath = "explosion-protection.tnt";
        } else if (entity instanceof EnderCrystal) {
            flagPath = "explosion-protection.end-crystal";
        } else if (entity instanceof Wither || entity instanceof WitherSkull) {
            flagPath = "explosion-protection.wither";
        } else {
            return;
        }

        if (plugin.getConfigManager().getBoolean(flagPath, true)) {
            event.blockList().clear();
        }
    }

    /**
     * ウィザーはダメージを受けている間、爆発ではなくEntityChangeBlockEventで周囲のブロックを直接壊すため、
     * onEntityExplodeとは別に止める。
     */
    @EventHandler
    public void onWitherChangeBlock(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Wither)) {
            return;
        }
        if (!isTargetWorld(event.getBlock().getWorld().getName())) {
            return;
        }
        if (plugin.getConfigManager().getBoolean("explosion-protection.wither", true)) {
            event.setCancelled(true);
        }
    }

    private boolean isTargetWorld(String worldName) {
        List<String> worlds = plugin.getConfigManager().getStringList("explosion-protection.worlds", true);
        return worlds.contains(worldName);
    }
}
