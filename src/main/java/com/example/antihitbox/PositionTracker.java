package com.example.antihitbox;

import org.bukkit.util.BoundingBox;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Luu lich su BoundingBox cua entity de bu ping cho ray trace.
 *
 * - record() duoc goi tren MAIN THREAD moi tick.
 * - getClosestBefore() co the duoc goi tu bat ky thread nao (dung synchronized).
 */
public final class PositionTracker {

    private final int maxHistory;
    private final Map<UUID, Deque<PositionSnapshot>> history = new ConcurrentHashMap<>();

    public PositionTracker(final int maxHistory) {
        this.maxHistory = maxHistory;
    }

    public void record(final UUID uuid, final BoundingBox box) {
        final Deque<PositionSnapshot> deque =
                history.computeIfAbsent(uuid, k -> new ArrayDeque<>(maxHistory + 1));
        final long now = System.currentTimeMillis();
        synchronized (deque) {
            deque.addLast(PositionSnapshot.of(now, box));
            while (deque.size() > maxHistory) {
                deque.removeFirst();
            }
        }
    }

    /**
     * Tra ve snapshot moi nhat co timestamp <= targetTime.
     * Neu khong co, tra ve snapshot cu nhat (best-effort).
     */
    public BoundingBox getClosestBefore(final UUID uuid, final long targetTime) {
        final Deque<PositionSnapshot> deque = history.get(uuid);
        if (deque == null || deque.isEmpty()) return null;

        synchronized (deque) {
            PositionSnapshot best = null;
            for (final PositionSnapshot snap : deque) {
                if (snap.timestamp() <= targetTime) {
                    best = snap;
                } else {
                    break;
                }
            }
            if (best == null) {
                best = deque.peekFirst();
            }
            return best == null ? null : best.toBoundingBox();
        }
    }

    public void remove(final UUID uuid) {
        history.remove(uuid);
    }

    public void clear() {
        history.clear();
    }

    /** Don dep entry cua player da offline (goi dinh ky). */
    public void retainOnly(final Set<UUID> onlineUuids) {
        history.keySet().retainAll(onlineUuids);
    }
}
