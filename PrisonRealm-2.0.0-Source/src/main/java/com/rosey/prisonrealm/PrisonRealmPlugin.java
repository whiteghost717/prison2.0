package com.rosey.prisonrealm;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.*;
import java.util.*;

public final class PrisonRealmPlugin extends JavaPlugin implements Listener {
    private NamespacedKey itemKey;
    private final Map<String, Ritual> rituals = new HashMap<>();
    private final Map<UUID, UUID> sealedBy = new HashMap<>(); // prisoner -> original sealer
    private final Map<UUID, GameMode> oldModes = new HashMap<>();
    private Location prisonLocation;

    private static final class Ritual {
        final String blockKey;
        final UUID owner;
        final UUID target;
        final Location core;
        final List<UUID> crystals = new ArrayList<>();
        int taskId = -1;
        int seconds = 30;
        boolean active;
        Ritual(String key, UUID owner, UUID target, Location core) {
            this.blockKey = key; this.owner = owner; this.target = target; this.core = core;
        }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "prison_realm");
        Bukkit.getPluginManager().registerEvents(this, this);
        registerRecipe();
        loadPrisonLocation();
        loadSealed();
        getLogger().info("PrisonRealm enabled.");
    }

    @Override public void onDisable() {
        for (Ritual r : new ArrayList<>(rituals.values())) cancelRitual(r, "Server shutting down.", false);
        saveSealed();
        savePrisonLocation();
    }

    private void registerRecipe() {
        NamespacedKey key = new NamespacedKey(this, "prison_realm_recipe");
        Bukkit.removeRecipe(key);
        ShapedRecipe recipe = new ShapedRecipe(key, createRealmItem());
        recipe.shape("RNR", "NEN", "RNR");
        recipe.setIngredient('R', Material.REDSTONE_BLOCK);
        recipe.setIngredient('N', Material.NETHER_STAR);
        recipe.setIngredient('E', Material.DRAGON_EGG);
        Bukkit.addRecipe(recipe);
    }

    private ItemStack createRealmItem() {
        ItemStack item = new ItemStack(Material.CRYING_OBSIDIAN);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(getConfig().getString("item.name", "&5&lPrison Realm")));
        meta.setLore(getConfig().getStringList("item.lore").stream().map(this::color).toList());
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        meta.setCustomModelData(getConfig().getInt("item.custom-model-data", 26001));
        if (getConfig().getBoolean("item.glow", true)) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        }
        item.setItemMeta(meta);
        return item;
    }

    private boolean isRealm(ItemStack item) {
        return item != null && item.getType() == Material.CRYING_OBSIDIAN && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
    private String key(Location l) { return l.getWorld().getName()+":"+l.getBlockX()+":"+l.getBlockY()+":"+l.getBlockZ(); }
    private void broadcast(String s) { Bukkit.broadcastMessage(color("&5[PrisonRealm] &d" + s)); }
    private boolean admin(CommandSender s) { if (!s.hasPermission("prisonrealm.admin")) { s.sendMessage("§cYou need prisonrealm.admin."); return false; } return true; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("freeze")) { sender.sendMessage("§c/freeze has been removed. Prison Realm captures automatically."); return true; }
        if (cmd.equals("prisonrealm")) {
            if (args.length == 0) { sender.sendMessage("§5PrisonRealm commands: /prisonrealm give <player>, setprison, status, sealed, release <player>, reload"); return true; }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "give" -> {
                    if (!admin(sender)) return true;
                    if (args.length < 2) { sender.sendMessage("§cUsage: /prisonrealm give <player>"); return true; }
                    Player p = Bukkit.getPlayerExact(args[1]);
                    if (p == null) { sender.sendMessage("§cThat player must be online."); return true; }
                    Map<Integer, ItemStack> left = p.getInventory().addItem(createRealmItem());
                    left.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
                    sender.sendMessage("§aGave a Prison Realm to " + p.getName() + "."); return true;
                }
                case "setprison" -> {
                    if (!admin(sender)) return true;
                    if (!(sender instanceof Player p)) { sender.sendMessage("§cRun this in-game in the Nether at the prison spawn."); return true; }
                    if (p.getWorld().getEnvironment() != World.Environment.NETHER) { sender.sendMessage("§cGo to the Nether prison location first."); return true; }
                    prisonLocation = p.getLocation().clone(); savePrisonLocation();
                    sender.sendMessage("§aPrison spawn saved at your current Nether location."); return true;
                }
                case "status" -> { sender.sendMessage("§5Rituals: §f"+rituals.size()+" §7| Sealed prisoners: §f"+sealedBy.size()); return true; }
                case "sealed" -> { if (sealedBy.isEmpty()) sender.sendMessage("§7No sealed players."); else sealedBy.keySet().forEach(id -> sender.sendMessage("§d- §f"+name(id)+" §7(sealed by "+name(sealedBy.get(id))+")")); return true; }
                case "release" -> {
                    if (!admin(sender)) return true;
                    if (args.length < 2) { sender.sendMessage("§cUsage: /prisonrealm release <player>"); return true; }
                    Player p = Bukkit.getPlayerExact(args[1]); UUID id = p != null ? p.getUniqueId() : findUuid(args[1]);
                    if (id == null || !sealedBy.containsKey(id)) { sender.sendMessage("§cThat player is not sealed."); return true; }
                    release(id, true); sender.sendMessage("§aReleased "+args[1]+"."); return true;
                }
                case "reload" -> { if (!admin(sender)) return true; reloadConfig(); registerRecipe(); sender.sendMessage("§aPrisonRealm config reloaded."); return true; }
                default -> { sender.sendMessage("§5Commands: /prisonrealm give <player>, setprison, status, sealed, release <player>, reload"); return true; }
            }
        }
        if (cmd.equals("seal")) {
            if (!(sender instanceof Player owner)) { sender.sendMessage("§cPlayers only."); return true; }
            if (!owner.hasPermission("prisonrealm.use")) { sender.sendMessage("§cYou cannot use Prison Realm."); return true; }
            if (args.length < 1) { owner.sendMessage("§cUsage: /seal <player>"); return true; }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null) { owner.sendMessage("§cTarget must be online."); return true; }
            Ritual ritual = rituals.values().stream().filter(r -> r.owner.equals(owner.getUniqueId()) && r.active && r.target.equals(target.getUniqueId())).findFirst().orElse(null);
            if (ritual == null) { owner.sendMessage("§cYou can only seal the target of your own active Prison Realm ritual."); return true; }
            if (!target.isOnline() || !target.getWorld().equals(ritual.core.getWorld()) || !insideArea(target.getLocation(), ritual.core)) { cancelRitual(ritual, "The target escaped the Prison Realm!", true); return true; }
            if (!owner.isOnline() || owner.isDead()) { cancelRitual(ritual, "The ritual was cancelled because its owner died or disconnected.", true); return true; }
            sealTarget(ritual, target, owner); return true;
        }
        if (cmd.equals("unseal")) {
            if (!(sender instanceof Player owner)) { sender.sendMessage("§cPlayers only."); return true; }
            if (args.length < 1) { owner.sendMessage("§cUsage: /unseal <player>"); return true; }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null || !sealedBy.containsKey(target.getUniqueId())) { owner.sendMessage("§cThat player is not online or not sealed."); return true; }
            if (!owner.getUniqueId().equals(sealedBy.get(target.getUniqueId()))) { owner.sendMessage("§cOnly the original sealer or an admin can release them."); return true; }
            release(target.getUniqueId(), true); broadcast(target.getName()+" has been released from the Prison Realm."); return true;
        }
        if (cmd.equals("player")) { sender.sendMessage("§7Prisoners stay sealed until their original sealer uses /unseal or an admin releases them."); return true; }
        return false;
    }

    @EventHandler public void onPlace(BlockPlaceEvent e) {
        if (!isRealm(e.getItemInHand())) return;
        Player owner = e.getPlayer(); Location core = e.getBlockPlaced().getLocation();
        if (rituals.values().stream().anyMatch(r -> r.core.getWorld().equals(core.getWorld()) && r.core.distance(core) < 30)) {
            e.setCancelled(true); owner.sendMessage("§cAnother Prison Realm ritual is too close."); return;
        }
        Player target = Bukkit.getOnlinePlayers().stream().filter(p -> !p.equals(owner) && !sealedBy.containsKey(p.getUniqueId()))
                .filter(p -> p.getWorld().equals(core.getWorld()) && insideArea(p.getLocation(), core))
                .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(core.clone().add(.5, .5, .5)))).orElse(null);
        if (target == null) {
            Bukkit.getScheduler().runTask(this, () -> { if (e.getBlockPlaced().getType() == Material.CRYING_OBSIDIAN) e.getBlockPlaced().setType(Material.AIR); owner.getInventory().addItem(createRealmItem()); owner.sendMessage("§cNo target inside the 30×30 capture area. Prison Realm returned to you."); });
            return;
        }
        Ritual r = new Ritual(key(core), owner.getUniqueId(), target.getUniqueId(), core.clone()); rituals.put(r.blockKey, r);
        spawnCrystals(r);
        broadcast(owner.getName()+" has activated a Prison Realm! "+target.getName()+" has 30 seconds to escape!");
        owner.sendMessage("§dYour Prison Realm ritual has started. Only you can seal "+target.getName()+".");
        r.taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> tickRitual(r), 0L, 20L);
    }

    private void spawnCrystals(Ritual r) {
        World w = r.core.getWorld(); Location c = r.core.clone().add(.5, 1, .5);
        int[][] offsets = {{4,0},{-4,0},{0,4},{0,-4}};
        for (int[] off : offsets) {
            Location at = c.clone().add(off[0], 1.5, off[1]);
            EnderCrystal crystal = w.spawn(at, EnderCrystal.class, ent -> { ent.setShowingBottom(false); ent.setInvulnerable(true); });
            r.crystals.add(crystal.getUniqueId());
        }
    }

    private void tickRitual(Ritual r) {
        if (!rituals.containsKey(r.blockKey)) return;
        Player owner = Bukkit.getPlayer(r.owner), target = Bukkit.getPlayer(r.target);
        if (owner == null || !owner.isOnline() || owner.isDead()) { cancelRitual(r, "The ritual was cancelled because its owner died or disconnected.", true); return; }
        if (target == null || !target.isOnline()) { cancelRitual(r, "The ritual failed because the target disconnected.", true); return; }
        if (r.core.getBlock().getType() != Material.CRYING_OBSIDIAN) { cancelRitual(r, "The Prison Realm was destroyed. Ritual cancelled.", true); return; }
        if (!target.getWorld().equals(r.core.getWorld()) || !insideArea(target.getLocation(), r.core)) { cancelRitual(r, target.getName()+" escaped the Prison Realm!", true); return; }
        if (!r.active) {
            target.sendActionBar(color("&5Prison Realm activates in &d"+r.seconds+"s")); owner.sendActionBar(color("&5Ritual starts in &d"+r.seconds+"s"));
            if (r.seconds <= 0) {
                r.active = true; target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, 10, false, false, true));
                target.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, Integer.MAX_VALUE, 250, false, false, false));
                for (UUID id : r.crystals) { Entity ent = Bukkit.getEntity(id); if (ent instanceof EnderCrystal crystal) crystal.setBeamTarget(target.getLocation().clone().add(0, 1, 0)); }
                broadcast("The crystals have locked onto "+target.getName()+"! The target is frozen. "+owner.getName()+" must complete /seal "+target.getName()+".");
                target.sendTitle(color("&5PRISON REALM"), color("&dYou have been frozen!"), 5, 60, 10);
            } else r.seconds--;
        } else {
            target.sendActionBar(color("&5Prison Realm: &dFrozen &7| Owner: &f"+owner.getName()));
            for (UUID id : r.crystals) { Entity ent = Bukkit.getEntity(id); if (ent instanceof EnderCrystal crystal) crystal.setBeamTarget(target.getLocation().clone().add(0, 1, 0)); }
        }
    }

    private boolean insideArea(Location p, Location core) {
        return p.getWorld().equals(core.getWorld()) && Math.abs(p.getX() - (core.getBlockX()+.5)) <= 15 && Math.abs(p.getZ() - (core.getBlockZ()+.5)) <= 15;
    }
    private void sealTarget(Ritual r, Player target, Player owner) {
        if (prisonLocation == null || prisonLocation.getWorld() == null || prisonLocation.getWorld().getEnvironment() != World.Environment.NETHER) {
            owner.sendMessage("§cAdmin has not set the Nether prison. Ask an admin to stand at the prison and run /prisonrealm setprison."); return;
        }
        sealedBy.put(target.getUniqueId(), owner.getUniqueId()); oldModes.put(target.getUniqueId(), target.getGameMode());
        target.removePotionEffect(PotionEffectType.SLOWNESS); target.removePotionEffect(PotionEffectType.JUMP_BOOST);
        target.setInvulnerable(true); target.setGameMode(GameMode.ADVENTURE); target.setFireTicks(0); target.setFallDistance(0);
        target.teleport(prisonLocation.clone());
        cancelRitual(r, null, false); saveSealed();
        target.sendTitle(color("&5SEALED"), color("&dYou are now imprisoned."), 10, 80, 20);
        owner.sendMessage("§aYou sealed "+target.getName()+" inside the Prison Realm.");
        broadcast(owner.getName()+" has sealed "+target.getName()+" inside the Prison Realm!");
    }

    private void cancelRitual(Ritual r, String message, boolean unfreeze) {
        if (r.taskId != -1) Bukkit.getScheduler().cancelTask(r.taskId);
        rituals.remove(r.blockKey);
        if (unfreeze) {
            Player target = Bukkit.getPlayer(r.target);
            if (target != null) { target.removePotionEffect(PotionEffectType.SLOWNESS); target.removePotionEffect(PotionEffectType.JUMP_BOOST); target.sendActionBar(color("&7Prison Realm ritual ended.")); }
        }
        for (UUID id : r.crystals) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        if (message != null) broadcast(message);
    }

    @EventHandler public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (sealedBy.containsKey(p.getUniqueId())) {
            if (e.getTo() != null && (e.getFrom().getX()!=e.getTo().getX() || e.getFrom().getY()!=e.getTo().getY() || e.getFrom().getZ()!=e.getTo().getZ())) e.setTo(e.getFrom());
            return;
        }
        Ritual r = rituals.values().stream().filter(x -> x.active && x.target.equals(p.getUniqueId())).findFirst().orElse(null);
        if (r != null && e.getTo() != null && !insideArea(e.getTo(), r.core)) cancelRitual(r, p.getName()+" escaped the Prison Realm!", true);
    }
    @EventHandler public void onDamage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p && sealedBy.containsKey(p.getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onBreak(BlockBreakEvent e) { Ritual r = rituals.get(key(e.getBlock().getLocation())); if (r != null) { e.setCancelled(true); e.getPlayer().sendMessage("§cYou cannot break an active Prison Realm."); } }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Ritual owned = rituals.values().stream().filter(r -> r.owner.equals(id)).findFirst().orElse(null);
        if (owned != null) cancelRitual(owned, "The ritual was cancelled because its owner disconnected.", true);
        Ritual target = rituals.values().stream().filter(r -> r.target.equals(id)).findFirst().orElse(null);
        if (target != null) cancelRitual(target, "The ritual failed because the target disconnected.", true);
        saveSealed();
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) { if (sealedBy.containsKey(e.getPlayer().getUniqueId())) enforcePrisoner(e.getPlayer()); }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent e) { if (sealedBy.containsKey(e.getPlayer().getUniqueId())) enforcePrisoner(e.getPlayer()); }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) { if (sealedBy.containsKey(e.getPlayer().getUniqueId()) && prisonLocation != null) e.setRespawnLocation(prisonLocation); }
    @EventHandler public void onTarget(EntityTargetEvent e) { if (e.getTarget() instanceof Player p && sealedBy.containsKey(p.getUniqueId())) e.setCancelled(true); }

    private void enforcePrisoner(Player p) {
        p.setInvulnerable(true); p.setGameMode(GameMode.ADVENTURE); p.setFireTicks(0); p.setFallDistance(0);
        if (prisonLocation != null && prisonLocation.getWorld() != null && !p.getWorld().equals(prisonLocation.getWorld())) Bukkit.getScheduler().runTask(this, () -> p.teleport(prisonLocation));
    }
    private void release(UUID id, boolean restoreMode) {
        Player p = Bukkit.getPlayer(id); sealedBy.remove(id);
        if (p != null) { p.setInvulnerable(false); if (restoreMode) p.setGameMode(oldModes.getOrDefault(id, GameMode.SURVIVAL)); p.sendTitle(color("&aRELEASED"), color("&7You are free."), 5, 50, 10); }
        oldModes.remove(id); saveSealed();
    }
    private String name(UUID id) { Player p = Bukkit.getPlayer(id); return p == null ? id.toString().substring(0, 8) : p.getName(); }
    private UUID findUuid(String s) { try { return UUID.fromString(s); } catch (IllegalArgumentException ignored) { return null; } }
    private void savePrisonLocation() {
        if (prisonLocation == null || prisonLocation.getWorld() == null) return;
        getConfig().set("prison.world", prisonLocation.getWorld().getName()); getConfig().set("prison.x", prisonLocation.getX()); getConfig().set("prison.y", prisonLocation.getY()); getConfig().set("prison.z", prisonLocation.getZ()); getConfig().set("prison.yaw", prisonLocation.getYaw()); getConfig().set("prison.pitch", prisonLocation.getPitch()); saveConfig();
    }
    private void loadPrisonLocation() {
        String world = getConfig().getString("prison.world"); if (world == null) return; World w = Bukkit.getWorld(world); if (w == null) return;
        prisonLocation = new Location(w, getConfig().getDouble("prison.x"), getConfig().getDouble("prison.y"), getConfig().getDouble("prison.z"), (float)getConfig().getDouble("prison.yaw"), (float)getConfig().getDouble("prison.pitch"));
    }
    private void saveSealed() {
        List<String> list = new ArrayList<>(); for (Map.Entry<UUID, UUID> e : sealedBy.entrySet()) list.add(e.getKey()+"|"+e.getValue()+"|"+oldModes.getOrDefault(e.getKey(), GameMode.SURVIVAL).name());
        getConfig().set("sealed-players", list); saveConfig();
    }
    private void loadSealed() {
        for (String row : getConfig().getStringList("sealed-players")) { String[] p = row.split("\\|"); if (p.length >= 2) try { UUID prisoner = UUID.fromString(p[0]), owner = UUID.fromString(p[1]); sealedBy.put(prisoner, owner); if (p.length >= 3) oldModes.put(prisoner, GameMode.valueOf(p[2])); } catch (IllegalArgumentException ignored) { getLogger().warning("Skipping invalid sealed-player record: "+row); } }
    }
}
