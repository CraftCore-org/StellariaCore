package org.craftcore.stellaria.listeners;

import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.craftcore.stellaria.commands.tpa.TpaCore;

public class PlayerListener implements Listener {
    
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Handle player join event
    }
    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event){
        TpaCore.resetPlayerTeleportRequests(event.getPlayer());
    }
}
