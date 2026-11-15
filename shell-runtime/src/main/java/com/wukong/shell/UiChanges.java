package com.wukong.shell;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Event callbacks only update small window-scoped counters; they never walk a UI tree. */
final class UiChanges {
    private final LinkedHashMap<Integer, long[]> windows = new LinkedHashMap<>();
    private long revision;
    synchronized void record(int window, long time) {
        if (window < 0) return;
        long[] previous = windows.get(window);
        windows.put(window, new long[]{++revision, Math.max(time, previous == null ? 0 : previous[1])});
        if (windows.size() > 1024) windows.remove(windows.keySet().iterator().next());
    }
    synchronized long[] snapshot(Collection<Integer> relevant, long now) {
        long version = 0, last = 0;
        for (int id : relevant) {
            long[] value = windows.get(id);
            if (value != null) { version = Math.max(version, value[0]); last = Math.max(last, value[1]); }
        }
        return new long[]{version, last == 0 ? 10000 : Math.min(10000, Math.max(0, now - last))};
    }
}
