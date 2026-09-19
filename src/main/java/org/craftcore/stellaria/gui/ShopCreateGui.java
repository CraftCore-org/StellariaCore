package org.craftcore.stellaria.gui;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player; import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.*; import org.bukkit.inventory.meta.ItemMeta;
import org.craftcore.stellaria.StellariaCore; import org.craftcore.stellaria.listeners.ShopListener; import org.craftcore.stellaria.utils.GuiItemUtil;
/** /shop create で対象チェストを選んだ後の設定画面。 */
public final class ShopCreateGui extends Gui {
    private final StellariaCore plugin;
    private final ShopListener listener;
    public ShopCreateGui(StellariaCore plugin,ShopListener listener){
        super(27,Component.text("ショップ作成"));
        this.plugin=plugin;
        this.listener=listener;
    }
    @Override public void open(Player p){
        render(p);
        p.playSound(p,Sound.BLOCK_AMETHYST_BLOCK_PLACE,1,1);
        super.open(p);
    }
    private void render(Player p){
        var d=listener.draft(p);
        getInventory().clear();
        if(d==null)return;
        String mode=d.mode()==org.craftcore.stellaria.managers.ShopManager.Mode.BUY?"販売":"買取";
        getInventory().setItem(11,button(d.mode()==org.craftcore.stellaria.managers.ShopManager.Mode.BUY?Material.LIME_DYE:Material.RED_DYE,"種別: "+mode+"（クリックで切替）"));getInventory().setItem(13,button(Material.GOLD_INGOT,d.price()>0?"単価: "+plugin.getEconomyManager().formatExact(d.price()):"単価を設定"));
        ItemStack item=d.item()==null?button(Material.CHEST,"販売アイテムを設定"):d.item().clone();
        if(d.item()!=null){
            item.setAmount(1);
            ItemMeta m=item.getItemMeta();
            m.lore(GuiItemUtil.loreFromStrings(java.util.List.of("&%eクリックして持ち替え")));
            item.setItemMeta(m);
        }
        getInventory().setItem(18, button(Material.BARRIER, "作成をキャンセル"));
        getInventory().setItem(22, button(Material.EMERALD, "作成を確定"));
    }
    private ItemStack button(Material m,String n){ItemStack i=GuiItemUtil.cleanIcon(m);
        ItemMeta meta=i.getItemMeta();meta.displayName(GuiItemUtil.text("&%f"+n));
        i.setItemMeta(meta);
        return i;
    }
    @Override public void onClick(InventoryClickEvent e){
        e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p))return;
        switch(e.getRawSlot()){
            case 11->{
                listener.toggleMode(p);
                p.playSound(p, Sound.UI_BUTTON_CLICK,1,1);
                render(p);
            }
            case 13->{
                listener.requestPrice(p);
                p.playSound(p, Sound.UI_BUTTON_CLICK,1,1);
            }
            case 15->{
                listener.requestItem(p);
                p.playSound(p, Sound.UI_BUTTON_CLICK,1,1);
            }
            case 18 -> {
                listener.cancelCreate(p);
                p.closeInventory();
                p.playSound(p, Sound.UI_BUTTON_CLICK, 1, 1);
            }
            case 22->{
                listener.confirmCreate(p);

            }
            default->{}
        }
    }

    @Override
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        if (listener.draft(player) == null) {
            return;
        }

        // 単価入力やアイテム設定のために意図的にGUIを閉じた場合はキャンセルしない
        if (listener.isCreateInputPending(player)) {
            return;
        }

        listener.cancelCreate(player);
    }
}
