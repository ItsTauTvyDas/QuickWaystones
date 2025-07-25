package fun.pozzoo.quickwaystones.events;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import fun.pozzoo.quickwaystones.QuickWaystones;
import fun.pozzoo.quickwaystones.WaystoneSound;
import fun.pozzoo.quickwaystones.data.WaystoneData;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

public class WaystoneEventsHandler implements Listener {
    private final QuickWaystones plugin;

    public WaystoneEventsHandler(QuickWaystones plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private boolean canPlaceBlock(Block block) {
        if (!block.getType().isOccluding())
            return true;
        Block block1 = block.getRelative(BlockFace.DOWN, 1);
        Block block2 = block.getRelative(BlockFace.DOWN, 2);
        Block blockUp2 = block.getRelative(BlockFace.UP, 2);
        Block block3 = block.getRelative(BlockFace.DOWN, 3);
        if (plugin.isWaystoneBlock(block2))
            return false;
        if (plugin.isWaystoneBlock(block1) && blockUp2.getType().isOccluding())
            return false;
        return !(block2.getType().isOccluding() && plugin.isWaystoneBlock(block3));
    }

    private void destroyWaystone(Location location) {
        plugin.getWaystonesMap().remove(location);
        plugin.getDataManager().saveWaystoneData();
    }

    @EventHandler
    public void onBlockDestroy(BlockDestroyEvent event) {
        if (!plugin.isWaystoneBlock(event.getBlock())) return;

        WaystoneData waystone = plugin.getWaystonesMap().get(event.getBlock().getLocation());
        Location location = event.getBlock().getLocation();
        if (event.willDrop()) {
            event.setWillDrop(false);
            location.getWorld().dropItem(
                    location.toCenterLocation(),
                    plugin.getCraftManager().createWaystoneItem(waystone)
            );
            plugin.playWaystoneSound(null, location, WaystoneSound.DEACTIVATED);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.isWaystoneBlock(event.getBlock())) return;

        Player player = event.getPlayer();
        WaystoneData waystone = plugin.getWaystonesMap().get(event.getBlock().getLocation());

        Location location = event.getBlock().getLocation();
        if ((player.isOp() && player.getGameMode() == GameMode.CREATIVE) || player.getUniqueId().equals(waystone.getOwnerUniqueId()))
            destroyWaystone(location);
        plugin.playWaystoneSound(null, location, WaystoneSound.DEACTIVATED);
        event.setDropItems(false);
        if (event.getPlayer().getInventory().getItemInMainHand().getType().toString().endsWith("_PICKAXE"))
            location.getWorld().dropItem(
                    location.toCenterLocation(),
                    plugin.getCraftManager().createWaystoneItem(waystone)
            );
    }

    @EventHandler
    public void onBlockPlaceEvent(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        Block block = event.getBlockPlaced();
        if (!canPlaceBlock(event.getBlockPlaced())) {
            plugin.playWaystoneSound(event.getPlayer(), block.getLocation(), WaystoneSound.DISALLOWED);
            event.setCancelled(true);
            return;
        }
        if (!plugin.isWaystoneItem(item))
            return;
        Player player = event.getPlayer();
        WaystoneData data = new WaystoneData(block.getLocation(), player.getName(), player.getUniqueId());
        plugin.getWaystonesMap().put(block.getLocation(), data);
        String name = item.getItemMeta().getPersistentDataContainer().get(plugin.getCraftManager().getPersistentWaystoneNameKey(), PersistentDataType.STRING);
        Boolean visible = item.getItemMeta().getPersistentDataContainer().get(plugin.getCraftManager().getPersistentWaystoneVisibilityKey(), PersistentDataType.BOOLEAN);
        if (name != null && !name.isEmpty())
            data.setName(name);
        if (visible != null)
            data.setGloballyAccessible(visible);
        plugin.getDataManager().saveWaystoneData();
        plugin.playWaystoneSound(null, block.getLocation(), WaystoneSound.ACTIVATED);
    }

    @EventHandler
    public void onPlayerInteractWaystone(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;
        if (!plugin.isWaystoneBlock(event.getClickedBlock())) return;
        if (plugin.isWaystoneDestroyed(event.getClickedBlock())) return;

        Player player = event.getPlayer();
        Block block = event.getClickedBlock();

        if (event.getItem() == null) {
            WaystoneData data = plugin.getWaystonesMap().get(block.getLocation());
            if (data == null)
                return;
            checkForAvailabilityAndShowListDialog(player, data);
            return;
        }

        WaystoneData waystone = plugin.getWaystonesMap().get(block.getLocation());
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("name", waystone.getName());
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("Features.RenameByNameTag");
        if (section != null && section.getBoolean("Enabled")
                && event.getItem().getType() == Material.getMaterial(section.getString("Material", "NAME_TAG"))) {
            TextComponent textComponent = (TextComponent) event.getItem().getItemMeta().displayName();

            if (textComponent == null) return;

            waystone.setName(textComponent.decoration(TextDecoration.ITALIC, false).content());
            placeholders.put("new_name", waystone.getName());
            plugin.getDataManager().saveWaystoneData();
            if (section.getBoolean("SubtractItemCount"))
                player.getInventory().getItemInMainHand().subtract();
            plugin.playWaystoneSound(player, block.getLocation(), WaystoneSound.RENAMED);
            player.sendActionBar(QuickWaystones.message("Renamed", placeholders));
            return;
        }
        section = plugin.getConfig().getConfigurationSection("Features.ChangeVisibility");
        if (section != null && section.getBoolean("Enabled")
                && event.getItem().getType() == Material.getMaterial(section.getString("Material", "ECHO_SHARD"))) {
            waystone.setGloballyAccessible(!waystone.isGloballyAccessible());
            plugin.getDataManager().saveWaystoneData();
            if (section.getBoolean("SubtractItemCount"))
                player.getInventory().getItemInMainHand().subtract();
            String type;
            if (waystone.isGloballyAccessible()) {
                type = "Public";
                plugin.playWaystoneSound(player, block.getLocation(), WaystoneSound.VISIBILITY_CHANGE_TO_PUBLIC);
            } else {
                type = "Private";
                plugin.playWaystoneSound(player, block.getLocation(), WaystoneSound.VISIBILITY_CHANGE_TO_PRIVATE);
            }
            placeholders.put("type", plugin.getConfig().getString("Messages.WaystoneAttributes." + type));
            player.sendActionBar(QuickWaystones.message("VisibilityChanged", placeholders));
        }
    }

    @EventHandler
    public void onEntityInteract(EntityInteractEvent event) {
        pressurePlate(event.getBlock(), event.getEntity(), event);
    }

    @EventHandler
    public void onPlayerInteractRedstone(PlayerInteractEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        if (event.getAction() != Action.PHYSICAL) return;
        if (event.getClickedBlock() == null) return;
        if (plugin.isWaystoneBlock(event.getClickedBlock())) return;
        if (event.getPlayer().getNoDamageTicks() > 0) return;
        if (event.getPlayer().isInsideVehicle()) return;

        pressurePlate(event.getClickedBlock(), event.getPlayer(), event);
    }

    private void pressurePlate(Block block, Entity entity, Cancellable cancellable) {
        Material type = block.getType();
        if (type.toString().endsWith("_PRESSURE_PLATE") && !List.of(Material.LIGHT_WEIGHTED_PRESSURE_PLATE, Material.HEAVY_WEIGHTED_PRESSURE_PLATE).contains(type)) {
            for (int i = 1; i <= 2; i++) {
                Location loc = block.getLocation().add(0, -i, 0);
                WaystoneData data = plugin.getWaystonesMap().get(loc);
                if (data != null && !plugin.isWaystoneDestroyed(loc.getBlock())) {
                    if (!(entity instanceof Player player)) {
                        cancellable.setCancelled(true);
                        return;
                    }
                    List<MetadataValue> list = player.getMetadata("was_damaged");
                    if (list.isEmpty())
                        checkForAvailabilityAndShowListDialog(player, data);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent event) {
        List<Block> blocks = new ArrayList<>(event.getBlocks());
        blocks.add(event.getBlock());
        blocks.add(event.getBlock().getRelative(event.getDirection()));
        for (Block block : blocks) {
            Location location = block.getRelative(event.getDirection()).getLocation();
            for (Entity entity : location.getWorld().getNearbyEntities(location, 1, 1, 1, x -> x instanceof Player)) {
                Player player = (Player) entity;
                player.setMetadata("was_damaged", new FixedMetadataValue(plugin, System.currentTimeMillis()));
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlock().getType() == Material.WATER) {
            event.getPlayer().setMetadata("was_damaged", new FixedMetadataValue(plugin, System.currentTimeMillis()));
            return;
        }
        if (event.getFrom().getYaw() == event.getTo().getYaw() && event.getFrom().getPitch() == event.getTo().getPitch()) {
            for (Entity entity : event.getPlayer().getWorld().getNearbyEntities(event.getFrom(), 1, 2, 1)) {
                if (entity == event.getPlayer())
                    continue;
                Location entityLocation = entity.getLocation();
                if (entityLocation.distance(event.getFrom()) <= 0.7) {
                    event.getPlayer().setMetadata("was_damaged", new FixedMetadataValue(plugin, System.currentTimeMillis()));
                    return;
                }
            }
        }
        List<MetadataValue> list = event.getPlayer().getMetadata("was_damaged");
        if (list.isEmpty()) return;
        long timestamp = list.getFirst().asLong();
        if (timestamp + 1000L >= System.currentTimeMillis())
            return;
        event.getPlayer().removeMetadata("was_damaged", plugin);
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player player)
            player.setMetadata("was_damaged", new FixedMetadataValue(plugin, null));
    }

    @EventHandler
    public void onPlayerKnockback(EntityKnockbackEvent event) {
        if (event.getEntity() instanceof Player player)
            player.setMetadata("was_damaged", new FixedMetadataValue(plugin, null));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getWaystoneDialogs().cleanupPlayerCache(event.getPlayer().getUniqueId());
    }

    private void checkForAvailabilityAndShowListDialog(Player player, WaystoneData data) {
        List<MetadataValue> list = player.getMetadata("teleported_at");
        if (!list.isEmpty()) {
            long current = System.currentTimeMillis();
            long last = list.getFirst().asLong();
            long delayAfter = plugin.getConfig().getLong("Teleportation.DelayBetweenUses") * 1000;
            if (last + delayAfter >= current) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("time", String.format("%.1f", (last + delayAfter - current) / 1000.0));
                player.sendActionBar(QuickWaystones.message("WaitBeforeUse", placeholders));
                return;
            }
            player.removeMetadata("teleported_at", plugin);
        }
        plugin.getWaystoneDialogs().showListDialog(player, data);
    }
}