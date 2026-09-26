package org.craftcore.stellaria.listeners;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * 耕地の踏み荒らしを全ワールド・全域で禁止する。土地保護（LandProtectionListener）の対象外の場所でも
 * 畑が壊れて得をする人はいないため、保護の有無やtrust関係にかかわらず一律で止める。
 * プレイヤーはPlayerInteractEvent(PHYSICAL)、Mob・乗り物に乗ったプレイヤー等はEntityInteractEventで
 * 踏みつけが通知されるので、両方をキャンセルする。
 */
public class FarmlandTrampleListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onPlayerTrample(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL && isFarmland(event.getClickedBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityTrample(EntityInteractEvent event) {
        if (isFarmland(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    private boolean isFarmland(Block block) {
        return block != null && block.getType() == Material.FARMLAND;
    }
}
