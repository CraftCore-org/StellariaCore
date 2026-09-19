package org.craftcore.stellaria.commands;

import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.ShopManager;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.FormatUtil;
import org.craftcore.stellaria.utils.TabCompleteUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /shop list|info|remove。対象操作は5ブロック以内の注視先に限定する。
 */
public final class ShopCommand implements CommandExecutor, TabCompleter {
    private final StellariaCore plugin;

    public ShopCommand(StellariaCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.getConfigManager().getMessage("shop.must_be_player", null));
            return true;
        }
        if (args.length != 1) {
            msg(p, "shop.usage");
            return true;
        }
        if (args[0].equalsIgnoreCase("create")) {
            plugin.getShopListener().beginCreate(p);
            return true;
        }
        if (args[0].equalsIgnoreCase("list")) {
            List<ShopManager.Shop> all = plugin.getShopManager().list(p.getUniqueId());
            if (all.isEmpty()) {
                msg(p, "shop.list_empty");
                return true;
            }
            msg(p, "shop.list_header");
            for (ShopManager.Shop s : all)
                msg(p, "shop.list_entry", "%id%", Integer.toString(s.id()), "%world%", s.key().world(), "%x%", Integer.toString(s.key().x()), "%y%", Integer.toString(s.key().y()), "%z%", Integer.toString(s.key().z()), "%mode%", s.mode().name(), "%price%", plugin.getEconomyManager().formatExact(s.price()));
            return true;
        }
        Block target = p.getTargetBlockExact(5);
        ShopManager.Shop shop = target == null ? null : plugin.getShopManager().find(target);
        if (shop == null) {
            msg(p, "shop.not_found");
            return true;
        }
        if (!shop.owner().equals(p.getUniqueId()) && !plugin.getContainerLockManager().isBypassing(p)) {
            msg(p, "shop.not_owner");
            return true;
        }
        if (args[0].equalsIgnoreCase("info")) {
            msg(p, "shop.info", "%id%", Integer.toString(shop.id()), "%mode%", shop.mode().name(), "%price%", plugin.getEconomyManager().formatExact(shop.price()), "%stock%", Integer.toString(shop.stock()), "%funds%", plugin.getEconomyManager().formatExact(shop.funds()));
            return true;
        }
        if (args[0].equalsIgnoreCase("remove")) {
            plugin.getShopManager().remove(shop);
            msg(p, "shop.removed");
            return true;
        }
        msg(p, "shop.usage");
        return true;
    }

    private void msg(Player p, String key, String... pairs) {
        String s = plugin.getConfigManager().getMessage(key, p);
        for (int i = 0; i + 1 < pairs.length; i += 2) s = FormatUtil.replace(s, pairs[i], pairs[i + 1]);
        p.sendMessage(ColorUtil.component(s));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String a, @NotNull String[] args) {
        return args.length == 1 ? TabCompleteUtil.filterStartsWith(List.of("create", "list", "info", "remove"), args[0]) : List.of();
    }
}
