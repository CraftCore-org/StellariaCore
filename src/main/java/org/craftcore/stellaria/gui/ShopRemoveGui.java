package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.listeners.ShopListener;
import org.craftcore.stellaria.managers.ShopManager;
import org.craftcore.stellaria.utils.GuiItemUtil;

public class ShopRemoveGui extends Gui{
    private final StellariaCore plugin;
    private final ShopListener listener;
    private ShopManager.Shop shop;
    public ShopRemoveGui(StellariaCore plugin,ShopListener listener,ShopManager.Shop shop){
        super(9, Component.text("ショップを削除しますか？"));
        this.plugin=plugin;
        this.listener=listener;
        this.shop = shop;
    }
    @Override public void open(Player p){
        render(p);
        p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BANJO,1,1);
        super.open(p);
    }
    private void render(Player p){
        getInventory().clear();
        getInventory().setItem(0,button(Material.BARRIER,"&%cキャンセル"));
        getInventory().setItem(4,button(Material.CHEST,"このショップを削除しますか？"));
        getInventory().setItem(8,button(Material.RED_STAINED_GLASS_PANE,"&%c削除する"));
    }
    private ItemStack button(Material m,String n){ItemStack i=GuiItemUtil.cleanIcon(m);
        ItemMeta meta=i.getItemMeta();meta.displayName(GuiItemUtil.text("&%f"+n));
        i.setItemMeta(meta);
        return i;
    }
    @Override public void onClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        shop=plugin.getShopManager().find(shop.key());
        if (slot == 0){
            player.closeInventory();
            player.playSound(player,Sound.UI_BUTTON_CLICK,1,1);
        }
        if (slot == 8) {
            plugin.getShopManager().remove(shop,player);
            player.closeInventory();
            message(player,"shop.removed");
            player.playSound(player,Sound.BLOCK_ANVIL_DESTROY,1,1.5f);
        }
    }
    private void message(Player p,String key){
        p.sendMessage(org.craftcore.stellaria.utils.ColorUtil.component(plugin.getConfigManager().getMessage(key,p)));
    }

}
