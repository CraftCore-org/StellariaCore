package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.commands.HomeCommand;
import org.craftcore.stellaria.commands.WarpCommand;
import org.craftcore.stellaria.utils.ColorUtil;
import org.craftcore.stellaria.utils.FormatUtil;

import java.util.List;

/**
 * サーバー内の各機能への入口をまとめた総合メニュー（/menu）。
 * それぞれの機能は既存のコマンド/GUIをそのまま呼び出すだけで、独自ロジックは持たない。
 */
public class MenuGui extends Gui {

    private static final int SLOT_PROFILE = 10;
    private static final int SLOT_DISCORD = 11;
    private static final int SLOT_VOTE = 12;
    private static final int SLOT_WORLD = 13;
    private static final int SLOT_WEATHER_VOTE = 14;
    private static final int SLOT_TIME_VOTE = 15;
    private static final int SLOT_ENDERCHEST = 16;
    private static final int SLOT_FEATURES = 28;
    private static final int SLOT_MAP = 29;
    private static final int SLOT_TPA_HELP = 30;
    private static final int SLOT_LAND_HELP = 31;
    private static final int SLOT_WARP = 32;
    private static final int SLOT_HOME = 33;
    private static final int SLOT_PROFILE_HINT = 53;

    private final StellariaCore plugin;

    public MenuGui(StellariaCore plugin, Player viewer) {
        super(54, messageComponent(plugin, "menu.title", viewer));
        this.plugin = plugin;
        populate(viewer);
    }

    private void populate(Player viewer) {
        setItem(SLOT_PROFILE, Material.PLAYER_HEAD, "menu.profile", viewer);
        setItem(SLOT_DISCORD, Material.PAPER, "menu.discord", viewer);
        setItem(SLOT_VOTE, Material.EMERALD, "menu.vote", viewer);
        setItem(SLOT_WORLD, Material.COMPASS, "menu.world", viewer);
        setItem(SLOT_WEATHER_VOTE, Material.LIGHTNING_ROD, "menu.weathervote", viewer);
        setItem(SLOT_TIME_VOTE, Material.CLOCK, "menu.timevote", viewer);
        setItem(SLOT_ENDERCHEST, Material.ENDER_CHEST, "menu.enderchest", viewer);
        setItem(SLOT_FEATURES, Material.REDSTONE, "menu.features", viewer);
        setItem(SLOT_MAP, Material.FILLED_MAP, "menu.map", viewer);
        setItem(SLOT_TPA_HELP, Material.ENDER_PEARL, "menu.tpa_help", viewer);
        setItem(SLOT_LAND_HELP, Material.SHIELD, "menu.land_help", viewer);
        setItem(SLOT_WARP, Material.LODESTONE, "menu.warp", viewer);
        setItem(SLOT_HOME, Material.RED_BED, "menu.home", viewer);

        ItemStack hint = new ItemStack(Material.BOOK);
        ItemMeta hintMeta = hint.getItemMeta();
        hintMeta.displayName(messageComponent(plugin, "menu.profile", viewer));
        hintMeta.lore(List.of(messageComponent(plugin, "menu.profile_hint", viewer)));
        hint.setItemMeta(hintMeta);
        getInventory().setItem(SLOT_PROFILE_HINT, hint);
    }

    private void setItem(int slot, Material material, String messageKey, Player viewer) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messageComponent(plugin, messageKey, viewer));
        item.setItemMeta(meta);
        getInventory().setItem(slot, item);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() >= getInventory().getSize()) {
            return;
        }

        switch (event.getRawSlot()) {
            case SLOT_PROFILE -> runCommand(player, "profile");
            case SLOT_DISCORD -> runCommand(player, "discord");
            case SLOT_VOTE -> runCommand(player, "vote");
            case SLOT_WORLD -> new WorldSelectGui(plugin, this).open(player);
            case SLOT_WEATHER_VOTE -> new WeatherVoteGui(plugin, player).open(player);
            case SLOT_TIME_VOTE -> new TimeVoteGui(plugin, player).open(player);
            case SLOT_ENDERCHEST -> {
                player.closeInventory();
                player.openInventory(player.getEnderChest());
            }
            case SLOT_FEATURES -> new FeaturesGui(plugin, plugin.getFeatures(), player, this).open(player);
            case SLOT_MAP -> runCommand(player, "map");
            case SLOT_TPA_HELP -> sendHelpLines(player, "menu.tpa_help_lines");
            case SLOT_LAND_HELP -> runCommand(player, "land help");
            case SLOT_WARP -> new WarpSelectGui(plugin, new WarpCommand(plugin), this).open(player);
            case SLOT_HOME -> new HomeSelectGui(plugin, new HomeCommand(plugin), player, this).open(player);
            default -> {
            }
        }
    }

    private void runCommand(Player player, String command) {
        player.closeInventory();
        player.performCommand(command);
    }

    private void sendHelpLines(Player player, String messageListKey) {
        player.closeInventory();
        for (String line : plugin.getConfigManager().getMessageList(messageListKey)) {
            player.sendMessage(FormatUtil.text(player, line));
        }
    }

    private static Component messageComponent(StellariaCore plugin, String path, Player player) {
        return ColorUtil.component(plugin.getConfigManager().getMessage(path, player));
    }
}
