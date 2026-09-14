package org.craftcore.stellaria.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.MuteManager;
import org.craftcore.stellaria.utils.DurationParser;

import java.util.List;

/**
 * レベル3ミュート中のプレイヤーが mute.level3-command-blacklist（config.yml）記載の
 * コマンドを実行しようとしたらキャンセルする。マッチングは入力されたコマンドラベル
 * （例: "/tell ..." なら "tell"）で行うため、エイリアスもすべてブラックリストに列挙する必要がある。
 */
public class MuteCommandBlockListener implements Listener {

    private final StellariaCore plugin;

    public MuteCommandBlockListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        MuteManager.MuteRecord record = plugin.getMuteManager()
                .getRestrictingRecord(player.getUniqueId(), MuteManager.MuteScope.COMMAND);
        if (record == null) {
            return;
        }

        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase();
        List<String> blacklist = plugin.getConfigManager().getStringList("mute.level3-command-blacklist");
        boolean blocked = blacklist.stream().anyMatch(label::equalsIgnoreCase);
        if (!blocked) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(plugin.getConfigManager().getMessage("mute.blocked_command", player)
                .replace("%remaining%", DurationParser.formatRemaining(record.expiresAt()))
                .replace("%reason%", record.reason()));
    }
}
