package org.craftcore.stellaria.commands;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ItemTextUtil;
import org.jetbrains.annotations.NotNull;

/** /setlore と /setname で、メインハンドのアイテム表示を運営向けに編集する。 */
public final class SetItemTextCommand implements CommandExecutor {

    private final StellariaCore plugin;

    public SetItemTextCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String @NotNull [] args) {
        String commandName = command.getName();
        String permission = "stellaria." + commandName;
        if (!sender.hasPermission(permission)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("item-text.no_permission", null));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("item-text.must_be_player", null));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(plugin.getConfigManager().getUsageMessage("item-text." + commandName + "_usage", player));
            return true;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) {
            sender.sendMessage(plugin.getConfigManager().getMessage("item-text.no_item", player));
            return true;
        }

        ItemMeta meta = item.getItemMeta();
        String input = String.join(" ", args);
        if (commandName.equals("setlore")) {
            meta.lore(ItemTextUtil.loreComponents(input));
        } else {
            meta.displayName(ItemTextUtil.nameComponent(input));
        }
        item.setItemMeta(meta);
        sender.sendMessage(plugin.getConfigManager().getMessage("item-text." + commandName + "_updated", player));
        return true;
    }
}
