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
    // A captor places a Prison Realm block as a release marker before running /unseal.
    private final Map<UUID, Location> releaseStations = new HashMap<>();
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
