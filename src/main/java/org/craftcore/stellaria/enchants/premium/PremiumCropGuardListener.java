package org.craftcore.stellaria.enchants.premium;

import io.papermc.paper.event.player.PlayerPurchaseEvent;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Crafter;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.Recipe;
import org.craftcore.stellaria.managers.ConfigManager;

import java.util.EnumSet;
import java.util.Set;

/**
 * 上質作物を変換レシピ以外で使えないようにする。上質作物は見た目がバニラの作物と同じ Material のため、
 * 何もしないとレシピの材料・植え付け・食事・調理・コンポスター・動物や村人への受け渡しに 1 個分として使えてしまう。
 */
public final class PremiumCropGuardListener implements Listener {

    private static final Set<Material> BLOCKED_INTERACT_TARGETS = EnumSet.of(
            Material.COMPOSTER, Material.CAMPFIRE, Material.SOUL_CAMPFIRE);

    private final ConfigManager config;
    private final PremiumCrops crops;
    private final PremiumCropRecipes recipes;

    public PremiumCropGuardListener(ConfigManager config, PremiumCrops crops, PremiumCropRecipes recipes) {
        this.config = config;
        this.crops = crops;
        this.recipes = recipes;
    }

    // ------------------------------------------------------------------
    // クラフト（作業台・クラフター）
    // ------------------------------------------------------------------

    /** クラフトの判定結果。blocked=true なら結果を消す。replacement が非 null なら結果をそれに差し替える。 */
    private record CraftOutcome(boolean blocked, ItemStack replacement) {
        static final CraftOutcome UNCHANGED = new CraftOutcome(false, null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (recipe == null) {
            return;
        }
        CraftOutcome outcome = judge(event.getInventory().getMatrix(), recipe);
        if (outcome.blocked()) {
            event.getInventory().setResult(null);
        } else if (outcome.replacement() != null) {
            event.getInventory().setResult(outcome.replacement());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCrafterCraft(CrafterCraftEvent event) {
        if (!(event.getBlock().getState(false) instanceof Crafter crafter)) {
            return;
        }
        CraftOutcome outcome = judge(crafter.getInventory().getContents(), event.getRecipe());
        if (outcome.blocked()) {
            event.setCancelled(true);
        } else if (outcome.replacement() != null) {
            event.setResult(outcome.replacement());
        }
    }

    private CraftOutcome judge(ItemStack[] matrix, Recipe recipe) {
        int premium = 0;
        int nonEmpty = 0;
        ItemStack lone = null;
        for (ItemStack item : matrix) {
            if (item == null || item.isEmpty()) {
                continue;
            }
            nonEmpty++;
            lone = item;
            if (crops.isPremium(item)) {
                premium++;
            }
        }
        NamespacedKey key = recipe instanceof Keyed keyed ? keyed.getKey() : null;
        return switch (PremiumCraftRules.judge(premium, nonEmpty)) {
            case BLOCK -> new CraftOutcome(true, null);
            case CONVERT_TO_NORMAL -> new CraftOutcome(false,
                    new ItemStack(lone.getType(), PremiumCrops.CONVERSION_RATIO));
            case ALLOW -> {
                if (key != null && recipes.isToNormal(key)) {
                    // 通常作物 1 個が逆変換レシピに乗った（1 個 → 3 個の増殖になるため消す）
                    yield new CraftOutcome(true, null);
                }
                if (key != null && recipes.isToPremium(key)) {
                    // 登録時のアイテムではなく、今の messages.yml の名前で作り直す
                    yield new CraftOutcome(false, crops.create(recipe.getResult().getType(), 1));
                }
                yield CraftOutcome.UNCHANGED;
            }
        };
    }

    // ------------------------------------------------------------------
    // 植え付け・食事・調理・コンポスター
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (crops.isPremium(event.getItemInHand())) {
            deny(event, event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (crops.isPremium(event.getItem())) {
            deny(event, event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCook(BlockCookEvent event) {
        if (crops.isPremium(event.getSource())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteractBlock(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (crops.isPremium(event.getItem())
                && BLOCKED_INTERACT_TARGETS.contains(event.getClickedBlock().getType())) {
            deny(event, event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        if (!crops.isPremium(event.getItem())) {
            return;
        }
        Inventory destination = event.getDestination();
        if (destination instanceof BrewerInventory || destination.getType() == InventoryType.COMPOSTER) {
            event.setCancelled(true);
        }
    }

    /** 醸造台に入ってしまった上質作物（ネザーウォート）での醸造を止める。投入の防止をすり抜けた場合の最後の砦。 */
    @EventHandler(ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        if (crops.isPremium(event.getContents().getIngredient())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    // 動物・村人への受け渡し、取引・醸造台への投入
    // ------------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        ItemStack hand = event.getPlayer().getInventory().getItem(event.getHand());
        if (crops.isPremium(hand)) {
            deny(event, event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player) && crops.isPremium(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /** 取引画面・醸造台を開いている間は、上質作物を動かすクリックを止める。 */
    private static boolean isGuardedContainer(Inventory top) {
        return top instanceof MerchantInventory || top instanceof BrewerInventory;
    }

    @EventHandler(ignoreCancelled = true)
    public void onContainerClick(InventoryClickEvent event) {
        if (!isGuardedContainer(event.getView().getTopInventory())) {
            return;
        }
        ItemStack hotbar = event.getHotbarButton() >= 0
                ? event.getWhoClicked().getInventory().getItem(event.getHotbarButton())
                : null;
        if (crops.isPremium(event.getCurrentItem()) || crops.isPremium(event.getCursor()) || crops.isPremium(hotbar)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onContainerDrag(InventoryDragEvent event) {
        if (isGuardedContainer(event.getView().getTopInventory()) && crops.isPremium(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    /**
     * 取引リストからの選択ではバニラが支払い欄へ自動で作物を移すため、クリックの監視をすり抜ける。
     * 取引が成立する瞬間に支払い欄を確認して止める（PlayerTradeEvent もこのイベントのサブクラスとして届く）。
     */
    @EventHandler(ignoreCancelled = true)
    public void onPurchase(PlayerPurchaseEvent event) {
        if (event.getPlayer().getOpenInventory().getTopInventory() instanceof MerchantInventory merchant
                && (crops.isPremium(merchant.getItem(0)) || crops.isPremium(merchant.getItem(1)))) {
            deny(event, event.getPlayer());
        }
    }

    private void deny(Cancellable event, Player player) {
        event.setCancelled(true);
        player.sendMessage(config.getMessage("custom-enchants.premium_crop_blocked", player));
    }
}
