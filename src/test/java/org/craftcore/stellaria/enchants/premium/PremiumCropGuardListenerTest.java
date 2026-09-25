package org.craftcore.stellaria.enchants.premium;

import io.papermc.paper.event.player.PlayerPurchaseEvent;
import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.view.MerchantView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.craftcore.stellaria.managers.ConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PremiumCropGuardListenerTest {

    private PremiumCropGuardListener listener;

    @BeforeEach
    void setUp() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.namespace()).thenReturn("stellariacore");
        ConfigManager config = mock(ConfigManager.class);
        PremiumCrops crops = new PremiumCrops(plugin, config);
        listener = new PremiumCropGuardListener(config, crops, mock(PremiumCropRecipes.class));
    }

    private static ItemStack crop(Material material, boolean premium) {
        ItemStack item = mock(ItemStack.class);
        PersistentDataContainerView pdc = mock(PersistentDataContainerView.class);
        when(item.getType()).thenReturn(material);
        when(item.isEmpty()).thenReturn(false);
        when(item.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.BYTE))).thenReturn(premium);
        return item;
    }

    private static PlayerPurchaseEvent purchaseWithPayment(ItemStack first, ItemStack second) {
        PlayerPurchaseEvent event = mock(PlayerPurchaseEvent.class);
        Player player = mock(Player.class);
        MerchantView view = mock(MerchantView.class);
        MerchantInventory merchant = mock(MerchantInventory.class);
        when(event.getPlayer()).thenReturn(player);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(merchant);
        when(merchant.getItem(0)).thenReturn(first);
        when(merchant.getItem(1)).thenReturn(second);
        return event;
    }

    @Test
    void tradeIsCancelledWhenAutoFilledPaymentContainsPremiumCrops() {
        PlayerPurchaseEvent event = purchaseWithPayment(crop(Material.WHEAT, true), null);
        listener.onPurchase(event);
        verify(event).setCancelled(true);
    }

    @Test
    void tradeWithNormalCropsIsAllowed() {
        PlayerPurchaseEvent event = purchaseWithPayment(crop(Material.WHEAT, false), crop(Material.WHEAT, false));
        listener.onPurchase(event);
        verify(event, never()).setCancelled(true);
    }

    @Test
    void hopperCannotFeedPremiumNetherWartIntoABrewingStand() {
        InventoryMoveItemEvent event = mock(InventoryMoveItemEvent.class);
        BrewerInventory brewer = mock(BrewerInventory.class);
        when(event.getDestination()).thenReturn(brewer);
        ItemStack wart = crop(Material.NETHER_WART, true);
        when(event.getItem()).thenReturn(wart);
        listener.onHopperMove(event);
        verify(event).setCancelled(true);
    }

    @Test
    void brewingWithPremiumNetherWartIsCancelled() {
        BrewEvent event = mock(BrewEvent.class);
        BrewerInventory contents = mock(BrewerInventory.class);
        ItemStack wart = crop(Material.NETHER_WART, true);
        when(event.getContents()).thenReturn(contents);
        when(contents.getIngredient()).thenReturn(wart);
        listener.onBrew(event);
        verify(event).setCancelled(true);
    }

    @Test
    void premiumCropCannotBeClickedIntoABrewingStand() {
        org.bukkit.event.inventory.InventoryClickEvent event = mock(org.bukkit.event.inventory.InventoryClickEvent.class);
        org.bukkit.inventory.InventoryView view = mock(org.bukkit.inventory.InventoryView.class);
        BrewerInventory brewer = mock(BrewerInventory.class);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(event.getView()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(brewer);
        when(event.getWhoClicked()).thenReturn(player);
        when(player.getInventory()).thenReturn(inventory);
        when(event.getHotbarButton()).thenReturn(-1);
        ItemStack wart = crop(Material.NETHER_WART, true);
        when(event.getCursor()).thenReturn(wart);
        listener.onContainerClick(event);
        verify(event).setCancelled(true);
    }
}
