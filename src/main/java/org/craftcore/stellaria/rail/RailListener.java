package org.craftcore.stellaria.rail;

import org.bukkit.Material;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.commands.RailCommand;
import org.craftcore.stellaria.gui.RailDepartGui;
import org.craftcore.stellaria.utils.WorldBlacklistUtil;

import java.util.List;
import java.util.Set;

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

    /** ツルハシ・オノ・シャベル・クワ・剣は、レール上でトロッコに乗った状態で右クリックしても
     *  通常バニラ動作（クワの耕地化等）が起きにくいため、行き先GUI起動用の右クリックとして許可する。 */
    private static final Set<String> GUI_TRIGGER_TOOL_SUFFIXES = Set.of("_PICKAXE", "_AXE", "_SHOVEL", "_HOE", "_SWORD");

    /**
     * /rail station add 実行後、右クリックでレールを選択して駅の位置を確定するためのフック。
     * それに該当しなければ、レール上のトロッコに乗車中の右クリック（素手 or 右クリックで何も
     * 起きないツール）を行き先GUI起動として扱う。乗車した瞬間に強制で開く方式は「ちょっとあれ」
     * という理由で見送り、プレイヤー自身の右クリック操作をトリガーにする。
     */
    @EventHandler(ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && RailCommand.handleStationRailClick(plugin, event.getPlayer(), event.getClickedBlock())) {
            event.setCancelled(true);
            return;
        }
        if ((event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR)
                && tryOpenDepartGuiOnInteract(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    private boolean tryOpenDepartGuiOnInteract(Player player) {
        if (!plugin.getRailConfig().isOpenGuiOnInteractEnabled()) {
            return false;
        }
        if (!(player.getVehicle() instanceof Minecart cart) || plugin.getRailManager().isRailMode(cart.getUniqueId())) {
            return false;
        }
        if (!player.hasPermission("stellaria.rail")) {
            return false;
        }
        if (WorldBlacklistUtil.isBlacklisted(plugin.getRailConfig().getDisabledWorlds(), player.getWorld().getName())) {
            return false;
        }
        if (!isInertForGuiTrigger(player.getInventory().getItemInMainHand())) {
            return false;
        }
        List<RailManager.ReachableStation> reachable = plugin.getRailManager().findReachableStations(cart);
        if (reachable.isEmpty()) {
            return false;
        }
        player.sendMessage(plugin.getConfigManager().getMessage("rail.auto_gui_prompt", player));
        new RailDepartGui(plugin, plugin.getRailCommand(), cart).open(player);
        return true;
    }

    private static boolean isInertForGuiTrigger(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return true;
        }
        String name = item.getType().name();
        for (String suffix : GUI_TRIGGER_TOOL_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
