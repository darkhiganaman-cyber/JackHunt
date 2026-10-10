package ru.example.jackhunt;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public final class JackHuntPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private NamespacedKey roundKey;
    private final Map<UUID, Integer> scores = new HashMap<>();
    private UUID activeRoundId;
    private UUID activeItemId;
    private boolean roundActive = false;
    private BukkitTask nextRoundTask;
    private BukkitTask timeoutTask;
    private Location center;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        roundKey = new NamespacedKey(this, "jack_round_id");
        loadScores();
        loadCenter();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("jack")).setExecutor(this);
        scheduleNextRound(getConfig().getLong("interval-minutes", 60) * 60L * 20L);
        getLogger().info("JackHunt enabled.");
    }

    @Override
    public void onDisable() {
        cancelTask(nextRoundTask);
        cancelTask(timeoutTask);
        removeActiveItem();
        saveScores();
    }

    private void loadCenter() {
        World world = getEventWorld();
        if (world == null) return;
        double x = getConfig().getDouble("center.x", world.getSpawnLocation().getX());
        double z = getConfig().getDouble("center.z", world.getSpawnLocation().getZ());
        center = new Location(world, x, world.getSpawnLocation().getY(), z);
    }

    private World getEventWorld() {
        String name = getConfig().getString("world", "world");
        World world = Bukkit.getWorld(name);
        if (world == null) getLogger().warning("Мир '" + name + "' не загружен. Проверьте world в config.yml.");
        return world;
    }

    private void scheduleNextRound(long delayTicks) {
        cancelTask(nextRoundTask);
        nextRoundTask = Bukkit.getScheduler().runTaskLater(this, this::startRound, Math.max(1L, delayTicks));
    }

    public void startRound() {
        if (roundActive) return;
        World world = getEventWorld();
        if (world == null) {
            Bukkit.broadcastMessage("§6[Джек] §cИвент не запущен: мир не найден.");
            scheduleNextRound(20L * 60L);
            return;
        }
        if (center == null || center.getWorld() != world) loadCenter();

        Location spawn = findSafeLocation(world);
        if (spawn == null) {
            Bukkit.broadcastMessage("§6[Джек] §cНе удалось найти безопасное место. Попробуем позже.");
            scheduleNextRound(20L * 60L);
            return;
        }

        activeRoundId = UUID.randomUUID();
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        meta.setDisplayName("§6§lГолова Джека");
        meta.setLore(List.of("§7Хэллоуинская охота", "§eПервый подобравший получит +1 очко"));
        meta.getPersistentDataContainer().set(roundKey, PersistentDataType.STRING, activeRoundId.toString());
        head.setItemMeta(meta);

        Item item = world.dropItem(spawn, head);
        item.setCustomName("§6§lГолова Джека");
        item.setCustomNameVisible(true);
        item.setGlowing(true);
        item.setUnlimitedLifetime(true);
        activeItemId = item.getUniqueId();
        roundActive = true;

        Bukkit.broadcastMessage("§6§l🎃 ОХОТА ЗА ГОЛОВОЙ ДЖЕКА НАЧАЛАСЬ!");
        Bukkit.broadcastMessage("§eВ мире появилась голова Джека. Найди её первым и получи §a+1 очко§e!");
        Bukkit.broadcastMessage("§7Мир: §f" + world.getName() + " §8| §7Радиус поиска: §f"
                + getConfig().getInt("min-radius", 1000) + "–" + getConfig().getInt("max-radius", 5000) + " блоков");

        long timeout = Math.max(1L, getConfig().getLong("round-timeout-minutes", 10)) * 60L * 20L;
        timeoutTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!roundActive) return;
            removeActiveItem();
            roundActive = false;
            activeRoundId = null;
            Bukkit.broadcastMessage("§6[Джек] §7Время вышло! Голова исчезла. Следующий раунд скоро начнётся.");
            scheduleNextRound(getConfig().getLong("interval-minutes", 60) * 60L * 20L);
        }, timeout);
    }

    private Location findSafeLocation(World world) {
        int min = Math.max(1, getConfig().getInt("min-radius", 1000));
        int max = Math.max(min, getConfig().getInt("max-radius", 5000));
        int attempts = Math.max(1, getConfig().getInt("location-attempts", 80));
        double cx = center == null ? world.getSpawnLocation().getX() : center.getX();
        double cz = center == null ? world.getSpawnLocation().getZ() : center.getZ();

        for (int i = 0; i < attempts; i++) {
            double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
            double radius = ThreadLocalRandom.current().nextDouble(min, max + 1.0);
            int x = (int) Math.round(cx + Math.cos(angle) * radius);
            int z = (int) Math.round(cz + Math.sin(angle) * radius);

            if (!world.getWorldBorder().isInside(new Location(world, x, world.getSpawnLocation().getY(), z))) continue;
            if (!world.isChunkGenerated(x >> 4, z >> 4)) continue;

            int y = world.getHighestBlockYAt(x, z);
            if (y <= world.getMinHeight() || y + 2 >= world.getMaxHeight()) continue;
            Material ground = world.getBlockAt(x, y, z).getType();
            Material feet = world.getBlockAt(x, y + 1, z).getType();
            Material head = world.getBlockAt(x, y + 2, z).getType();

            if (!ground.isSolid() || ground == Material.MAGMA_BLOCK || ground == Material.CACTUS
                    || ground == Material.CAMPFIRE || ground == Material.SOUL_CAMPFIRE) continue;
            if (!feet.isAir() || !head.isAir()) continue;
            if (ground == Material.WATER || ground == Material.LAVA) continue;

            return new Location(world, x + 0.5, y + 1.05, z + 0.5);
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Item item = event.getItem();
        ItemMeta meta = item.getItemStack().getItemMeta();
        if (meta == null) return;
        String id = meta.getPersistentDataContainer().get(roundKey, PersistentDataType.STRING);
        if (id == null) return;

        // Уникальный ID должен совпасть с текущим активным раундом.
        if (!roundActive || activeRoundId == null || !activeRoundId.toString().equals(id)) {
            event.setCancelled(true);
            item.remove();
            return;
        }

        // Обработчик выполняется в основном потоке сервера: первый pickup завершает раунд.
        event.setCancelled(true);
        item.remove();
        if (!roundActive) return;

        roundActive = false;
        scores.merge(player.getUniqueId(), 1, Integer::sum);
        saveScores();
        cancelTask(timeoutTask);
        activeItemId = null;
        activeRoundId = null;

        Bukkit.broadcastMessage("§6§l🎃 " + player.getName() + " нашёл голову Джека и получает §a+1 очко§6!");
        player.sendMessage("§aТвой постоянный счёт: §e" + scores.getOrDefault(player.getUniqueId(), 0) + " §aочков.");
        scheduleNextRound(getConfig().getLong("interval-minutes", 60) * 60L * 20L);
    }

    private void removeActiveItem() {
        if (activeItemId == null) return;
        Entity entity = Bukkit.getEntity(activeItemId);
        if (entity != null) entity.remove();
        activeItemId = null;
    }

    private void loadScores() {
        scores.clear();
        var section = getConfig().getConfigurationSection("scores");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                scores.put(UUID.fromString(key), section.getInt(key));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Пропущен некорректный UUID в scores: " + key);
            }
        }
    }

    private void saveScores() {
        getConfig().set("scores", null);
        for (Map.Entry<UUID, Integer> entry : scores.entrySet()) {
            getConfig().set("scores." + entry.getKey(), entry.getValue());
        }
        saveConfig();
    }

    private void cancelTask(BukkitTask task) {
        if (task != null) task.cancel();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§6/jack start §7— начать раунд");
            sender.sendMessage("§6/jack stop §7— остановить ивент");
            sender.sendMessage("§6/jack top §7— рейтинг");
            sender.sendMessage("§6/jack points <ник> §7— очки игрока (UUID ищется среди онлайн)");
            sender.sendMessage("§6/jack setcenter §7— установить центр по текущей позиции");
            sender.sendMessage("§6/jack reload §7— перезагрузить конфиг");
            return true;
        }
        if (!sender.hasPermission("jackhunt.admin") && !args[0].equalsIgnoreCase("top")) {
            sender.sendMessage("§cНедостаточно прав.");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                cancelTask(nextRoundTask);
                if (roundActive) sender.sendMessage("§cРаунд уже активен.");
                else startRound();
            }
            case "stop" -> {
                cancelTask(nextRoundTask);
                cancelTask(timeoutTask);
                removeActiveItem();
                roundActive = false;
                activeRoundId = null;
                sender.sendMessage("§aИвент остановлен.");
                Bukkit.broadcastMessage("§6[Джек] §7Ивент остановлен администрацией.");
            }
            case "top" -> {
                List<Map.Entry<UUID, Integer>> top = scores.entrySet().stream()
                        .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                        .limit(10).toList();
                sender.sendMessage("§6§l=== ТОП ОХОТНИКОВ ===");
                if (top.isEmpty()) sender.sendMessage("§7Пока никто не набрал очки.");
                for (int i = 0; i < top.size(); i++) {
                    OfflinePlayer p = Bukkit.getOfflinePlayer(top.get(i).getKey());
                    String name = p.getName() == null ? top.get(i).getKey().toString().substring(0, 8) : p.getName();
                    sender.sendMessage("§e" + (i + 1) + ". §f" + name + " §7— §a" + top.get(i).getValue());
                }
            }
            case "points" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cИспользование: /jack points <ник>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("§cДля офлайн-игрока пока используйте рейтинг /jack top.");
                    return true;
                }
                sender.sendMessage("§e" + target.getName() + ": §a" + scores.getOrDefault(target.getUniqueId(), 0) + " очков");
            }
            case "setcenter" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cЭту команду нужно выполнить в игре.");
                    return true;
                }
                World w = getEventWorld();
                if (w == null || player.getWorld() != w) {
                    sender.sendMessage("§cПерейдите в мир, указанный в config.yml.");
                    return true;
                }
                center = player.getLocation().clone();
                getConfig().set("center.x", center.getX());
                getConfig().set("center.z", center.getZ());
                saveConfig();
                sender.sendMessage("§aЦентр поиска установлен на ваши текущие X/Z.");
            }
            case "reload" -> {
                reloadConfig();
                loadCenter();
                sender.sendMessage("§aКонфигурация перезагружена.");
            }
            default -> sender.sendMessage("§cНеизвестная команда. Используйте /jack.");
        }
        return true;
    }
}
