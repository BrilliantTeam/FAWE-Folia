package com.fastasyncworldedit.bukkit.util;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * On Folia, worlds can never be unloaded: once a temp regen world's regions are
 * registered in the global TickRegionScheduler, the whole ServerLevel graph stays
 * reachable forever. Creating one temp world per regenerate() call therefore leaks
 * ~27MB per chunk regen (observed: 32GB in 20min at 1 regen/sec).
 *
 * Fix: keep ONE temp world per (world, environment, seed) and reuse it for every
 * regen. Chunk unloading still works on Folia, so the steady-state cost is one
 * extra ServerLevel per dimension instead of an unbounded leak.
 *
 * Values are stored as Object because ServerLevel/LevelStorageAccess are
 * version-specific NMS types; each adapter casts them back.
 */
public final class RegenWorldCache {

    public static final class Entry {

        public final Object serverLevel;
        public final Object session;
        public final Path tempDir;

        Entry(Object serverLevel, Object session, Path tempDir) {
            this.serverLevel = serverLevel;
            this.session = session;
            this.tempDir = tempDir;
        }

    }

    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    private RegenWorldCache() {
    }

    public static String key(String worldName, String environment, long seed) {
        return worldName + "|" + environment + "|" + seed;
    }

    public static Entry get(String key) {
        return CACHE.get(key);
    }

    /**
     * Lock object for the given key. Hold it across the check-then-create of the
     * temp world so concurrent regens of the same world wait for the first
     * creation instead of each leaking a world of their own. Never acquired from
     * the global/region threads (regenerate() runs on async threads), so blocking
     * inside it while the creation syncs to the global thread cannot deadlock.
     */
    public static Object lockFor(String key) {
        return LOCKS.computeIfAbsent(key, k -> new Object());
    }

    public static void put(String key, Object serverLevel, Object session, Path tempDir) {
        CACHE.put(key, new Entry(serverLevel, session, tempDir));
    }

    /**
     * Best effort, misteln-folia specific: mark the temp world's
     * {@code ServerLevel.checkInitialised} as WORLD_INIT_CHECKED so the first
     * region tick does not run MinecraftServer.initWorld a second time (we already
     * ran it ourselves in initWorldForFolia). No-op when the field is absent.
     */
    public static void markInitialised(Object serverLevel) {
        try {
            Field field = null;
            Class<?> cls = serverLevel.getClass(); // anonymous subclass; walk up to ServerLevel
            while (cls != null) {
                try {
                    field = cls.getDeclaredField("checkInitialised");
                    break;
                } catch (NoSuchFieldException e) {
                    cls = cls.getSuperclass();
                }
            }
            if (field == null) {
                return;
            }
            field.setAccessible(true);
            Field checked = cls.getDeclaredField("WORLD_INIT_CHECKED");
            checked.setAccessible(true);
            ((AtomicInteger) field.get(serverLevel)).set(checked.getInt(null));
        } catch (Throwable ignored) {
        }
    }

}
