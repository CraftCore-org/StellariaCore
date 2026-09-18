package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.commands.ProfileCommand;
import org.craftcore.stellaria.utils.GuiItemUtil;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 自分のプロフィールと、他プレイヤーの確認方法を表示する画面。 */
public final class ProfileGui extends Gui {

    private static final int PROFILE_SLOT = 11;
    private static final int HINT_SLOT = 15;

    private final StellariaCore plugin;

    public ProfileGui(StellariaCore plugin, Player player) {
        this(plugin, player, null);
    }

    /** メニュー画面などから開く場合に親画面を渡す。 */
    public ProfileGui(StellariaCore plugin, Player player, @Nullable Gui parent) {
        super(27, messageComponent(plugin, "profile.gui_title", player), parent);
        this.plugin = plugin;
        populate(player);
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() >= getInventory().getSize()) {
            return;
        }
        handleBackButton(event, player);
    }

    private void populate(Player player) {
        ItemStack profile = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta profileMeta = (SkullMeta) profile.getItemMeta();
        profileMeta.setOwningPlayer(player);
        profileMeta.displayName(messageComponent(plugin, "profile.gui_self", player));
        profileMeta.lore(ProfileCommand.buildProfileInfoLines(plugin, player, player).stream()
                .map(line -> GuiItemUtil.text(plugin.getPlaceholderManager().resolve(line, player)))
                .toList());
        profile.setItemMeta(profileMeta);
        getInventory().setItem(PROFILE_SLOT, profile);

        ItemStack hint = new ItemStack(Material.BOOK);
        ItemMeta hintMeta = hint.getItemMeta();
        hintMeta.displayName(messageComponent(plugin, "profile.gui_hint_title", player));
        hintMeta.lore(List.of(messageComponent(plugin, "profile.gui_hint", player)));
        hint.setItemMeta(hintMeta);
        getInventory().setItem(HINT_SLOT, hint);
    }

    private static Component messageComponent(StellariaCore plugin, String path, Player player) {
        return GuiItemUtil.text(plugin.getConfigManager().getMessage(path, player));
    }
}
