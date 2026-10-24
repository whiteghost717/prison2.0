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
        cancelRitual(r, null, false);

        // The placed Prison Realm breaks automatically after a successful capture.
        Location realmBlock = r.core.clone();
        if (realmBlock.getBlock().getType() == Material.CRYING_OBSIDIAN) {
            realmBlock.getBlock().setType(Material.AIR);
            Map<Integer, ItemStack> left = owner.getInventory().addItem(createRealmItem());
            left.values().forEach(i -> owner.getWorld().dropItemNaturally(owner.getLocation(), i));
        }
        saveSealed();
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
        // Sealed players can move freely inside the prison.
        if (sealedBy.containsKey(p.getUniqueId())) return;
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
        release(id, restoreMode, null);
    }

    private void release(UUID id, boolean restoreMode, Location destination) {
        Player p = Bukkit.getPlayer(id);
        sealedBy.remove(id);
        if (p != null) {
            p.setInvulnerable(false);
            if (restoreMode) p.setGameMode(oldModes.getOrDefault(id, GameMode.SURVIVAL));
            if (destination != null && destination.getWorld() != null) p.teleport(destination);
            p.sendTitle(color("&aRELEASED"), color("&7You are free."), 5, 50, 10);
        }
        oldModes.remove(id);
        saveSealed();
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
