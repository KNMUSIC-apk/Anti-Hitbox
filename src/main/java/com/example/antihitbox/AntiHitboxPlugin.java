package com.example.antihitbox;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class AntiHitboxPlugin extends JavaPlugin {

    private AntiHitboxConfig acConfig;
    private PositionTracker tracker;
    private AntiHitboxListener listener;
    private BukkitTask trackerTask;
    private BukkitTask cleanupTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.acConfig = AntiHitboxConfig.load(this);
        this.tracker = new PositionTracker(acConfig.historySize());
        this.listener = new AntiHitboxListener(this, acConfig, tracker);

        // Register listeners
        getServer().getPluginManager().registerEvents(listener, this);

        // Start history tracking task (1 tick interval)
        if (acConfig.historyEnabled()) {
            trackerTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                for (final Player p : Bukkit.getOnlinePlayers()) {
                    tracker.record(p.getUniqueId(), p.getBoundingBox());
                }
            }, 1L, 1L);
        }

        // Cleanup task: don dep UUID cua player da offline moi 5 phut
        cleanupTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            final Set<UUID> online = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getUniqueId)
                    .collect(Collectors.toUnmodifiableSet());
            tracker.retainOnly(online);
        }, 6000L, 6000L); // 5 phut = 6000 tick

        getLogger().info("AntiHitbox da bat. survival=" + acConfig.survivalReach()
                + ", creative=" + acConfig.creativeReach()
                + ", attribute=" + acConfig.useAttribute()
                + ", history=" + acConfig.historyEnabled());
    }

    @Override
    public void onDisable() {
        if (trackerTask != null) trackerTask.cancel();
        if (cleanupTask != null) cleanupTask.cancel();
        if (tracker != null) tracker.clear();
        getLogger().info("AntiHitbox da tat.");
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command,
                             final String label, final String[] args) {
        if (!command.getName().equalsIgnoreCase("antihitbox")) return false;
        if (!sender.hasPermission("antihitbox.admin")) {
            sender.sendMessage("§cBan khong co quyen.");
            return true;
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage("§eSu dung: /antihitbox reload");
            return true;
        }
        // Stop task cu
        if (trackerTask != null) trackerTask.cancel();
        // Reload config + tracker
        this.acConfig = AntiHitboxConfig.load(this);
        this.tracker.clear();
        // Restart tracking neu can
        if (acConfig.historyEnabled()) {
            trackerTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
                for (final Player p : Bukkit.getOnlinePlayers()) {
                    tracker.record(p.getUniqueId(), p.getBoundingBox());
                }
            }, 1L, 1L);
        }
        // Re-register listener voi config moi
        org.bukkit.event.HandlerList.unregisterAll(listener);
        this.listener = new AntiHitboxListener(this, acConfig, tracker);
        getServer().getPluginManager().registerEvents(listener, this);

        sender.sendMessage("§a[AntiHitbox] Da reload cau hinh.");
        return true;
    }
}
