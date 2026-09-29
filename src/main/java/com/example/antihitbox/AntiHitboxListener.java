package com.example.antihitbox;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Anti Hitbox Expand / Reach / Wall-clip — Paper 1.21.11.
 *
 * Nguyen tac:
 *   - KHONG kick, KHONG ban, KHONG gui tin nhan canh bao.
 *   - CHI huy su kien khi phat hien bat thuong.
 *
 * 4 lop check:
 *   (1) Reach:    khoang cach ngan nhat tu MAT attacker den RIA box that victim.
 *   (2) Ray trace: tia nhin phai cat BoundingBox THAT cua victim.
 *   (3) Occlusion: khong co block ran chan giua mat va victim.
 *   (4) Ping compensation: neu victim ping cao, thu box lich su.
 */
public final class AntiHitboxListener implements Listener {

    // ============================================================
    // STATIC — resolve attribute mot lan duy nhat
    // ============================================================
    private static final Attribute ENTITY_INTERACTION_RANGE = resolveInteractionRangeAttribute();
    private static final Attribute ATTACK_RANGE              = resolveAttackRangeAttribute();

    private static Attribute resolveInteractionRangeAttribute() {
        final String[] keys = {
                "player.entity_interaction_range",
                "generic.entity_interaction_range"
        };
        for (final String key : keys) {
            try {
                final Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
                if (a != null) return a;
            } catch (final Throwable ignored) { /* version mismatch */ }
        }
        final String[] names = { "ENTITY_INTERACTION_RANGE", "GENERIC_ENTITY_INTERACTION_RANGE" };
        for (final String name : names) {
            try { return Attribute.valueOf(name); } catch (final Throwable ignored) {}
        }
        return null;
    }

    private static Attribute resolveAttackRangeAttribute() {
        try {
            return Attribute.valueOf("ATTACK_RANGE");
        } catch (final Throwable ignored) {
            return null;
        }
    }

    // ============================================================
    // INSTANCE
    // ============================================================
    private final AntiHitboxPlugin plugin;
    private final AntiHitboxConfig config;
    private final PositionTracker tracker;

    public AntiHitboxListener(final AntiHitboxPlugin plugin,
                              final AntiHitboxConfig config,
                              final PositionTracker tracker) {
        this.plugin = plugin;
        this.config = config;
        this.tracker = tracker;
    }

    // ============================================================
    // MAIN HANDLER
    // ============================================================
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(final EntityDamageByEntityEvent event) {

        // Chi quan tam melee: player -> entity
        if (!(event.getDamager() instanceof Player attacker)) return;

        final GameMode mode = attacker.getGameMode();
        if (mode == GameMode.SPECTATOR) return;

        final Entity victim = event.getEntity();
        if (victim.isDead()) return;
        if (!(victim instanceof LivingEntity)) return;

        // --- Lay vi tri mat + huong nhin ---
        final Location eye = attacker.getEyeLocation();
        final double eyeX = eye.getX();
        final double eyeY = eye.getY();
        final double eyeZ = eye.getZ();

        final Vector direction = eye.getDirection();
        if (direction.lengthSquared() < 1.0e-6) return;

        // --- BoundingBox THUC cua victim (gom vehicle + dragon parts) ---
        final BoundingBox currentBox = resolveEffectiveBox(victim);

        // --- Nguong reach dong ---
        final double baseRange = resolveBaseRange(attacker, mode);
        final int ping = Math.min(Math.max(0, attacker.getPing()), config.maxPing());
        final double tolerance = config.baseTolerance() + ping * config.perPingMs();
        final double allowedRange = baseRange + tolerance;
        final double allowedRangeSq = allowedRange * allowedRange;

        // ========================================================
        // (1) REACH CHECK — so sanh binh phuong, khong sqrt
        // ========================================================
        final double distSq = squaredDistanceToBox(eyeX, eyeY, eyeZ, currentBox);
        if (distSq > allowedRangeSq) {
            cancel(event, "reach");
            return;
        }

        // ========================================================
        // (2) RAY TRACE CHECK — tia nhin phai cat BoundingBox THAT
        // ========================================================
        final Vector eyeVec = new Vector(eyeX, eyeY, eyeZ);
        boolean hit = rayHitsBox(currentBox, eyeVec, direction, allowedRange);
        BoundingBox hitBox = currentBox;

        // Ping compensation: neu victim ping cao, thu box lich su
        if (!hit && config.historyEnabled() && ping > 0) {
            final long targetTime = System.currentTimeMillis() - ping;
            final BoundingBox historical = tracker.getClosestBefore(victim.getUniqueId(), targetTime);
            if (historical != null && rayHitsBox(historical, eyeVec, direction, allowedRange)) {
                hit = true;
                hitBox = historical;
            }
        }

        if (!hit) {
            cancel(event, "ray-trace");
            return;
        }

        // ========================================================
        // (3) BLOCK OCCLUSION CHECK — khong the danh xuyen block
        //     Dung BoundingBox goc cua victim (hoac box lich su
        //     neu da fallback) de tinh dung khoang cach entityHit.
        // ========================================================
        if (config.occlusionEnabled()) {
            final boolean occluded = BlockOcclusionChecker.isOccluded(
                    attacker.getWorld(),
                    eye,
                    direction,
                    hitBox,
                    allowedRange,
                    config.occlusionIgnorePassable(),
                    config.occlusionEpsilon()
            );
            if (occluded) {
                cancel(event, "wall");
            }
        }
    }

    // ============================================================
    // CLEANUP
    // ============================================================
    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        tracker.remove(event.getPlayer().getUniqueId());
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private void cancel(final EntityDamageByEntityEvent event, final String reason) {
        event.setCancelled(true);
        if (config.logCancelled()) {
            final String name = event.getDamager() instanceof Player p ? p.getName() : "?";
            plugin.getLogger().info("[AntiHitbox] Cancelled (" + reason + ") from " + name);
        }
    }

    /**
     * BoundingBox "hieu dung" cua victim:
     *   - Gop box cua phuong tien victim dang cuoi.
     *   - Gop box cac part cua EnderDragon.
     */
    private static BoundingBox resolveEffectiveBox(final Entity victim) {
        BoundingBox box = victim.getBoundingBox();

        final Entity vehicle = victim.getVehicle();
        if (vehicle != null) {
            box = box.union(vehicle.getBoundingBox());
        }

        if (victim instanceof EnderDragon dragon) {
            for (final ComplexEntityPart part : dragon.getParts()) {
                box = box.union(part.getBoundingBox());
            }
        }

        return box;
    }

    /**
     * Base range thuc te cua attacker:
     *   - Uu tien ATTACK_RANGE (1.21+).
     *   - Fallback ENTITY_INTERACTION_RANGE.
     *   - Fallback config survival/creative.
     */
    private double resolveBaseRange(final Player attacker, final GameMode mode) {
        final double fallback = (mode == GameMode.CREATIVE)
                ? config.creativeReach()
                : config.survivalReach();

        if (!config.useAttribute()) return fallback;

        final Double attackRange = readAttribute(attacker, ATTACK_RANGE);
        if (attackRange != null && attackRange > 0) {
            return Math.max(attackRange, fallback);
        }

        final Double interaction = readAttribute(attacker, ENTITY_INTERACTION_RANGE);
        if (interaction != null && interaction > 0) {
            return Math.max(interaction, fallback);
        }

        return fallback;
    }

    private static Double readAttribute(final Player player, final Attribute attr) {
        if (attr == null) return null;
        try {
            final AttributeInstance inst = player.getAttribute(attr);
            return inst == null ? null : inst.getValue();
        } catch (final Throwable ignored) {
            return null;
        }
    }

    /**
     * Ray trace tia nhin tu mat den BoundingBox.
     *   - Uu tien box goc (strict).
     *   - Neu truot, thu box expand nhe (victim margin) de bu lag nhe.
     */
    private boolean rayHitsBox(final BoundingBox box,
                               final Vector eye,
                               final Vector direction,
                               final double maxDistance) {
        if (box.rayTrace(eye, direction, maxDistance) != null) return true;

        final double margin = config.victimMargin();
        if (margin <= 0) return false;

        final BoundingBox expanded = box.clone().expand(margin);
        return expanded.rayTrace(eye, direction, maxDistance) != null;
    }

    // ============================================================
    // TOAN HOC — khong sqrt, khong object rac
    // ============================================================

    private static double squaredDistanceToBox(final double px, final double py, final double pz,
                                               final BoundingBox box) {
        final double dx = axisDistanceSq(px, box.getMinX(), box.getMaxX());
        final double dy = axisDistanceSq(py, box.getMinY(), box.getMaxY());
        final double dz = axisDistanceSq(pz, box.getMinZ(), box.getMaxZ());
        return dx + dy + dz;
    }

    private static double axisDistanceSq(final double point,
                                         final double min,
                                         final double max) {
        final double below = min - point;
        final double above = point - max;
        final double d = Math.max(0.0, Math.max(below, above));
        return d * d;
    }
}
