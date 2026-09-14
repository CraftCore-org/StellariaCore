package org.craftcore.stellaria.commands;

import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * /heal コマンド。引数なしなら自分、引数ありなら指定プレイヤー（要 stellaria.heal.others）を
 * 全回復させる（HP・満腹度・隠し満腹度・炎消火）。
 */
public class HealCommand implements CommandExecutor, TabCompleter {

    private final StellariaCore plugin;

    public HealCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String @NotNull [] args) {
        if (!sender.hasPermission("stellaria.heal")) {
            sender.sendMessage(plugin.getConfigManager().getMessage("heal.no_permission", null));
            return true;
        }

        Player target;
        if (args.length == 0) {
            if (!(sender instanceof Player self)) {
                sender.sendMessage(plugin.getConfigManager().getMessage("heal.must_be_player", null));
                return true;
            }
            target = self;
        } else {
            if (!sender.hasPermission("stellaria.heal.others")) {
                sender.sendMessage(plugin.getConfigManager().getMessage("heal.no_permission", null));
                return true;
            }
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage(plugin.getConfigManager().getMessage("heal.player_not_found", Bukkit.getOfflinePlayer(args[0])));
                return true;
            }
        }

        target.setHealth(Objects.requireNonNull(target.getAttribute(Attribute.MAX_HEALTH)).getValue());
        target.setFoodLevel(20);
        target.setSaturation(20f);
        target.setFireTicks(0);

        if (target.equals(sender)) {
            target.sendMessage(plugin.getConfigManager().getMessage("heal.self", target));
        } else {
            sender.sendMessage(plugin.getConfigManager().getMessage("heal.other_sender", target));
            target.sendMessage(plugin.getConfigManager().getMessage("heal.other_receiver", target));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String @NotNull [] args) {
        if (args.length == 1) {
            return TabCompleteUtil.onlinePlayerNames(args[0]);
        }
        return List.of();
    }
}
