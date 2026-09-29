package com.example.antihitbox;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Cau hinh immutable cua plugin.
 */
public record AntiHitboxConfig(
        // reach
        double survivalReach,
        double creativeReach,
        boolean useAttribute,
        // tolerance
        double baseTolerance,
        double perPingMs,
        int maxPing,
        double victimMargin,
        // history
        boolean historyEnabled,
        int historySize,
        // occlusion
        boolean occlusionEnabled,
        boolean occlusionIgnorePassable,
        double occlusionEpsilon,
        // debug
        boolean logCancelled
) {

    public static AntiHitboxConfig load(final AntiHitboxPlugin plugin) {
        plugin.reloadConfig();
        final FileConfiguration c = plugin.getConfig();
        return new AntiHitboxConfig(
                c.getDouble("reach.survival", 3.0),
                c.getDouble("reach.creative", 6.0),
                c.getBoolean("reach.use-attribute", true),

                c.getDouble("tolerance.base", 0.10),
                c.getDouble("tolerance.per-ping-ms", 0.0015),
                c.getInt("tolerance.max-ping", 300),
                c.getDouble("tolerance.victim-margin", 0.05),

                c.getBoolean("history.enabled", true),
                Math.max(5, c.getInt("history.size", 40)),

                c.getBoolean("occlusion.enabled", true),
                c.getBoolean("occlusion.ignore-passable", true),
                c.getDouble("occlusion.epsilon", 0.01),

                c.getBoolean("debug.log-cancelled", false)
        );
    }
}
