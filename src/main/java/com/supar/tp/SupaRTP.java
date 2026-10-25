package com.supar.tp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class SupaRTP extends JavaPlugin implements Listener {
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, TeleportSession> sessions = new HashMap<>();
    private YamlConfiguration gui;
    private YamlConfiguration messages;
    private File guiFile;
    private File messagesFile;

    private static final Map<String, String> DESTINATIONS = Map.of(
            "overworld", "worlds.overworld",
            "nether", "worlds.nether",
            "end", "worlds.end"
    );

    @Override
    public void onEnable() {
        saveDefaultConfig();
        guiFile = new File(getDataFolder(), "gui.yml");
        messagesFile = new File(getDataFolder(), "messages.yml");
        saveIfMissing("gui.yml", guiFile);
        saveIfMissing("messages.yml", messagesFile);
        loadCustomFiles();
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("rtp")).setExecutor(this);
        Objects.requireNonNull(getCommand("rtp")).setTabCompleter((sender, command, alias, args) -> {
            if (args.length == 1) return List.of("world", "world_nether", "world_the_end", "overworld", "nether", "end");
            return List.of();
        });
        getLogger().info("SupaRTP enabled.");
    }

    @Override
    public void onDisable() {
        for (TeleportSession session : sessions.values()) {
            if (session.task != null) session.task.cancel();
        }
        sessions.clear();
    }

    private void saveIfMissing(String name, File file) {
        if (!file.exists()) saveResource(name, false);
    }

    private void loadCustomFiles() {
        gui = YamlConfiguration.loadConfiguration(guiFile);
        messages = YamlConfiguration.loadConfiguration(messagesFile);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("supar.tp.admin")) {
                send(sender, "no-permission");
                return true;
            }
            reloadConfig();
            loadCustomFiles();
            send(sender, "reloaded");
            return true;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("supar.tp.use")) {
            send(player, "no-permission");
            return true;
        }
        if (args.length == 0) {
            openMenu(player);
            return true;
        }
        String key = normalizeDestination(args[0]);
        if (key == null) {
            send(player, "unknown-destination");
            return true;
        }
        startTeleport(player, key);
        return true;
    }

    private String normalizeDestination(String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "world", "overworld" -> "overworld";
            case "world_nether", "nether" -> "nether";
            case "world_the_end", "end", "the_end" -> "end";
            default -> null;
        };
    }

    private void openMenu(Player player) {
        int size = gui.getInt("size", 27);
        if (size < 9 || size > 54 || size % 9 != 0) size = 27;
        Inventory inventory = Bukkit.createInventory(null, size, text(gui.getString("title", "<#37BFF8>SupaRTP")));
        if (gui.getBoolean("filler.enabled", true)) {
            Material material = material(gui.getString("filler.material"), Material.BLACK_STAINED_GLASS_PANE);
            ItemStack filler = item(material, gui.getString("filler.name", " "));
            for (int i = 0; i < size; i++) inventory.setItem(i, filler);
        }
        for (String key : DESTINATIONS.keySet()) {
            String path = "destinations." + key;
            if (!gui.getBoolean(path + ".enabled", true)) continue;
            int slot = gui.getInt(path + ".slot", -1);
            if (slot < 0 || slot >= size) continue;
            Material material = material(gui.getString(path + ".material"), Material.PAPER);
            ItemStack item = item(material, gui.getString(path + ".name", key));
            ItemMeta meta = item.getItemMeta();
            List<String> lore = gui.getStringList(path + ".lore");
            if (!lore.isEmpty()) meta.lore(lore.stream().map(this::text).toList());
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
        }
        player.openInventory(inventory);
    }

    private ItemStack item(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(text(name == null ? " " : name));
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!event.getView().title().equals(text(gui.getString("title", "<#37BFF8>SupaRTP")))) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) return;
        int slot = event.getRawSlot();
        for (String key : DESTINATIONS.keySet()) {
            String path = "destinations." + key;
            if (gui.getBoolean(path + ".enabled", true) && gui.getInt(path + ".slot", -1) == slot) {
                player.closeInventory();
                startTeleport(player, key);
                return;
            }
        }
    }

    private void startTeleport(Player player, String key) {
        if (sessions.containsKey(player.getUniqueId())) {
            send(player, "already-teleporting");
            return;
        }
        long remaining = cooldowns.getOrDefault(player.getUniqueId(), 0L) - System.currentTimeMillis();
        if (remaining > 0) {
            send(player, "cooldown", Map.of("time", formatDuration(remaining)));
            return;
        }
        String worldName = getConfig().getString(DESTINATIONS.get(key), "");
        World world = Bukkit.getWorld(worldName == null ? "" : worldName);
        if (world == null) {
            send(player, "world-unavailable");
            return;
        }
        TeleportSession session = new TeleportSession(player.getLocation().clone(), key);
        sessions.put(player.getUniqueId(), session);
        session.task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!player.isOnline()) {
                clearSession(player.getUniqueId());
                return;
            }
            if (session.secondsLeft > 0) {
                String message = messages.getString("countdown", "<#A7A7A7>You will teleport in <#37BFF8><seconds><#A7A7A7> seconds.")
                        .replace("<seconds>", String.valueOf(session.secondsLeft));
                if (getConfig().getBoolean("show-chat-countdown", true)) sendRaw(player, message);
                if (getConfig().getBoolean("show-actionbar-countdown", true)) player.sendActionBar(text(message));
                session.secondsLeft--;
                return;
            }
            send(player, "finding-location");
            Location target = findSafeLocation(world, key);
            clearSession(player.getUniqueId());
            if (target == null) {
                send(player, "no-safe-location");
                return;
            }
            cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + getConfig().getLong("cooldown-seconds", 120) * 1000L);
            player.teleportAsync(target).thenAccept(success -> Bukkit.getScheduler().runTask(this, () -> {
                if (success && player.isOnline()) {
                    player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                    send(player, "success");
                }
            }));
        }, 0L, 20L);
    }

    private Location findSafeLocation(World world, String key) {
        int radius = Math.max(100, getConfig().getInt("radius." + key, 5000));
        int attempts = Math.max(1, getConfig().getInt("random-location-attempts", 80));
        Location spawn = world.getSpawnLocation();
        WorldBorder border = world.getWorldBorder();
        for (int i = 0; i < attempts; i++) {
            double x = spawn.getX() + (ThreadLocalRandom.current().nextDouble(-radius, radius));
            double z = spawn.getZ() + (ThreadLocalRandom.current().nextDouble(-radius, radius));
            if (!border.isInside(new Location(world, x, spawn.getY(), z))) continue;
            int blockX = (int) Math.floor(x);
            int blockZ = (int) Math.floor(z);
            int minY = world.getMinHeight() + 1;
            int maxY = world.getMaxHeight() - 2;
            if (world.getEnvironment() == World.Environment.NETHER) {
                minY = Math.max(minY, 32);
                maxY = Math.min(maxY, 118);
            }
            Location candidate;
            if (world.getEnvironment() == World.Environment.NETHER) {
                // Avoid the bedrock roof: sample a position in the playable Nether height range.
                int y = ThreadLocalRandom.current().nextInt(minY, maxY + 1);
                candidate = new Location(world, blockX + 0.5, y, blockZ + 0.5);
            } else {
                int y = world.getHighestBlockYAt(blockX, blockZ);
                if (y < minY || y > maxY) continue;
                candidate = new Location(world, blockX + 0.5, y + 1, blockZ + 0.5);
            }
            if (isSafe(candidate)) return candidate;
        }
        return null;
    }

    private boolean isSafe(Location location) {
        Block feet = location.getBlock();
        Block head = location.clone().add(0, 1, 0).getBlock();
        Block floor = location.clone().subtract(0, 1, 0).getBlock();
        if (!feet.isPassable() || !head.isPassable()) return false;
        Material floorType = floor.getType();
        if (!floorType.isSolid() || floor.isLiquid()) return false;
        if (floorType.name().contains("MAGMA") || floorType.name().contains("CACTUS")
                || floorType.name().contains("CAMPFIRE") || floorType.name().contains("LAVA")
                || floorType.name().contains("FIRE") || floorType.name().contains("POWDER_SNOW")) return false;
        return location.getY() > location.getWorld().getMinHeight()
                && location.getY() < location.getWorld().getMaxHeight() - 1;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        TeleportSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || event.getTo() == null) return;
        if (event.getFrom().getWorld() != event.getTo().getWorld()) return;
        double distance = Math.sqrt(Math.pow(event.getTo().getX() - session.start.getX(), 2)
                + Math.pow(event.getTo().getY() - session.start.getY(), 2)
                + Math.pow(event.getTo().getZ() - session.start.getZ(), 2));
        if (distance > getConfig().getDouble("movement-cancel-distance", 0.5)) {
            clearSession(event.getPlayer().getUniqueId());
            send(event.getPlayer(), "cancelled-move");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearSession(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        clearSession(event.getPlayer().getUniqueId());
    }

    private void clearSession(UUID uuid) {
        TeleportSession session = sessions.remove(uuid);
        if (session != null && session.task != null) session.task.cancel();
    }

    private void send(CommandSender sender, String key) {
        send(sender, key, Map.of());
    }

    private void send(CommandSender sender, String key, Map<String, String> replacements) {
        String prefix = messages.getString("prefix", "");
        String body = messages.getString(key, "<#FF6961>Missing message: " + key);
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            body = body.replace("<" + entry.getKey() + ">", entry.getValue());
        }
        sendRaw(sender, prefix + body);
    }

    private void sendRaw(CommandSender sender, String message) {
        sender.sendMessage(text(message));
    }

    private Component text(String input) {
        return mini.deserialize(input == null ? "" : input).decoration(TextDecoration.ITALIC, false);
    }

    private Material material(String name, Material fallback) {
        if (name == null) return fallback;
        Material found = Material.matchMaterial(name);
        return found == null ? fallback : found;
    }

    private String formatDuration(long millis) {
        long seconds = Math.max(1, (millis + 999) / 1000);
        long minutes = seconds / 60;
        long remainder = seconds % 60;
        return minutes > 0 ? minutes + "m " + remainder + "s" : remainder + "s";
    }

    private static final class TeleportSession {
        private final Location start;
        private final String destination;
        private int secondsLeft;
        private BukkitTask task;

        private TeleportSession(Location start, String destination) {
            this.start = start;
            this.destination = destination;
            this.secondsLeft = 5;
        }
    }
}