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
 * Anti Hitbox Expand / Reach — Paper 1.21.11.
 *
 * Nguyen tac:
 *   - KHONG kick, KHONG ban, KHONG gui tin nhan canh bao.
 *   - CHI huy su kien (event.setCancelled(true)) khi phat hien bat thuong.
 *
 * Co che:
 *   1. Reach check: khoang cach ngan nhat tu MAT attacker den RIA
 *      BoundingBox THUC cua victim (khong phai center-to-center),
 *      so voi nguong dong (attribute + ping tolerance).
 *   2. Ray trace check: tia nhin tu MAT attacker phai cat BoundingBox
 *      THUC cua victim (hoac box lich su neu ping cao), trong pham vi
 *      reach hop le.
 */
public final class AntiHitboxListener implements Listener {

    // ============================================================
    // STATIC — resolve attribute mot lan duy nhat (tranh lookup moi event)
    // ============================================================
    private static final Attribute ENTITY_INTERACTION_RANGE = resolveInteractionRangeAttribute();
    private static final Attribute ATTACK_RANGE            = resolveAttackRangeAttribute();

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
        // ATTACK_RANGE chi co tu 1.21.x; wrap try/catch de an toan.
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

        // --- Tinh nguong reach dong ---
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
        // (2) RAY TRACE CHECK — tia nhin phai cat BoundingBox THUC
        // ========================================================
        final Vector eyeVec = new Vector(eyeX, eyeY, eyeZ);
        boolean hit = rayHitsBox(currentBox, eyeVec, direction, allowedRange);

        // Neu chua trung va victim ping cao -> thu box lich su (ping compensation).
        if (!hit && config.historyEnabled() && ping > 0) {
            final long targetTime = System.currentTimeMillis() - ping;
            final BoundingBox historical = tracker.getClosestBefore(victim.getUniqueId(), targetTime);
            if (historical != null) {
                hit = rayHitsBox(historical, eyeVec, direction, allowedRange);
            }
        }

        if (!hit) {
            cancel(event, "ray-trace");
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
     * Tra ve BoundingBox "hieu dung" cua victim:
     *   - Gop box cua phuong tien victim dang cuoi (boat, horse, minecart...).
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
     * Tinh base range thuc te cua attacker:
     *   - Uu tien ATTACK_RANGE (1.21+) neu co.
     *   - Fallback sang ENTITY_INTERACTION_RANGE (attribute).
     *   - Fallback cuoi cung: config survival/creative.
     */
    private double resolveBaseRange(final Player attacker, final GameMode mode) {
        final double fallback = (mode == GameMode.CREATIVE)
                ? config.creativeReach()
                : config.survivalReach();

        if (!config.useAttribute()) return fallback;

        // ATTACK_RANGE (1.21+): base attack reach thuc te
        final Double attackRange = readAttribute(attacker, ATTACK_RANGE);
        if (attackRange != null && attackRange > 0) {
            return Math.max(attackRange, fallback);
        }

        // ENTITY_INTERACTION_RANGE: tuy la interact range nhung gan dung voi attack range
        // trong vanilla 1.20.5+ (ca hai deu default 3.0 survival / 6.0 creative).
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

    /**
     * Binh phuong khoang cach ngan nhat tu diem (px, py, pz) den RIA cua box.
     * Tra ve 0 neu diem nam trong box.
     */
    private static double squaredDistanceToBox(final double px, final double py, final double pz,
                                               final BoundingBox box) {
        final double dx = axisDistanceSq(px, box.getMinX(), box.getMaxX());
        final double dy = axisDistanceSq(py, box.getMinY(), box.getMaxY());
        final double dz = axisDistanceSq(pz, box.getMinZ(), box.getMaxZ());
        return dx + dy + dz;
    }

    /**
     * Binh phuong khoang cach tren 1 truc tu point den doan [min, max].
     * Dung Math.max(0, ...) tranh if-branch -> JIT inline tot hon.
     */
    private static double axisDistanceSq(final double point,
                                         final double min,
                                         final double max) {
        final double below = min - point; // > 0 neu point < min
        final double above = point - max; // > 0 neu point > max
        final double d = Math.max(0.0, Math.max(below, above));
        return d * d;
    }
}
