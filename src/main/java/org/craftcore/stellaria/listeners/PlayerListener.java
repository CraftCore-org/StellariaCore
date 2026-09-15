package org.craftcore.stellaria.listeners;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.commands.tpa.TpaCore;
import org.craftcore.stellaria.managers.ElevatorManager;

public class PlayerListener implements Listener {

    private final StellariaCore plugin;
    private final ElevatorManager elevatorManager;

    public PlayerListener(StellariaCore plugin, ElevatorManager elevatorManager) {
        this.plugin = plugin;
        this.elevatorManager = elevatorManager;
    }

    // タブリストのヘッダー/フッター更新は TabListManager が毎tick自動で行うようになったので、
    // Join時にここで手動更新する必要は無くなった（旧 TabList.updateAllPlayersTablist()）。
    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event){
        TpaCore.resetPlayerTeleportRequests(event.getPlayer(), plugin);
        plugin.getAfkManager().removePlayer(event.getPlayer().getUniqueId());
        plugin.getActionBarManager().removePlayer(event.getPlayer().getUniqueId());
        plugin.getBossBarManager().removePlayer(event.getPlayer().getUniqueId());
        plugin.getKikoriManager().removePlayer(event.getPlayer().getUniqueId());
        plugin.getLandManager().removeBypassState(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfigManager().getBoolean("afk.enabled", true)) {
            return;
        }
        // 位置が実際に変わった場合のみ活動とみなす（視点変更だけでは復帰させない）
        if (event.hasChangedPosition()) {
            plugin.getAfkManager().updateActivity(event.getPlayer());
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!plugin.getConfigManager().getBoolean("afk.enabled", true)) {
            return;
        }
        plugin.getAfkManager().updateActivity(event.getPlayer());
    }

    @EventHandler
    public void onPlayerJump(PlayerJumpEvent event){
        elevatorManager.PlayerElevatorMoveUp(event.getPlayer());
    }

    @EventHandler
    public void onPlayerSneak(PlayerToggleSneakEvent event){
        if (event.isSneaking()){
            elevatorManager.PlayerElevatorMoveDown(event.getPlayer());
        }
    }
}
