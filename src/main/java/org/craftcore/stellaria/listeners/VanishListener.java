package org.craftcore.stellaria.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.craftcore.stellaria.StellariaCore;

/** vanish中のプレイヤーをMOBのAIターゲットから除外する。 */
public class VanishListener implements Listener {

    private final StellariaCore plugin;

    public VanishListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player target && plugin.getVanishManager().isVanished(target.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
