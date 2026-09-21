package org.craftcore.stellaria.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.WorldResetSafetyUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ワールド自動リセットに関わるプレイヤー操作を扱う独立リスナー。
 * PlayerListener（join/quit・AFK・elevator）とは守備範囲が異なるため分離している。
 *
 * PlayerTeleportEventはコマンド・ネザーポータル・他プラグイン経由など原因を問わず発火するため、
 * これをフックすることで再入場ルートを個別に塞ぐ必要がない。ただし、ログイン時の
 * リスポーン地点配置（ワールドが再生成された後の初回配置等）はテレポートとして扱われず
 * このイベントの対象外になる点は既知の制約として残す。
 */
public class WorldResetListener implements Listener {

    // ロックアウト窓の再入場確認待ち状態（プレイヤー1人につき1件）
    private static final Map<UUID, WorldResetSafetyUtil.PendingConfirm> PENDING_CONFIRM = new HashMap<>();

    private final StellariaCore plugin;

    public WorldResetListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null || event.getTo().getWorld() == null) {
            return;
        }
        String targetWorld = event.getTo().getWorld().getName();
        Player player = event.getPlayer();

        if (plugin.getWorldResetManager().isLockedOut(targetWorld)) {
            WorldResetSafetyUtil.Result result = WorldResetSafetyUtil.attempt(player, targetWorld, PENDING_CONFIRM);
            if (result == WorldResetSafetyUtil.Result.WARNED) {
                event.setCancelled(true);
                player.sendMessage(plugin.getConfigManager().getMessage("world-reset.lockout_warning", player));
            }
            return;
        }

        if (plugin.getWorldResetManager().isResetTarget(targetWorld)) {
            String message = plugin.getConfigManager().getMessage("world-reset.next_reset_notice", player);
            message = FormatUtil.replace(message, "%next_reset%", plugin.getWorldResetManager().formattedNextResetTime());
            player.sendMessage(message);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PENDING_CONFIRM.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String commandLabel = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase();
        if (!commandLabel.equals("sethome") && !commandLabel.equals("setwarp")) {
            return;
        }
        Player player = event.getPlayer();
        if (plugin.getWorldResetManager().isResetTarget(player.getWorld().getName())) {
            player.sendMessage(plugin.getConfigManager().getMessage("world-reset.sethome_warning", player));
        }
    }
}
