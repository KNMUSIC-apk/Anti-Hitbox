package com.example.antihitbox;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Kiem tra block ran co chan giua mat attacker va victim khong.
 *
 * Co che:
 *   1. Ray trace tu mat attacker den BoundingBox THAT cua victim
 *      -> khoang cach entityHitDistance.
 *   2. Ray trace tu mat attacker den block ran dau tien
 *      -> khoang cach blockHitDistance.
 *   3. Neu blockHitDistance < entityHitDistance - epsilon
 *      -> co block chan -> occluded.
 *
 * ignorePassableBlocks = true: bo qua co, bien quang cao, hoa, chat long.
 * FluidCollisionMode.NEVER: khong tinh nuoc/lava la vat can.
 */
public final class BlockOcclusionChecker {

    private BlockOcclusionChecker() {
        // utility class
    }

    /**
     * @param world              world chua attacker
     * @param eyeLoc             vi tri mat attacker (co the tai su dung de tranh alloc)
     * @param direction          huong nhin attacker (da normalize hoac chua deu OK)
     * @param victimBox          BoundingBox THAT cua victim
     * @param maxRange           khoang cach toi da can kiem tra
     * @param ignorePassable     bo qua block passable (co, hoa, bien quang cao...)
     * @param epsilon            sai so (block) khi so sanh khoang cach
     * @return true neu co block ran chan giua mat attacker va victim
     */
    public static boolean isOccluded(final World world,
                                     final Location eyeLoc,
                                     final Vector direction,
                                     final BoundingBox victimBox,
                                     final double maxRange,
                                     final boolean ignorePassable,
                                     final double epsilon) {

        final Vector eyeVec = eyeLoc.toVector();

        // (1) Tia nhin co cat victim box khong?
        final RayTraceResult entityHit = victimBox.rayTrace(eyeVec, direction, maxRange);
        if (entityHit == null) {
            // Khong the xac dinh diem va cham -> coi nhu bi chan (an toan)
            return true;
        }
        final double entityDistance = entityHit.getHitPosition().distance(eyeVec);

        // (2) Tim block ran dau tien tren tia nhin.
        //     ignorePassableBlocks = true -> bo qua co, bien quang cao, hoa...
        //     FluidCollisionMode.NEVER -> khong tinh nuoc/lava la vat can.
        final RayTraceResult blockHit = world.rayTraceBlocks(
                eyeLoc,
                direction,
                maxRange,
                FluidCollisionMode.NEVER,
                ignorePassable
        );

        // (3) Khong co block nao chan -> khong occluded.
        if (blockHit == null) {
            return false;
        }

        // (4) So sanh khoang cach.
        final double blockDistance = blockHit.getHitPosition().distance(eyeVec);
        return blockDistance < entityDistance - epsilon;
    }
}
