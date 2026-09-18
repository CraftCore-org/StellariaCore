package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.managers.ShopManager;
import org.craftcore.stellaria.utils.GuiItemUtil;

import java.util.List;

/** 購入・売却を確定前に確認する画面。 */
public final class ShopTransactionGui extends Gui {
    private final StellariaCore plugin;
    private final ShopManager.Shop shop;
    private int quantity=1;
    public ShopTransactionGui(StellariaCore plugin, ShopManager.Shop shop) {
        super(27, Component.text("ショップ取引"));
        this.plugin=plugin;
        this.shop=shop;
        render();
    }
    private void render(){
        getInventory().clear();
        getInventory().setItem(9,button(Material.RED_DYE,quantity<shop.item().getMaxStackSize()?"-64個":"-4スタック"));
        getInventory().setItem(10,button(Material.RED_DYE,quantity<shop.item().getMaxStackSize()?"-16個":"-2スタック"));
        getInventory().setItem(11,button(Material.RED_DYE,quantity<shop.item().getMaxStackSize()?"-1個":"-1スタック"));
        getInventory().setItem(15,button(Material.LIME_DYE,quantity<shop.item().getMaxStackSize()?"+1個":"+1スタック"));
        getInventory().setItem(16,button(Material.LIME_DYE,quantity<shop.item().getMaxStackSize()?"+16個":"+2スタック"));
        getInventory().setItem(17,button(Material.LIME_DYE,quantity<shop.item().getMaxStackSize()?"+64個":"+4スタック"));
        getInventory().setItem(13,preview()); getInventory().setItem(21,button(Material.EMERALD,"確定"));
        getInventory().setItem(23,button(Material.BARRIER,"キャンセル"));
    }
    private ItemStack preview(){
        ItemStack item=shop.item().clone();
        int max=item.getMaxStackSize();
        item.setAmount(Math.min(quantity,max));
        ItemMeta meta=item.getItemMeta();
        meta.displayName(GuiItemUtil.text(shop.mode()==ShopManager.Mode.BUY?"購入するアイテム":"売却するアイテム"));
        meta.lore(GuiItemUtil.loreFromStrings(List.of("&%f数量: &%e"+quantity+"個","&%f単価: &%e"+plugin.getEconomyManager().formatExact(shop.price()),"&%f合計: &%e"+plugin.getEconomyManager().formatExact(shop.price()*quantity))));
        if(quantity>max){
            meta.addEnchant(Enchantment.UNBREAKING,1,true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        item.setItemMeta(meta);
        return item;
    }
    private ItemStack button(Material material,String name){
        ItemStack i=GuiItemUtil.cleanIcon(material);
        ItemMeta m=i.getItemMeta();
        m.displayName(GuiItemUtil.text("&%f"+name));
        i.setItemMeta(m);
        return i;
    }
    @Override public void onClick(InventoryClickEvent event){
        event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player))return;
        int slot=event.getRawSlot();
        int max=shop.item().getMaxStackSize();
        if(slot==9){
            quantity=Math.max(1,quantity-(quantity<=max?64:max * 4));
            player.playSound(player,Sound.UI_BUTTON_CLICK,1,1);
            render();
        }
        else if(slot==10){
            quantity=Math.max(1,quantity-(quantity<=max?16:max * 2));
            player.playSound(player,Sound.UI_BUTTON_CLICK,1,1);
            render();
        }
        else if(slot==11){
            quantity=Math.max(1,quantity-(quantity<=max?1:max));
            player.playSound(player,Sound.UI_BUTTON_CLICK,1,1);
            render();
        }
        else if(slot==15){
            quantity=Math.min(max*16,quantity+(quantity<max?1:max));
            player.playSound(player,Sound.UI_BUTTON_CLICK,1,1);
            render();
        }
        else if(slot==16) {
            if (quantity == 1) quantity = Math.min(max * 16, quantity + (quantity < max ? 15 : max * 2));
            else quantity = Math.min(max * 16, quantity + (quantity < max ? 16 : max * 2));
            player.playSound(player,Sound.UI_BUTTON_CLICK,1,1);
            render();
        } else if(slot==17) {
            if (quantity == 1) quantity = Math.min(max * 16, quantity + (quantity < max ? 63 : max * 4));
            else quantity = Math.min(max * 16, quantity + (quantity < max ? 64 : max * 4));
            player.playSound(player,Sound.UI_BUTTON_CLICK,1,1);
            render();
        } else if(slot==21){
            ShopManager.TradeResult result=plugin.getShopManager().trade(player,plugin.getShopManager().find(shop.key()),quantity);
            if(result==ShopManager.TradeResult.SUCCESS){
                player.closeInventory();
                message(player,"shop.trade_success");
                player.playSound(player,Sound.ENTITY_EXPERIENCE_ORB_PICKUP,1,1);
            }else {
                message(player,"shop.trade_"+result.name().toLowerCase());
                player.playSound(player,Sound.BLOCK_NOTE_BLOCK_BASS,1,1);
            }}
        else if(slot==23){
            player.closeInventory();
            player.playSound(player,Sound.UI_BUTTON_CLICK,1,1);
        }
    }
    private void message(Player p,String key){
        p.sendMessage(org.craftcore.stellaria.utils.ColorUtil.component(plugin.getConfigManager().getMessage(key,p)));
    }
}
