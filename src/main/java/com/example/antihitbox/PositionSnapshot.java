package com.example.antihitbox;

import org.bukkit.util.BoundingBox;

/**
 * Anh chup BoundingBox cua victim tai mot thoi diem.
 * Immutable, chi luu primitive -> khong co object rac phuc tap.
 */
public record PositionSnapshot(
        long timestamp,
        double minX, double minY, double minZ,
        double maxX, double maxY, double maxZ
) {

    public static PositionSnapshot of(final long timestamp, final BoundingBox box) {
        return new PositionSnapshot(
                timestamp,
                box.getMinX(), box.getMinY(), box.getMinZ(),
                box.getMaxX(), box.getMaxY(), box.getMaxZ()
        );
    }

    public BoundingBox toBoundingBox() {
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
