package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.gui.AdvancementGui;
import org.craftcore.stellaria.managers.AdvancementManager;
import org.craftcore.stellaria.utils.AdvancementDefinitions;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** /advancements: 独自進捗の GUI を開く。admin サブコマンドで進捗の付与・取り消しを行う。 */
public class AdvancementCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "stellaria.advancements.admin";

    private final StellariaCore plugin;

    public AdvancementCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        AdvancementManager manager = plugin.getAdvancementManager();
        if (!manager.isEnabled()) {
            sender.sendMessage(plugin.getConfigManager().getMessage("advancements.disabled", null));
            return true;
        }
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.getConfigManager().getMessage("advancements.must_be_player", null));
                return true;
            }
            new AdvancementGui(plugin, player, null).open(player);
            return true;
        }
        if (!args[0].equalsIgnoreCase("admin") || args.length != 4
                || !(args[2].equalsIgnoreCase("grant") || args[2].equalsIgnoreCase("revoke"))) {
            sender.sendMessage(plugin.getConfigManager().getUsageMessage("advancements.usage", null));
            return true;
        }
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("advancements.no_permission", null));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(plugin.getConfigManager().getMessage("advancements.player_not_found", null));
            return true;
        }
        String id = args[3];
        if (manager.getParsed().find(id).isEmpty()) {
            sender.sendMessage(reply("advancements.unknown_advancement", target, id));
            return true;
        }
        boolean grant = args[2].equalsIgnoreCase("grant");
        boolean changed = grant ? manager.grant(target, id) : manager.revoke(target, id);
        String key = grant
                ? (changed ? "advancements.granted" : "advancements.already_completed")
                : (changed ? "advancements.revoked" : "advancements.not_completed");
        sender.sendMessage(reply(key, target, id));
        return true;
    }

    private String reply(String path, Player target, String id) {
        return FormatUtil.replace(plugin.getConfigManager().getMessage(path, target), "%id%", id);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return List.of();
        }
        return switch (args.length) {
            case 1 -> TabCompleteUtil.filterStartsWith(List.of("admin"), args[0]);
            case 2 -> TabCompleteUtil.filterStartsWith(
                    Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
            case 3 -> TabCompleteUtil.filterStartsWith(List.of("grant", "revoke"), args[2]);
            case 4 -> TabCompleteUtil.filterStartsWith(plugin.getAdvancementManager().getParsed().definitions().stream()
                    .map(AdvancementDefinitions.Definition::id).toList(), args[3]);
            default -> List.of();
        };
    }
}
