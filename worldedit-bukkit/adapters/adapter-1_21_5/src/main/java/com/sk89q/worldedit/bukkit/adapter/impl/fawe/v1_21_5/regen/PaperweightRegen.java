package com.sk89q.worldedit.bukkit.adapter.impl.fawe.v1_21_5.regen;

import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import com.fastasyncworldedit.bukkit.adapter.Regenerator;
import com.fastasyncworldedit.bukkit.util.FoliaLibHolder;
import com.fastasyncworldedit.bukkit.util.RegenWorldCache;
import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.queue.IChunkCache;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.implementation.chunk.ChunkCache;
import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Lifecycle;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.bukkit.adapter.Refraction;
import com.sk89q.worldedit.bukkit.adapter.impl.fawe.v1_21_5.PaperweightPlatformAdapter;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.io.file.SafeFiles;
import com.sk89q.worldedit.world.RegenOptions;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.util.ProgressListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.generator.BiomeProvider;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static net.minecraft.core.registries.Registries.BIOME;

public class PaperweightRegen extends Regenerator {

    private static final Logger LOGGER = LogManagerCompat.getLogger();
    // generation of one FULL chunk touches neighbours up to ~8 chunks out (the status pyramid);
    // 12 gives margin without meaningful cost (625 map lookups per regen)
    private static final int REGEN_PYRAMID_RADIUS = 12;
    // observability: logs the temp world's loaded chunk count every N releases so leak
    // regressions show up in the console without needing a heap dump
    private static final AtomicLong RELEASE_COUNT = new AtomicLong();
    private static final AtomicLong SWEEP_RUNS = new AtomicLong();
    private static final AtomicLong SWEEP_DRAINED = new AtomicLong();

    private static final Field serverWorldsField;
    private static final Field paperConfigField;
    private static final Field generatorSettingBaseSupplierField;
    // diagnostic-only: distinguishes unloadable holders that sit in the unload queue
    // (drain problem) from ones that never got queued (checkUnload problem)
    private static final Field inUnloadQueueField;

    static {
        try {
            serverWorldsField = CraftServer.class.getDeclaredField("worlds");
            serverWorldsField.setAccessible(true);

            Field tmpPaperConfigField;
            try { // only present on paper
                tmpPaperConfigField = Level.class.getDeclaredField("paperConfig");
                tmpPaperConfigField.setAccessible(true);
            } catch (Exception e) {
                tmpPaperConfigField = null;
            }
            paperConfigField = tmpPaperConfigField;

            generatorSettingBaseSupplierField = NoiseBasedChunkGenerator.class.getDeclaredField(Refraction.pickName(
                    "settings", "e"));
            generatorSettingBaseSupplierField.setAccessible(true);

            Field tmpInUnloadQueueField;
            try {
                tmpInUnloadQueueField = NewChunkHolder.class.getDeclaredField("inUnloadQueue");
                tmpInUnloadQueueField.setAccessible(true);
            } catch (Exception e) {
                tmpInUnloadQueueField = null;
            }
            inUnloadQueueField = tmpInUnloadQueueField;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // runtime
    private ServerLevel originalServerWorld;
    private ServerLevel freshWorld;
    private LevelStorageSource.LevelStorageAccess session;

    private Path tempDir;
    private String cacheKey;
    private boolean worldFromCache;

    public PaperweightRegen(
            World originalBukkitWorld,
            Region region,
            Extent target,
            RegenOptions options) {
        super(originalBukkitWorld, region, target, options);
    }

    @Override
    protected void runTasks(final BooleanSupplier shouldKeepTicking) {
        while (shouldKeepTicking.getAsBoolean()) {
            if (!this.freshWorld.getChunkSource().pollTask()) {
                return;
            }
        }
    }

    @Override
    protected boolean prepare() {
        this.originalServerWorld = ((CraftWorld) originalBukkitWorld).getHandle();
        seed = options.getSeed().orElse(originalServerWorld.getSeed());
        return true;
    }

    @Override
    protected boolean initNewWorld() throws Exception {
        // Folia cannot unload worlds: a fresh temp world per regenerate() call leaks its whole
        // ServerLevel graph through the global TickRegionScheduler. Reuse one cached temp world
        // per (world, environment, seed) instead; chunk unloading still works normally.
        if (FoliaLibHolder.isFolia() && !options.hasBiomeType()) {
            cacheKey = RegenWorldCache.key(originalBukkitWorld.getName(),
                    originalBukkitWorld.getEnvironment().name(), seed);
            synchronized (RegenWorldCache.lockFor(cacheKey)) {
                RegenWorldCache.Entry cached = RegenWorldCache.get(cacheKey);
                if (cached != null) {
                    freshWorld = (ServerLevel) cached.serverLevel;
                    session = (LevelStorageSource.LevelStorageAccess) cached.session;
                    tempDir = cached.tempDir;
                    worldFromCache = true;
                    return true;
                }
                return initNewWorld0();
            }
        }
        return initNewWorld0();
    }

    private boolean initNewWorld0() throws Exception {
        // world folder
        tempDir = java.nio.file.Files.createTempDirectory("FastAsyncWorldEditWorldGen");

        // prepare for world init (see upstream implementation for reference)
        org.bukkit.World.Environment environment = originalBukkitWorld.getEnvironment();
        org.bukkit.generator.ChunkGenerator generator = originalBukkitWorld.getGenerator();
        LevelStorageSource levelStorageSource = LevelStorageSource.createDefault(tempDir);
        ResourceKey<LevelStem> levelStemResourceKey = getWorldDimKey(environment);
        session = levelStorageSource.createAccess("faweregentempworld", levelStemResourceKey);
        PrimaryLevelData originalWorldData = originalServerWorld.serverLevelData;

        MinecraftServer server = originalServerWorld.getCraftServer().getServer();
        WorldOptions originalOpts = originalWorldData.worldGenOptions();
        WorldOptions newOpts = options.getSeed().isPresent()
                ? originalOpts.withSeed(OptionalLong.of(seed))
                : originalOpts;
        LevelSettings newWorldSettings = new LevelSettings(
                "faweregentempworld",
                originalWorldData.settings.gameType(),
                originalWorldData.settings.hardcore(),
                originalWorldData.settings.difficulty(),
                originalWorldData.settings.allowCommands(),
                originalWorldData.settings.gameRules(),
                originalWorldData.settings.getDataConfiguration());

        PrimaryLevelData.SpecialWorldProperty specialWorldProperty = originalWorldData.isFlatWorld()
                ? PrimaryLevelData.SpecialWorldProperty.FLAT
                : originalWorldData.isDebugWorld()
                        ? PrimaryLevelData.SpecialWorldProperty.DEBUG
                        : PrimaryLevelData.SpecialWorldProperty.NONE;
        PrimaryLevelData newWorldData = new PrimaryLevelData(newWorldSettings, newOpts, specialWorldProperty,
                Lifecycle.stable());

        BiomeProvider biomeProvider = getBiomeProvider();

        // init world
        freshWorld = Fawe.instance().getQueueHandler().sync((Supplier<ServerLevel>) () -> new ServerLevel(
                server,
                server.executor,
                session,
                newWorldData,
                originalServerWorld.dimension(),
                new LevelStem(
                        originalServerWorld.dimensionTypeRegistration(),
                        originalServerWorld.getChunkSource().getGenerator()),
                new RegenNoOpWorldLoadListener(),
                originalServerWorld.isDebug(),
                seed,
                ImmutableList.of(),
                false,
                originalServerWorld.getRandomSequences(),
                environment,
                generator,
                biomeProvider) {

            private final Holder<Biome> singleBiome = options.hasBiomeType()
                    ? DedicatedServer.getServer().registryAccess()
                            .lookupOrThrow(BIOME).asHolderIdMap().byIdOrThrow(
                                    WorldEditPlugin.getInstance().getBukkitImplAdapter()
                                            .getInternalBiomeId(options.getBiomeType()))
                    : null;

            @Override
            public @Nonnull Holder<Biome> getUncachedNoiseBiome(int biomeX, int biomeY, int biomeZ) {
                if (options.hasBiomeType()) {
                    return singleBiome;
                }
                return super.getUncachedNoiseBiome(biomeX, biomeY, biomeZ);
            }

            @Override
            public void save(
                    final ProgressListener progressListener,
                    final boolean flush,
                    final boolean savingDisabled) {
                // noop, spigot
            }

            @Override
            public void save(
                    final ProgressListener progressListener,
                    final boolean flush,
                    final boolean savingDisabled,
                    final boolean close) {
                // noop, paper
            }
        }).get();
        freshWorld.noSave = true;
        removeWorldFromWorldsMap();
        newWorldData.checkName(originalServerWorld.serverLevelData.getLevelName()); // rename to original world name
        if (paperConfigField != null) {
            paperConfigField.set(freshWorld, originalServerWorld.paperConfig());
        }

        if (FoliaLibHolder.isFolia()) {
            boolean initialised = initWorldForFolia(newWorldData);
            if (initialised && cacheKey != null) {
                RegenWorldCache.markInitialised(freshWorld);
                RegenWorldCache.put(cacheKey, freshWorld, session, tempDir);
                worldFromCache = true; // cached worlds must survive cleanup()
            }
            // ponytail: hasBiomeType() regens (manual //regen with a biome) still create a
            // throwaway world and leak it on Folia; keying the cache on biome is the upgrade path.
            return initialised;
        }

        return true;
    }

    private boolean initWorldForFolia(PrimaryLevelData worldData) throws ExecutionException, InterruptedException {
        MinecraftServer console = ((CraftServer) Bukkit.getServer()).getServer();

        ChunkPos spawnChunk = new ChunkPos(
                freshWorld.getChunkSource().randomState().sampler().findSpawnPosition());

        setRandomSpawnSelection(spawnChunk);

        CompletableFuture<Boolean> initFuture = new CompletableFuture<>();

        org.bukkit.Location spawnLocation = new org.bukkit.Location(
                freshWorld.getWorld(),
                spawnChunk.x << 4,
                64,
                spawnChunk.z << 4);
        FoliaLibHolder.getScheduler().runAtLocation(spawnLocation, task -> {
            try {
                console.initWorld(freshWorld, worldData, worldData, worldData.worldGenOptions());
                initFuture.complete(true);
            } catch (Exception e) {
                initFuture.completeExceptionally(e);
            }
        });

        return initFuture.get();
    }

    private void setRandomSpawnSelection(ChunkPos spawnChunk) {
        try {
            Field randomSpawnField = ServerLevel.class.getDeclaredField("randomSpawnSelection");
            randomSpawnField.setAccessible(true);
            randomSpawnField.set(freshWorld, spawnChunk);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to set randomSpawnSelection for Folia world initialization", e);
        }
    }

    @Override
    protected void cleanup() {
        if (worldFromCache) {
            // Folia: the temp world is shared and can never be unloaded anyway -- leave it
            // alive for the next regen, but release this regen's chunks explicitly.
            releaseCachedWorldChunks();
            return;
        }
        try {
            session.close();
        } catch (Exception ignored) {
        }

        // shutdown chunk provider
        try {
            Fawe.instance().getQueueHandler().sync(() -> {
                try {
                    freshWorld.getChunkSource().getDataStorage().cache.clear();
                    freshWorld.getChunkSource().close(false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception ignored) {
        }

        // remove world from server
        try {
            Fawe.instance().getQueueHandler().sync(this::removeWorldFromWorldsMap);
        } catch (Exception ignored) {
        }

        // delete directory
        try {
            SafeFiles.tryHardToDeleteDir(tempDir);
        } catch (Exception ignored) {
        }
    }

    /**
     * noSave=true worlds skip ChunkMap's unload processing entirely (vanilla gates
     * processUnloads behind !noSave), so every regen would pin its whole generation
     * pyramid (~150 chunks, mostly ProtoChunks) in the cached temp world forever.
     * Strip the UNLOAD_COOLDOWN tickets our chunk reads added, expire the UNKNOWN
     * tickets Moonrise backfills on every level-lowering removal, and drive the unload
     * ourselves from the temp world's region thread, after marking this regen's pyramid
     * clean: the unload-save path ignores ServerLevel.noSave, but saveChunk skips chunks
     * that are not unsaved, and mustNotSave additionally gates POI saves for full chunks.
     * ponytail: dirty POI data on ProtoChunk-status holders (village areas) can still
     * write a few KB into the temp dir; clearing PoiChunk dirty flags is the upgrade path.
     */
    private void releaseCachedWorldChunks() {
        try {
            final ServerLevel level = freshWorld;
            final Set<BlockVector2> chunks = region.getChunks();
            BlockVector3 min = region.getMinimumPoint();
            org.bukkit.Location location = new org.bukkit.Location(level.getWorld(), min.x(), min.y(), min.z());
            FoliaLibHolder.getScheduler().runAtLocation(location, task -> {
                try {
                    for (BlockVector2 chunk : chunks) {
                        for (int dx = -REGEN_PYRAMID_RADIUS; dx <= REGEN_PYRAMID_RADIUS; dx++) {
                            for (int dz = -REGEN_PYRAMID_RADIUS; dz <= REGEN_PYRAMID_RADIUS; dz++) {
                                ChunkAccess loaded = ((ChunkSystemLevel) level)
                                        .moonrise$getAnyChunkIfLoaded(chunk.x() + dx, chunk.z() + dz);
                                if (loaded == null) {
                                    continue;
                                }
                                loaded.tryMarkSaved();
                                if (loaded instanceof LevelChunk levelChunk) {
                                    levelChunk.mustNotSave = true;
                                }
                                level.getChunkSource().removeTicketWithRadius(ChunkHolderManager.UNLOAD_COOLDOWN,
                                        new ChunkPos(chunk.x() + dx, chunk.z() + dz), 0);
                            }
                        }
                    }
                    ChunkHolderManager holderManager = ((ChunkSystemServerLevel) level)
                            .moonrise$getChunkTaskScheduler().chunkHolderManager;
                    // Moonrise's removeTicketAtLevel backfills a 1-tick UNKNOWN ticket whenever a
                    // removal lowers the chunk's ticket level, and delayed tickets only die in the
                    // holder manager's expiry tick -- which noSave temp worlds never run (the same
                    // gate that made UNLOAD_COOLDOWN immortal). Each tick() call decrements every
                    // delayed ticket in the region by one, so loop: 25 covers the UNKNOWN backfills
                    // (delay 1) plus the ~20-tick PLUGIN tickets this fork's getChunkAtAsync leaves
                    // on every chunk we read. On Folia tick() is region-scoped and requires region
                    // context; we have it here.
                    for (int i = 0; i < 25; i++) {
                        holderManager.tick();
                    }
                    // processUnloads drains at most max(50, 5% of queue) per call; a few calls
                    // per regen keeps the queue near zero against ~150 queued chunks per regen.
                    for (int i = 0; i < 8; i++) {
                        holderManager.processUnloads();
                    }
                    // The FULL->INACCESSIBLE demotions of this regen's read chunks are queued as
                    // region tasks that only complete after this task returns, so they enter the
                    // unload queue behind our back -- and nothing else ever drains a noSave
                    // world's region. Sweep again a few ticks later or ~10 chunks leak per regen.
                    // ponytail: the sweep task's own region_scheduler_api ticket backfills an
                    // UNKNOWN on removal, pinning up to ~500 holders in a cluster NatureRevive
                    // never revisits; a periodic sweeper over regions with unloadable holders is
                    // the upgrade path.
                    FoliaLibHolder.getScheduler().runAtLocationLater(location, () -> {
                        try {
                            SWEEP_RUNS.incrementAndGet();
                            int start = level.getChunkSource().getLoadedChunksCount();
                            for (int i = 0; i < 25; i++) {
                                holderManager.tick();
                            }
                            int before;
                            int passes = 0;
                            do {
                                before = level.getChunkSource().getLoadedChunksCount();
                                holderManager.processUnloads();
                            } while (level.getChunkSource().getLoadedChunksCount() < before && ++passes < 32);
                            SWEEP_DRAINED.addAndGet(
                                    Math.max(0, start - level.getChunkSource().getLoadedChunksCount()));
                        } catch (Throwable e) {
                            LOGGER.error("Failed the delayed unload sweep in the FAWE temp world", e);
                        }
                    }, 10L);
                    if (RELEASE_COUNT.incrementAndGet() % 200 == 0) {
                        // observability: histogram of why holders refuse to unload plus the pinning
                        // ticket of a few samples; unlocked reads, diagnostic-only accuracy
                        Map<String, Integer> blockers = new TreeMap<>();
                        StringBuilder samples = new StringBuilder();
                        int sampled = 0;
                        int queued = 0;
                        int stranded = 0;
                        for (NewChunkHolder holder : holderManager.getChunkHolders()) {
                            String reason = holder.isSafeToUnload();
                            blockers.merge(reason == null ? "unloadable" : reason, 1, Integer::sum);
                            if (reason == null && inUnloadQueueField != null) {
                                if (inUnloadQueueField.getBoolean(holder)) {
                                    queued++;
                                } else {
                                    stranded++;
                                }
                            }
                            if (reason != null && sampled < 5) {
                                sampled++;
                                samples.append(' ').append(holder.chunkX).append(',').append(holder.chunkZ)
                                        .append('=').append(reason).append('/')
                                        .append(holderManager.getTicketDebugString(
                                                ChunkPos.asLong(holder.chunkX, holder.chunkZ)));
                            }
                        }
                        LOGGER.info("FAWE temp regen world '{}': {} chunk holders loaded; blockers: {}; "
                                        + "unloadable queued={} stranded={}; sweeps={} sweepDrained={}; samples:{}",
                                level.getWorld().getName(), level.getChunkSource().getLoadedChunksCount(),
                                blockers, queued, stranded, SWEEP_RUNS.get(), SWEEP_DRAINED.get(), samples);
                    }
                } catch (Throwable e) {
                    LOGGER.error("Failed to release regen chunks in the FAWE temp world; "
                            + "skipped unload to avoid writing chunk data to disk", e);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected World getFreshWorld() {
        return freshWorld != null ? freshWorld.getWorld() : null;
    }

    @Override
    protected IChunkCache<IChunkGet> initSourceQueueCache() {
        return new ChunkCache<>(BukkitAdapter.adapt(freshWorld.getWorld()));
    }

    /**
     * The temporary world and the target world are built from the same {@code MinecraftServer} and
     * use the same chunk coordinates, so the {@link Structure} registry instances used as map keys
     * and the chunk-key longs inside the reference sets are already valid in both worlds: the maps
     * transplant as-is, with no remapping and no re-running of structure placement.
     */
    @Override
    protected Object readStructureData(int chunkX, int chunkZ) {
        LevelChunk from = freshWorld.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (from == null) { // never generated to full status -- nothing to copy
            return null;
        }
        // Copied even when both maps are empty: the target chunk's pre-regen starts and references
        // would otherwise survive as phantoms for structures this regen replaced with terrain.
        return new Object[] {new HashMap<>(from.getAllStarts()), new HashMap<>(from.getAllReferences())};
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void applyStructureData(int chunkX, int chunkZ, Object data) {
        final Object[] snapshot = (Object[]) data;
        // Folia cannot load the target chunk synchronously from here, and ensureLoaded's completion
        // thread does not necessarily own that chunk's region, so hop onto the chunk's own thread
        // before touching its structure maps. Both setters mark the chunk unsaved themselves.
        PaperweightPlatformAdapter.ensureLoaded(originalServerWorld, chunkX, chunkZ).thenRun(
                () -> runOnChunkThread(chunkX, chunkZ, () -> {
                    LevelChunk to = originalServerWorld.getChunkSource().getChunkNow(chunkX, chunkZ);
                    if (to == null) { // unloaded again in the meantime
                        return;
                    }
                    to.setAllStarts((Map<Structure, StructureStart>) snapshot[0]);
                    to.setAllReferences((Map<Structure, LongSet>) snapshot[1]);
                    // StructureCheck keeps its own per-chunk cache of which structures start
                    // where, seeded from the chunk NBT on disk. /locate structure, vanilla's
                    // "avoid an existing structure" checks and Structure#findValidGenerationPoint
                    // all read that cache instead of the live chunk, so without this refresh they
                    // keep answering from the pre-regen data. Vanilla pairs the same call with
                    // every chunk that reaches STRUCTURE_STARTS.
                    originalServerWorld.onStructureStartsAvailable(to);
                }));
    }

    // util
    @SuppressWarnings("unchecked")
    private void removeWorldFromWorldsMap() {
        try {
            Map<String, org.bukkit.World> map = (Map<String, org.bukkit.World>) serverWorldsField
                    .get(Bukkit.getServer());
            map.remove("faweregentempworld");
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private ResourceKey<LevelStem> getWorldDimKey(org.bukkit.World.Environment env) {
        return switch (env) {
            case NETHER -> LevelStem.NETHER;
            case THE_END -> LevelStem.END;
            default -> LevelStem.OVERWORLD;
        };
    }

    private static class RegenNoOpWorldLoadListener implements ChunkProgressListener {

        private RegenNoOpWorldLoadListener() {
        }

        @Override
        public void updateSpawnPos(@Nonnull ChunkPos spawnPos) {
        }

        @Override
        public void onStatusChange(
                final @Nonnull ChunkPos pos,
                @org.jetbrains.annotations.Nullable final net.minecraft.world.level.chunk.status.ChunkStatus status) {

        }

        @Override
        public void start() {

        }

        @Override
        public void stop() {

        }

    }

}
