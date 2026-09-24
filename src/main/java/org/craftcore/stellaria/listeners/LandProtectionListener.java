package org.craftcore.stellaria.listeners;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.DoubleChest;
import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import io.papermc.paper.event.player.PlayerOpenSignEvent;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.LeashHitch;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Wither;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.craftcore.stellaria.StellariaCore;
import org.craftcore.stellaria.utils.ColorUtil;

import java.util.List;
import java.util.UUID;

/**
 * 土地保護（/land）のイベント判定。判定の中心はLandManager#canBuild（ブロック操作・PvP以外）と
 * LandManager#isPvpAllowed（PvPのみ）の2つ。管理者bypass（stellaria.land.admin）は
 * 権限を持っているだけでは効かず、LandManager#hasBypassEnabled（/land bypassでON/OFF）を
 * 満たした時だけ保護を無視できる。
 */
public class LandProtectionListener implements Listener {

    /**
     * 保護通知はチャットではなくアクションバーの一時フラッシュ表示にする（ActionBarManager#flash）。
     * 木こりの連鎖伐採やPvP連打で同じ判定が短時間に何度も走っても、アクションバーは同じチャンネルを
     * 上書きするだけでチャット欄を汚さないため、独自のクールダウン管理が不要になる。
     */
    private static final long NOTICE_DURATION_TICKS = 40L;
    private static final String NOTICE_CHANNEL = "land_protection";

    private final StellariaCore plugin;

    public LandProtectionListener(StellariaCore plugin) {
        this.plugin = plugin;
    }

    /** {@link Object#notify()}と紛らわしくなるため、あえて"notify"ではなくこの名前にしている。 */
    private void sendNotice(Player player, String messageKey) {
        String message = plugin.getConfigManager().getMessage(messageKey, player);
        plugin.getActionBarManager().flash(player, NOTICE_CHANNEL, ColorUtil.component(message), NOTICE_DURATION_TICKS);
    }

    /**
     * LOW優先度で登録する（StellariaCore側）。KikoriListener#onBlockBreakより先に評価させ、
     * ここでキャンセルしたブロックについては連鎖伐採の起動判定自体が走らないようにするため。
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.getLandManager().canBuild(event.getBlock().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            sendNotice(event.getPlayer(), "land.protected_block");
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        Player player = event.getPlayer();

        if (!plugin.getLandManager().canBuild(block.getLocation(), player)) {
            event.setCancelled(true);
            sendNotice(player, "land.protected_block");
            return;
        }

        // 隣に置いてラージチェスト化することで他人のチェストの中身に触れるのを防ぐ（銅のチェストも同様に連結する）。
        if ((block.getType() == Material.CHEST
                || block.getType() == Material.TRAPPED_CHEST
                || Tag.COPPER_CHESTS.isTagged(block.getType()))
                && !canAccessContainer(block, player)) {

            event.setCancelled(true);
            sendNotice(player, "land.protected_block");
        }
    }

    @EventHandler
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!plugin.getLandManager().canBuild(event.getBlock().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            sendNotice(event.getPlayer(), "land.protected_block");
        }
    }

    @EventHandler
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!plugin.getLandManager().canBuild(event.getBlock().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            sendNotice(event.getPlayer(), "land.protected_block");
        }
    }

    /**
     * 利き手の判定はしない（オフハンドのレコード・染料・リード等でも操作できてしまうため、両手とも止める）。
     */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Material type = block.getType();
        Player player = event.getPlayer();

        // ドラゴンの卵は左クリックでもテレポートするため、左右どちらのクリックも止める。
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            if (type == Material.DRAGON_EGG && !plugin.getLandManager().canBuild(block.getLocation(), player)) {
                event.setCancelled(true);
                sendNotice(player, "land.protected_block");
            }
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        // 書見台は本を読むだけなら誰でも可能にする。本の取り出し・設置はonTakeLecternBook/onInsertLecternBookで判定する。
        if (type == Material.LECTERN) {
            return;
        }

        boolean isDoor = isDoorMaterial(type);
        boolean isChest = isContainerMaterial(type);
        if (isChest) {
            if (!canAccessContainer(block, player)) {
                event.setCancelled(true);
                sendNotice(player, "land.protected_block");
            }
            return;
        }
        if (!isDoor && !isBuildInteractable(type, event.getItem())) {
            return;
        }

        // エリアオーナーが「他人でも開閉可能」に設定していれば、trust関係なく誰でも操作できる。
        if (isDoor && plugin.getLandManager().doorsOpenToOthers(block.getLocation())) {
            return;
        }
        if (!plugin.getLandManager().canBuild(block.getLocation(), player)) {
            event.setCancelled(true);
            sendNotice(player, "land.protected_block");
        }
    }

    /**
     * 右クリックで状態が変わる（＝実質的な改変になる）ブロックのうち、ドア系・コンテナ系以外のもの。
     * これらはdoors/chestsの公開フラグに関係なく、canBuildできる人だけが操作できる。
     * フェンスは右クリックの用途がリードを結ぶことしかないため、ここで止めて他人の土地へのリード結び付けを防ぐ。
     * 看板は読むだけ/クリックコマンドの実行は妨げないよう、染料・イカスミ・ハニカムを持っている時だけ止める
     * （文字の編集自体はonOpenSign/onSignChangeで判定する）。
     */
    private boolean isBuildInteractable(Material material, ItemStack item) {
        if (Tag.FLOWER_POTS.isTagged(material) || Tag.FENCES.isTagged(material)) {
            return true;
        }
        if (Tag.ALL_SIGNS.isTagged(material)) {
            return item != null && isSignEditingItem(item.getType());
        }
        return switch (material) {
            case JUKEBOX, DRAGON_EGG, NOTE_BLOCK, DAYLIGHT_DETECTOR, REPEATER, COMPARATOR, REDSTONE_WIRE -> true;
            default -> false;
        };
    }

    private boolean isSignEditingItem(Material material) {
        return material.name().endsWith("_DYE")
                || material == Material.GLOW_INK_SAC
                || material == Material.INK_SAC
                || material == Material.HONEYCOMB;
    }

    /**
     * バニラのTag（DOORS/TRAPDOORS/FENCE_GATES/BUTTONS）で判定する。設定ファイル経由にしないのは、
     * config.ymlは新規インストール時にしか展開されず（移行機能なし）、既存サーバーで
     * 新しいキーが単に「無い」＝「保護対象0件」に化けてしまう事故を避けるため
     * （実際に銅ドア/淡いオークドア等、キー追加時点で存在しなかった新素材も自動的に拾える）。
     * ボタンもここに含めるのは、ドア同様「誰でも開閉可能」に設定していない限りはdoorsフラグの保護対象にするため。
     */
    private boolean isDoorMaterial(Material material) {
        return Tag.DOORS.isTagged(material) || Tag.TRAPDOORS.isTagged(material)
                || Tag.FENCE_GATES.isTagged(material) || Tag.BUTTONS.isTagged(material)
                || material == Material.LEVER;
    }

    /**
     * こちらは引き続きconfig.yml駆動（チェスト・シュルカー等はプレイヤーが手動で増減したいケースが
     * 現実的にあるため）。ただし新キー land.protected-containers が空（＝既存サーバーで未更新）なら、
     * このコマンド群導入前から存在した land.protected-interactables に自動フォールバックする。
     * 銅のチェスト・棚・欠けた金床等は、リストに無くてもバニラのTagで常に保護する
     * （config.ymlは既存サーバーに追記されないため、後から追加された素材がリスト漏れで無防備になるのを防ぐ）。
     */
    private boolean isContainerMaterial(Material material) {
        if (Tag.COPPER_CHESTS.isTagged(material) || Tag.WOODEN_SHELVES.isTagged(material) || Tag.ANVIL.isTagged(material)) {
            return true;
        }
        List<String> names = plugin.getConfigManager().getStringList("land.protected-containers");
        if (names.isEmpty()) {
            names = plugin.getConfigManager().getStringList("land.protected-interactables");
        }
        return names.contains(material.name());
    }

    @EventHandler
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (!plugin.getLandManager().canBuild(event.getRightClicked().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            sendNotice(event.getPlayer(), "land.protected_block");
        }
    }

    @EventHandler
    public void onTakeLecternBook(PlayerTakeLecternBookEvent event) {
        if (!canAccessContainerLocation(event.getLectern().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            sendNotice(event.getPlayer(), "land.protected_block");
        }
    }

    @EventHandler
    public void onInsertLecternBook(PlayerInsertLecternBookEvent event) {
        if (!canAccessContainerLocation(event.getBlock().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            sendNotice(event.getPlayer(), "land.protected_block");
        }
    }

    @EventHandler
    public void onOpenSign(PlayerOpenSignEvent event) {
        if (!plugin.getLandManager().canBuild(event.getSign().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            sendNotice(event.getPlayer(), "land.protected_block");
        }
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        if (!plugin.getLandManager().canBuild(event.getBlock().getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            sendNotice(event.getPlayer(), "land.protected_block");
        }
    }

    /**
     * 額縁のアイテム設置・回転、フェンスのリードの結び目の取り外しを保護する（どちらもHanging）。
     * 破壊側はonEntityDamageByEntity/onHangingBreakByEntityで扱う。
     */
    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        if (!(entity instanceof Hanging)) {
            return;
        }
        if (!plugin.getLandManager().canBuild(entity.getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            sendNotice(event.getPlayer(), "land.protected_block");
        }
    }

    /** チェスト付きトロッコ・ボート、ホッパー付きトロッコの中身はチェストと同じ公開フラグで判定する。 */
    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder(false) instanceof Entity entity) || !isStorageVehicle(entity)) {
            return;
        }
        if (!canAccessContainerLocation(entity.getLocation(), player)) {
            event.setCancelled(true);
            sendNotice(player, "land.protected_block");
        }
    }

    @EventHandler
    public void onVehicleDamage(VehicleDamageEvent event) {
        if (isStorageVehicleProtected(event.getVehicle(), event.getAttacker())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (isStorageVehicleProtected(event.getVehicle(), event.getAttacker())) {
            event.setCancelled(true);
        }
    }

    /** 収納付き乗り物をこのattackerが壊せないならtrue（通知も送る）。通常のトロッコ・ボートは線路/駅の運用を妨げないよう対象外。 */
    private boolean isStorageVehicleProtected(Entity vehicle, Entity attacker) {
        if (!isStorageVehicle(vehicle)) {
            return false;
        }
        Player player = resolveAttacker(attacker);
        if (player == null || plugin.getLandManager().canBuild(vehicle.getLocation(), player)) {
            return false;
        }
        sendNotice(player, "land.protected_block");
        return true;
    }

    private boolean isStorageVehicle(Entity entity) {
        return entity instanceof StorageMinecart || entity instanceof HopperMinecart || entity instanceof ChestBoat;
    }

    /**
     * リードで結ぶ操作。対象のMobがいる場所と、結び先がフェンスの結び目ならその場所の両方でcanBuildを要求する。
     * 他人の土地の動物を連れ出す操作もここで止まる。
     */
    @EventHandler
    public void onLeash(PlayerLeashEntityEvent event) {
        Player player = event.getPlayer();
        if (!plugin.getLandManager().canBuild(event.getEntity().getLocation(), player)
                || (event.getLeashHolder() instanceof LeashHitch hitch
                        && !plugin.getLandManager().canBuild(hitch.getLocation(), player))) {
            event.setCancelled(true);
            sendNotice(player, "land.protected_block");
        }
    }

    /**
     * フェンスに結ばれたリードを外す操作のみ保護する。自分が手に持っているリードを外すのは、
     * 他人の土地の中であっても妨げない。
     */
    @EventHandler
    public void onUnleash(PlayerUnleashEntityEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.LivingEntity living)
                || !living.isLeashed()
                || !(living.getLeashHolder() instanceof LeashHitch hitch)) {
            return;
        }
        if (!plugin.getLandManager().canBuild(hitch.getLocation(), event.getPlayer())) {
            event.setCancelled(true);
            sendNotice(event.getPlayer(), "land.protected_block");
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || (attacker.hasPermission("stellaria.land.admin") && plugin.getLandManager().hasBypassEnabled(attacker))) {
            return;
        }

        // アーマースタンド・額縁/絵画等（Hanging）・エンドクリスタルはPvPではなく設置物の一種として扱う
        // （破壊・中身の取り出しはcanBuildで判定。額縁の中身だけを殴って抜く操作もここで拾える）。
        Entity victimEntity = event.getEntity();
        if (isPlacedObject(victimEntity)) {
            if (!plugin.getLandManager().canBuild(victimEntity.getLocation(), attacker)) {
                event.setCancelled(true);
                sendNotice(attacker, "land.protected_block");
            }
            return;
        }

        if (!(victimEntity instanceof Player victim)) {
            return;
        }
        if (attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }

        // claim不可ワールド（land.enabled-worlds外）では土地保護のPvP制限を適用しない。
        // バニラ/他プラグインのPvP挙動にそのまま委ねる。
        if (plugin.getLandManager().isWorldDisabled(victim.getWorld().getName())) {
            return;
        }

        if (!plugin.getLandManager().isPvpAllowed(victim.getLocation())) {
            event.setCancelled(true);
            sendNotice(attacker, "land.pvp_blocked");
        }
    }

    /** 直接攻撃・投射物・起爆されたTNT・懐かれた動物（オオカミ等）の攻撃者をPlayerまで解決する。 */
    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) {
            return player;
        }
        if (damager instanceof Tameable tameable && tameable.isTamed() && tameable.getOwner() instanceof Player player) {
            return player;
        }
        return null;
    }

    /**
     * プレイヤー（直接・投射物・TNT・懐いた動物経由）による額縁/絵画等の破壊を保護する。
     * resolveAttackerを使うため、矢で額縁を撃ち抜くような間接攻撃も正しく判定できる。
     * 爆発原因（Creeperや非プレイヤー起爆のTNT等）はresolveAttackerで解決できないプレイヤー不在の
     * ケースがあるため、爆発原因の場合はplayer解決を待たずonEntityExplode等と同様に無条件で保護する。
     */
    @EventHandler
    public void onHangingBreakByEntity(HangingBreakByEntityEvent event) {
        if (!plugin.getConfigManager().getBoolean("land.protect.hangings", true)) {
            return;
        }
        if (event.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION) {
            if (!plugin.getLandManager().explosionsAllowed(event.getEntity().getLocation())) {
                event.setCancelled(true);
            }
            return;
        }
        Player player = resolveAttacker(event.getRemover());
        if (player == null) {
            return;
        }
        if (!plugin.getLandManager().canBuild(event.getEntity().getLocation(), player)) {
            event.setCancelled(true);
            sendNotice(player, "land.protected_block");
        }
    }

    /**
     * HangingBreakByEntityEvent以外の経路（支柱のブロックが無くなった等の物理的脱落）での
     * 額縁/絵画等の破壊を扱う。爆発原因はonHangingBreakByEntity側（HangingBreakByEntityEventとして
     * 届く場合）で既に処理されるため、ここでは爆発以外の原因は素通りさせ、正常な脱落を妨げない。
     */
    @EventHandler
    public void onHangingBreak(HangingBreakEvent event) {
        if (event instanceof HangingBreakByEntityEvent) {
            return;
        }
        if (!plugin.getConfigManager().getBoolean("land.protect.hangings", true)) {
            return;
        }
        if (event.getCause() != HangingBreakEvent.RemoveCause.EXPLOSION) {
            return;
        }
        if (!plugin.getLandManager().explosionsAllowed(event.getEntity().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!plugin.getConfigManager().getBoolean("land.protect.pistons", true)) {
            return;
        }
        UUID pistonOwner = plugin.getLandManager().ownerOf(event.getBlock().getLocation());
        // ピストンヘッド自身の伸び先（何も押していない=空気だった場所を含む）も必ずチェックする。
        // getBlocks()は「押されるブロック」のリストなので、押す対象が無い(空気)場合はここに含まれず、
        // ヘッドだけがすり抜けてclaim内に伸びてしまう。
        Block headDestination = event.getBlock().getRelative(event.getDirection());
        if (crossesIntoOtherClaim(pistonOwner, headDestination.getLocation())
                || touchesOtherClaim(pistonOwner, event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!plugin.getConfigManager().getBoolean("land.protect.pistons", true)) {
            return;
        }
        UUID pistonOwner = plugin.getLandManager().ownerOf(event.getBlock().getLocation());
        // BlockPistonRetractEvent#getDirection()はピストン本体の向き（引き込む対象から見て逆向き）を指す。
        // 実際にブロックが移動する方向はその反対なので、ここだけgetOppositeFace()する
        // （extend側のgetDirection()は素直に移動方向なので反転不要）。
        if (touchesOtherClaim(pistonOwner, event.getBlocks(), event.getDirection().getOppositeFace())) {
            event.setCancelled(true);
        }
    }

    /**
     * 移動対象ブロック、またはその移動先のいずれかが「ピストンの持ち主とは異なるオーナーの土地
     * （または未claim地からピストン所有者の土地へ）」をまたいでいればtrue。オーナー自身が自分のclaim内で
     * 完結させるレッドストーン回路は妨げない。
     */
    private boolean touchesOtherClaim(UUID pistonOwner, List<Block> movedBlocks, BlockFace direction) {
        for (Block block : movedBlocks) {
            if (crossesIntoOtherClaim(pistonOwner, block.getLocation())) {
                return true;
            }
            Block destination = block.getRelative(direction);
            if (crossesIntoOtherClaim(pistonOwner, destination.getLocation())) {
                return true;
            }
        }
        return false;
    }

    private boolean crossesIntoOtherClaim(UUID pistonOwner, Location location) {
        UUID owner = plugin.getLandManager().ownerOf(location);
        return owner != null && !owner.equals(pistonOwner);
    }

    @EventHandler
    public void onLiquidFlow(BlockFromToEvent event) {
        // 未claim地への流入は保護対象が無いので、config読み込みより先に（より安価な）判定で弾く。
        UUID toOwner = plugin.getLandManager().ownerOf(event.getToBlock().getLocation());
        if (toOwner == null) {
            return;
        }
        if (!plugin.getConfigManager().getBoolean("land.protect.liquid-flow", true)) {
            return;
        }
        UUID fromOwner = plugin.getLandManager().ownerOf(event.getBlock().getLocation());
        if (!toOwner.equals(fromOwner)) {
            // 流入元が別オーナー（または未claim）の場合のみキャンセル。同じエリア内の自然な流れは許可する。
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!plugin.getConfigManager().getBoolean("land.protect.explosions", true)) {
            return;
        }
        event.blockList().removeIf(block -> !plugin.getLandManager().explosionsAllowed(block.getLocation()));
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!plugin.getConfigManager().getBoolean("land.protect.explosions", true)) {
            return;
        }
        event.blockList().removeIf(block -> !plugin.getLandManager().explosionsAllowed(block.getLocation()));
    }

    /**
     * 爆発によるアーマースタンド・エンドクリスタル・収納付き乗り物の破壊を、ブロックと同じexplosionsフラグで保護する。
     * （額縁・絵画はHangingBreakEvent側で扱う）
     */
    @EventHandler
    public void onEntityDamageByExplosion(EntityDamageEvent event) {
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION && cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity instanceof ArmorStand || entity instanceof EnderCrystal || isStorageVehicle(entity))) {
            return;
        }
        if (!plugin.getConfigManager().getBoolean("land.protect.explosions", true)) {
            return;
        }
        if (!plugin.getLandManager().explosionsAllowed(entity.getLocation())) {
            event.setCancelled(true);
        }
    }

    /**
     * ウィザーはダメージを受けている間、爆発ではなくEntityChangeBlockEventで周囲のブロックを直接壊すため、
     * onEntityExplodeだけでは防げない。爆発と同じexplosionsフラグで保護する。
     */
    @EventHandler
    public void onWitherChangeBlock(EntityChangeBlockEvent event) {
        if (!(event.getEntity() instanceof Wither)) {
            return;
        }
        if (!plugin.getConfigManager().getBoolean("land.protect.explosions", true)) {
            return;
        }
        if (!plugin.getLandManager().explosionsAllowed(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (!plugin.getConfigManager().getBoolean("land.protect.fire", true)) {
            return;
        }
        if (plugin.getLandManager().ownerOf(event.getBlock().getLocation()) == null) {
            return;
        }
        Player igniter = event.getPlayer();
        if (igniter != null && plugin.getLandManager().canBuild(event.getBlock().getLocation(), igniter)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler
    public void onBlockBurn(BlockBurnEvent event) {
        if (!plugin.getConfigManager().getBoolean("land.protect.fire", true)) {
            return;
        }
        if (plugin.getLandManager().ownerOf(event.getBlock().getLocation()) != null) {
            event.setCancelled(true);
        }
    }

    private boolean isPlacedObject(Entity entity) {
        return entity instanceof ArmorStand || entity instanceof Hanging || entity instanceof EnderCrystal;
    }

    private boolean canAccessContainer(Block block, Player player) {
        if (!(block.getState() instanceof org.bukkit.block.Chest chest)) {
            return canAccessContainerLocation(block.getLocation(), player);
        }

        InventoryHolder holder = chest.getInventory().getHolder();

        // シングルチェスト
        if (!(holder instanceof DoubleChest doubleChest)) {
            return canAccessContainerLocation(block.getLocation(), player);
        }

        // ラージチェストは左右両方をチェック
        if (doubleChest.getLeftSide() instanceof org.bukkit.block.Chest left
                && !canAccessContainerLocation(left.getLocation(), player)) {
            return false;
        }

        if (doubleChest.getRightSide() instanceof org.bukkit.block.Chest right
                && !canAccessContainerLocation(right.getLocation(), player)) {
            return false;
        }

        return true;
    }

    private boolean canAccessContainerLocation(Location location, Player player) {
        // その土地がチェスト公開設定ならアクセス可能
        if (plugin.getLandManager().chestsOpenToOthers(location)) {
            return true;
        }

        return plugin.getLandManager().canBuild(location, player);
    }
}
