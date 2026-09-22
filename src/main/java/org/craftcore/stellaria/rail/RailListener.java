package org.craftcore.stellaria.rail;

import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.commands.RailCommand;
import org.craftcore.stellaria.gui.RailDepartGui;
import org.craftcore.stellaria.utils.WorldBlacklistUtil;

import java.util.List;

/**
 * 高速鉄道セッションの毎tick更新とライフサイクル終了のきっかけとなるBukkitイベントを拾う。
 * 対象外のMinecart（RailManagerにセッションが無いもの）への影響はRailManager側の
 * sessions.containsKey()チェックに一任し、ここでは種別だけを見て素通しする。
 */
public class RailListener implements Listener {

    private final StellariaCore plugin;

    public RailListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (event.getVehicle() instanceof Minecart cart) {
            plugin.getRailManager().tickMovement(cart);
        }
    }

    @EventHandler
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (event.getVehicle() instanceof Minecart cart) {
            plugin.getRailManager().endSession(cart, RailManager.EndReason.DESTROYED);
        }
    }

    /** 高速モード中のトロッコが別のトロッコと衝突したら、バニラの押し合いに任せず即座に高速モードを解除する。 */
    @EventHandler
    public void onVehicleCollide(VehicleEntityCollisionEvent event) {
        if (event.getVehicle() instanceof Minecart cart && event.getEntity() instanceof Minecart) {
            plugin.getRailManager().endSession(cart, RailManager.EndReason.COLLISION);
        }
    }

    /**
     * 高速モード中に途中下車したら即座にセッションを終了する。VehicleExitEventの時点で
     * Bukkit側は既にexitしたプレイヤーをcart.getPassengers()から外しているため、
     * RailManager#endSessionのnotifyEndループ（現在の乗客だけを見る）ではこのプレイヤーの
     * アクションバーを消せない。ここで個別にクリアする。
     */
    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (!(event.getVehicle() instanceof Minecart cart) || !plugin.getRailManager().isRailMode(cart.getUniqueId())) {
            return;
        }
        if (event.getExited() instanceof Player player) {
            plugin.getActionBarManager().clearChannel(player, "rail");
        }
        plugin.getRailManager().endSession(cart, RailManager.EndReason.DISMOUNTED);
    }

    /**
     * 高速モードでないトロッコにプレイヤーが乗車したら、レール上かつ発車可能な駅があれば
     * 自動で目的地GUIを開く。/rail depart gui を知らない一般プレイヤーでも迷わず使えるようにするため。
     * 乗車直後の1tickはPassenger/位置の反映がまだ確定していないことがあるため1tick遅らせて判定する。
     */
    @EventHandler(ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (!plugin.getRailConfig().isAutoGuiOnMountEnabled()) {
            return;
        }
        if (!(event.getVehicle() instanceof Minecart cart) || !(event.getEntered() instanceof Player player)) {
            return;
        }
        if (!player.hasPermission("stellaria.rail") || plugin.getRailManager().isRailMode(cart.getUniqueId())) {
            return;
        }
        if (WorldBlacklistUtil.isBlacklisted(plugin.getRailConfig().getDisabledWorlds(), player.getWorld().getName())) {
            return;
        }
        player.getScheduler().runDelayed(plugin, task -> {
            if (!cart.isValid() || player.getVehicle() != cart || plugin.getRailManager().isRailMode(cart.getUniqueId())) {
                return;
            }
            List<RailManager.ReachableStation> reachable = plugin.getRailManager().findReachableStations(cart);
            if (reachable.isEmpty()) {
                return;
            }
            player.sendMessage(plugin.getConfigManager().getMessage("rail.auto_gui_prompt", player));
            new RailDepartGui(plugin, plugin.getRailCommand(), cart).open(player);
        }, null, 1L);
    }

    /** /rail station add 実行後、右クリックでレールを選択して駅の位置を確定するためのフック。 */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (RailCommand.handleStationRailClick(plugin, event.getPlayer(), event.getClickedBlock())) {
            event.setCancelled(true);
        }
    }
}
