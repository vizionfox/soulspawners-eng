package org.mvk.soulspawners;

import org.bukkit.*;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class Soulspawners extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private NamespacedKey soulKey;
    private Random random = new Random();

    private boolean dropSouls;
    private boolean dropFromSpawner;
    private boolean spawnerCrafting;
    private double soulDropChance;
    private Material soulItem;

    private Map<String, String> messages = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        soulKey = new NamespacedKey(this, "soul_entity");

        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("ss")).setExecutor(this);
        Objects.requireNonNull(getCommand("ss")).setTabCompleter(this);

        if (spawnerCrafting) registerRecipe();
    }

    private void loadConfig() {
        reloadConfig();

        dropSouls = getConfig().getBoolean("drop-souls", true);
        dropFromSpawner = getConfig().getBoolean("drop-from-spawner", true);
        spawnerCrafting = getConfig().getBoolean("spawner-crafting", true);
        soulDropChance = getConfig().getDouble("soul-drop-chance", 10.0) / 100.0;

        try {
            soulItem = Material.valueOf(getConfig().getString("soul-item", "GHAST_TEAR"));
        } catch (Exception e) {
            soulItem = Material.GHAST_TEAR;
        }

        messages.clear();
        for (String key : Objects.requireNonNull(getConfig().getConfigurationSection("messages")).getKeys(false)) {
            messages.put(key, ChatColor.translateAlternateColorCodes('&',
                    getConfig().getString("messages." + key, "")));
        }
    }

    private String msg(String key, Map<String, String> placeholders) {
        String text = messages.getOrDefault(key, key);
        if (placeholders != null) {
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                text = text.replace("%" + entry.getKey() + "%", entry.getValue());
            }
        }
        return text;
    }

    private ItemStack createSoul(EntityType type, int amount) {
        ItemStack soul = new ItemStack(soulItem, amount);
        ItemMeta meta = soul.getItemMeta();
        meta.setDisplayName(ChatColor.LIGHT_PURPLE + "Soul of " + type.name());
        meta.getPersistentDataContainer().set(soulKey, PersistentDataType.STRING, type.name());
        soul.setItemMeta(meta);
        return soul;
    }

    private ItemStack createSpawner() {
        ItemStack spawner = new ItemStack(Material.SPAWNER);
        ItemMeta meta = spawner.getItemMeta();
        meta.setDisplayName("§fEmpty Spawner");
        spawner.setItemMeta(meta);
        return spawner;
    }

    private void registerRecipe() {
        ItemStack spawner = createSpawner();
        ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(this, "custom_spawner"), spawner);

        List<String> shape = getConfig().getStringList("spawner-recipe.shape");
        recipe.shape(shape.get(0), shape.get(1), shape.get(2));

        for (String key : getConfig().getConfigurationSection("spawner-recipe.ingredients").getKeys(false)) {
            Material mat = Material.valueOf(getConfig().getString("spawner-recipe.ingredients." + key));
            recipe.setIngredient(key.charAt(0), mat);
        }

        Bukkit.addRecipe(recipe);
    }

    @EventHandler
    public void onSpawnerBreak(BlockBreakEvent e) {
        if (!dropFromSpawner) return;

        if (e.getBlock().getType() == Material.SPAWNER &&
                e.getPlayer().getInventory().getItemInMainHand().containsEnchantment(Enchantment.SILK_TOUCH)) {

            e.setDropItems(false);
            CreatureSpawner cs = (CreatureSpawner) e.getBlock().getState();
            EntityType type = cs.getSpawnedType();

            if (type == null) {
                e.getPlayer().sendMessage(ChatColor.RED + "This spawner is empty and does not contain a mob!");
                return;
            }

            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation(), createSoul(type, 1));
        }
    }

    @EventHandler
    public void onMobKill(EntityDeathEvent e) {
        if (!dropSouls) return;

        Entity entity = e.getEntity();
        if (!(entity instanceof LivingEntity)) return;
        if (entity.getType() == EntityType.PLAYER) return;
        if (e.getEntity().getKiller() == null) return;
        if (!entity.getType().isAlive() || !entity.getType().isSpawnable()) return;

        if (random.nextDouble() < soulDropChance) {
            e.getDrops().add(createSoul(entity.getType(), 1));
        }
    }

    @EventHandler
    public void onUseSoul(PlayerInteractEvent e) {
        if (e.getClickedBlock() == null) return;
        if (e.getClickedBlock().getType() != Material.SPAWNER) return;

        ItemStack hand = e.getItem();
        if (hand == null || hand.getType() != soulItem) return;
        if (!hand.hasItemMeta()) return;

        ItemMeta meta = hand.getItemMeta();
        String mobName = meta.getPersistentDataContainer().get(soulKey, PersistentDataType.STRING);

        if (mobName == null) return;

        try {
            EntityType type = EntityType.valueOf(mobName);
            CreatureSpawner spawner = (CreatureSpawner) e.getClickedBlock().getState();
            spawner.setSpawnedType(type);
            spawner.update();

            hand.setAmount(hand.getAmount() - 1);

            e.getPlayer().sendMessage(msg("inserted", Map.of("mob", type.name())));
        } catch (IllegalArgumentException ex) {
            e.getPlayer().sendMessage(msg("corrupted", null));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length < 1) return false;

        if (args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("soulspawner.give")) {
                sender.sendMessage(msg("no_permission", null));
                return true;
            }

            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /ss give <mob> <count>");
                return true;
            }

            String mobName = args[1].toUpperCase();
            int count;
            try {
                count = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(ChatColor.RED + "Count must be a number!");
                return true;
            }

            EntityType type;
            try {
                type = EntityType.valueOf(mobName);
                if (!type.isSpawnable() || !type.isAlive()) {
                    sender.sendMessage(ChatColor.RED + "You can only give souls for living, spawnable mobs!");
                    return true;
                }
            } catch (IllegalArgumentException ex) {
                sender.sendMessage(msg("invalid_mob", null));
                return true;
            }

            if (!(sender instanceof org.bukkit.entity.Player player)) {
                sender.sendMessage("This command can only be used by a player.");
                return true;
            }

            player.getInventory().addItem(createSoul(type, count));
            sender.sendMessage(msg("gave_soul", Map.of("mob", type.name(), "count", String.valueOf(count))));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("soulspawner.reload")) {
                sender.sendMessage(msg("no_permission", null));
                return true;
            }

            loadConfig();
            sender.sendMessage(ChatColor.GREEN + "SoulSpawners: Configuration reloaded!");
            return true;
        }

        if (args[0].equalsIgnoreCase("chance")) {
            if (!sender.hasPermission("soulspawner.use")) {
                sender.sendMessage(msg("no_permission", null));
                return true;
            }
            double actualChance = soulDropChance * 100.0;
            String formatted = String.format("%.2f", actualChance);
            sender.sendMessage(ChatColor.GREEN + "Current soul drop chance: " + formatted + "%");
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("give");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            List<String> mobs = new ArrayList<>();
            for (EntityType type : EntityType.values()) {
                if (type.isAlive() && type.isSpawnable()) mobs.add(type.name().toLowerCase());
            }
            return mobs;
        }
        return Collections.emptyList();
    }
}
