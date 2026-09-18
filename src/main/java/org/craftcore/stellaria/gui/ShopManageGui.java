package org.craftcore.stellaria.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.listeners.ShopListener;
import org.craftcore.stellaria.managers.ShopManager;
import org.craftcore.stellaria.utils.GuiItemUtil;

/** 所有者が仮想在庫とSELL資金を管理する画面。 */
public final class ShopManageGui extends Gui {
    private final StellariaCore plugin;
    private ShopManager.Shop shop;
    private final ShopListener listener;
    public ShopManageGui(StellariaCore plugin,ShopManager.Shop shop,ShopListener listener){super(27,Component.text("ショップ管理"));
        this.plugin=plugin;
        this.shop=shop;
        this.listener=listener;
        render();
    }
    private void render(){
        getInventory().clear();
        getInventory().setItem(13,display());
        getInventory().setItem(11,button(Material.CHEST,"ここにアイテムを入れて在庫を補充"));
        getInventory().setItem(15,button(Material.HOPPER,"在庫から1スタック引き出す"));
        if (shop.mode()==ShopManager.Mode.BUY) getInventory().setItem(18,button(Material.GREEN_DYE,"種別: 販売（切替）"));
        if (shop.mode()==ShopManager.Mode.SELL) getInventory().setItem(18,button(Material.RED_DYE,"種別: 買取（切替）"));
        getInventory().setItem(26,button(Material.GOLD_INGOT,"単価を変更"));
        if(shop.mode()==ShopManager.Mode.SELL){
            getInventory().setItem(20,button(Material.GOLD_INGOT,"資金を入金"));
            getInventory().setItem(24,button(Material.GOLD_NUGGET,"資金を出金"));
        }
        getInventory().setItem(22,button(Material.TNT,"ショップを削除"));
    }
    private ItemStack display(){

        ItemStack i=shop.item().clone();
        i.setAmount(Math.min(1,i.getMaxStackSize()));
        ItemMeta m=i.getItemMeta();
        m.displayName(GuiItemUtil.text("&%f在庫: &%e"+shop.stock()+"個"));
        m.lore(GuiItemUtil.loreFromStrings(java.util.List.of("&%f単価: &%e"+plugin.getEconomyManager().formatExact(shop.price()),"&%f資金プール: &%e"+plugin.getEconomyManager().formatExact(shop.funds()))));
        i.setItemMeta(m);
        return i;
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
        if(!(event.getWhoClicked() instanceof Player p))return;
        shop=plugin.getShopManager().find(shop.key());
        if(shop==null){p.closeInventory();
            return;
        }switch(event.getRawSlot()){
            case 11->{
                depositStock(p,event.getCursor());
                p.playSound(p, Sound.UI_BUTTON_CLICK,1,1);
            }
            case 15->{
                withdrawStock(p);
                p.playSound(p, Sound.UI_BUTTON_CLICK,1,1);
            }
            case 18->{
                listener.toggleShopMode(p,shop);
                p.playSound(p, Sound.UI_BUTTON_CLICK,1,1);
            }
            case 26->{
                listener.requestShopPrice(p,shop);
                p.playSound(p, Sound.UI_BUTTON_CLICK,1,1);
            }
            case 20->{
                if (shop.mode() == ShopManager.Mode.SELL) {
                    listener.requestFunds(p, shop, true);
                    p.playSound(p, Sound.UI_BUTTON_CLICK, 1, 1);
                }
            }
            case 24->{
                if (shop.mode() == ShopManager.Mode.SELL) {
                    listener.requestFunds(p, shop, false);
                    p.playSound(p, Sound.UI_BUTTON_CLICK, 1, 1);
                }
            }
            case 22->{
                new ShopRemoveGui(plugin, listener, shop).open(p);
            }default->{} }shop=plugin.getShopManager().find(shop.key());
        if(shop!=null)render();
    }
    private void depositStock(Player p,ItemStack cursor){
        if(cursor==null||cursor.getType().isAir()||!cursor.isSimilar(shop.item())){
            message(p,"shop.stock_item_mismatch");
            return;
        }int amount=cursor.getAmount();
        if(plugin.getShopManager().addStock(shop,amount)){p.setItemOnCursor(null);
            shop=plugin.getShopManager().find(shop.key());
            message(p,"shop.stock_deposited");
        }
    }
    @Override public void onDrag(InventoryDragEvent event) { event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !event.getRawSlots().equals(java.util.Set.of(13))) return;
        depositStock(player, event.getOldCursor());
        render();
    }
    private void withdrawStock(Player p){
        int amount=Math.min(shop.stock(),shop.item().getMaxStackSize());
        if(amount<1){
            message(p,"shop.trade_not_enough_stock");
            return;
        }
        ItemStack out=shop.item().clone();
        give(p, out, amount);
        plugin.getShopManager().addStock(shop,-amount);
        shop=plugin.getShopManager().find(shop.key());

        message(p,"shop.stock_withdrawn");
    }
    private static void give(Player p, ItemStack sample, int amount) {
        while (amount > 0) {
            ItemStack i = sample.clone();
            int n = Math.min(amount, i.getMaxStackSize());
            i.setAmount(n);
            p.getInventory().addItem(i).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
            amount -= n;
        }
    }

    private void message(Player p,String key){
        p.sendMessage(org.craftcore.stellaria.utils.ColorUtil.component(plugin.getConfigManager().getMessage(key,p)));
    }
}
