package org.craftcore.stellaria.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.LandBorderParticleManager;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /chunkborder [on|off] [半径] コマンド。土地保護の有無に関係なく、単純にチャンクの
 * 境界線を地形追従で表示する（/land border とは別モードで、同時には表示できない）。
 * 専用権限は無し（誰でも実行可）。
 */
public class ChunkBorderCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ON_OFF = List.of("on", "off");

    private final StellariaCore plugin;

    public ChunkBorderCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("chunkborder.must_be_player", null));
            return true;
        }

        LandBorderParticleManager manager = plugin.getLandBorderParticleManager();
        int defaultRadius = Math.max(1, plugin.getConfigManager().getInt("land.border-particle.toggle-radius-default", 3));

        if (args.length == 0) {
            boolean enabled = manager.toggle(player, defaultRadius, LandBorderParticleManager.Mode.ALL_CHUNKS);
            sendState(player, enabled, defaultRadius);
            return true;
        }

        String action = args[0].toLowerCase();
        if (action.equals("off") && args.length == 1) {
            manager.disableAndForget(player.getUniqueId());
            player.sendMessage(plugin.getConfigManager().getMessage("chunkborder.disabled", player));
            return true;
        }
        if (!action.equals("on") || args.length > 2) {
            player.sendMessage(plugin.getConfigManager().getUsageMessage("chunkborder.usage", player));
            return true;
        }

        int radius = defaultRadius;
        if (args.length == 2) {
            try {
                radius = Integer.parseInt(args[1]);
                if (radius < 1) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                player.sendMessage(plugin.getConfigManager().getUsageMessage("chunkborder.usage", player));
                return true;
            }
        }

        manager.enable(player, radius, LandBorderParticleManager.Mode.ALL_CHUNKS);
        sendState(player, true, radius);
        return true;
    }

    private void sendState(Player player, boolean enabled, int radius) {
        String message = plugin.getConfigManager().getMessage(
                enabled ? "chunkborder.enabled" : "chunkborder.disabled", player);
        player.sendMessage(FormatUtil.replace(message, "%radius%", String.valueOf(radius)));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.filterStartsWith(ON_OFF, args[0]);
        }
        return List.of();
    }
}
