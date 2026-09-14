package org.craftcore.stellaria.listeners;

import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.craftcore.stellaria.commands.tpa.TpaCore;

public class PlayerListener implements Listener {

    // タブリストのヘッダー/フッター更新は TabListManager が毎tick自動で行うようになったので、
    // Join時にここで手動更新する必要は無くなった（旧 TabList.updateAllPlayersTablist()）。
    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event){
        TpaCore.resetPlayerTeleportRequests(event.getPlayer());
    }
}
