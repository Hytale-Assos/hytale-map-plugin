package assos.hytale.map.render;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import assos.hytale.map.stats.MapStats;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Extracts chunk snapshots on the owning world thread and caches them.
 *
 * <p>All chunk reads happen on the world thread. Chunks that leave memory are
 * persisted to a {@link RenderCache}, so previously visited areas render
 * without any world-side cost. Loading chunks from the world storage on
 * demand is possible but expensive for the server (full pre-load pipeline),
 * so it is opt-in and rate-limited.</p>
 */
public final class ChunkSnapshotService {

    private static final int MAX_CACHED_CHUNKS = 8192;
    private static final int SURFACE_SEARCH_DEPTH = 16;
    private static final int FLUID_SEARCH_DEPTH = 16;
    private static final float FLUID_OPACITY = 0.68f;
    private static final int MAX_CONCURRENT_STORAGE_LOADS = 8;

    private final BlockPalette palette;
    private final FluidPalette fluidPalette;
    private final RenderCache renderCache;
    private final boolean loadChunksOnDemand;
    private final Semaphore storageLoadSlots = new Semaphore(MAX_CONCURRENT_STORAGE_LOADS);
    private final MapStats stats;
    private final Map<Long, ChunkSnapshot> cache = Collections.synchronizedMap(
            new LinkedHashMap<Long, ChunkSnapshot>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, ChunkSnapshot> eldest) {
                    return size() > MAX_CACHED_CHUNKS;
                }
            });

    public ChunkSnapshotService(@Nonnull BlockPalette palette, @Nonnull FluidPalette fluidPalette,
            @Nonnull RenderCache renderCache, boolean loadChunksOnDemand, @Nonnull MapStats stats) {
        this.palette = palette;
        this.fluidPalette = fluidPalette;
        this.renderCache = renderCache;
        this.loadChunksOnDemand = loadChunksOnDemand;
        this.stats = stats;
    }

    /**
     * Returns a future completed with the snapshot of the given chunk. Loaded
     * chunks are captured on the world thread and persisted; unloaded chunks
     * fall back to the disk render cache, and only when
     * {@code loadChunksOnDemand} is enabled to a rate-limited storage load.
     * Chunks with no data anywhere complete with
     * {@link ChunkSnapshot#unloaded(int, int)} and are not cached, so a later
     * request retries once the chunk exists.
     *
     * @param world the world the chunks belong to
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return a future completed on the world thread
     */
    @Nonnull
    public CompletableFuture<ChunkSnapshot> snapshot(@Nonnull World world, int chunkX, int chunkZ) {
        long key = ChunkUtil.indexChunk(chunkX, chunkZ);
        ChunkSnapshot cached = cache.get(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        ChunkSnapshot persisted = renderCache.load(key, chunkX, chunkZ);
        if (persisted != null) {
            stats.recordDiskCacheHit();
            cache.put(key, persisted);
            return CompletableFuture.completedFuture(persisted);
        }
        CompletableFuture<ChunkSnapshot> future = new CompletableFuture<>();
        world.execute(() -> loadAndCapture(world, key, chunkX, chunkZ, future));
        return future;
    }

    /**
     * Captures the final state of a chunk that is about to leave memory, so
     * the map keeps rendering it without reloading it. Must run on the world
     * thread; the persistence itself happens asynchronously.
     *
     * @param chunk the chunk being unloaded
     */
    public void persistOnUnload(@Nonnull WorldChunk chunk) {
        @SuppressWarnings("deprecation")
        long key = chunk.getIndex();
        long start = System.nanoTime();
        ChunkSnapshot snapshot = capture(chunk.getX(), chunk.getZ(), chunk);
        stats.recordSnapshotCapture(System.nanoTime() - start);
        renderCache.store(key, snapshot);
        cache.remove(key);
    }

    /**
     * Captures the chunk if it is already in memory, otherwise loads it from
     * the world storage first. The storage load completes on the world thread,
     * so the capture runs there too.
     */
    private void loadAndCapture(@Nonnull World world, long key, int chunkX, int chunkZ,
            @Nonnull CompletableFuture<ChunkSnapshot> future) {
        @SuppressWarnings("deprecation")
        WorldChunk chunk = world.loadChunkIfInMemory(key);
        if (chunk != null) {
            completeWithCapture(key, chunkX, chunkZ, chunk, future);
            return;
        }
        if (!loadChunksOnDemand) {
            future.complete(ChunkSnapshot.unloaded(chunkX, chunkZ));
            return;
        }
        stats.recordOnDemandLoad();
        try {
            storageLoadSlots.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.complete(ChunkSnapshot.unloaded(chunkX, chunkZ));
            return;
        }
        world.getNonTickingChunkAsync(key).whenComplete((loaded, error) -> {
            storageLoadSlots.release();
            if (error != null) {
                future.complete(ChunkSnapshot.unloaded(chunkX, chunkZ));
                return;
            }
            completeWithCapture(key, chunkX, chunkZ, loaded, future);
        });
    }

    private void completeWithCapture(long key, int chunkX, int chunkZ, @Nullable WorldChunk chunk,
            @Nonnull CompletableFuture<ChunkSnapshot> future) {
        ChunkSnapshot snapshot;
        if (chunk != null) {
            long start = System.nanoTime();
            snapshot = capture(chunkX, chunkZ, chunk);
            stats.recordSnapshotCapture(System.nanoTime() - start);
        } else {
            snapshot = ChunkSnapshot.unloaded(chunkX, chunkZ);
        }
        if (snapshot.loaded()) {
            synchronized (cache) {
                cache.put(key, snapshot);
            }
            renderCache.store(key, snapshot);
        }
        future.complete(snapshot);
    }

    /**
     * Drops the cached snapshot of a chunk so the next request recaptures it.
     *
     * @param chunkKey the chunk key, as computed by {@link ChunkUtil#indexChunk(int, int)}
     */
    public void invalidate(long chunkKey) {
        cache.remove(chunkKey);
    }

    public void clear() {
        cache.clear();
    }

    public int cacheSize() {
        return cache.size();
    }

    @SuppressWarnings("deprecation")
    @Nonnull
    private ChunkSnapshot capture(int chunkX, int chunkZ, @Nonnull WorldChunk chunk) {
        BlockChunk blockChunk = chunk.getBlockChunk();
        short[] heights = new short[ChunkSnapshot.COLUMNS];
        int[] colors = new int[ChunkSnapshot.COLUMNS];
        for (int localZ = 0; localZ < ChunkSnapshot.CHUNK_SIZE; localZ++) {
            for (int localX = 0; localX < ChunkSnapshot.CHUNK_SIZE; localX++) {
                short height = blockChunk.getHeight(localX, localZ);
                int surfaceY = findSurfaceBlockY(chunk, localX, localZ, height);
                int surfaceBlockId =
                        surfaceY >= 0 ? chunk.getBlock(localX, surfaceY, localZ) : 0;
                heights[localX + localZ * ChunkSnapshot.CHUNK_SIZE] = height;
                colors[localX + localZ * ChunkSnapshot.CHUNK_SIZE] =
                        columnColor(chunk, localX, localZ, surfaceY, surfaceBlockId);
            }
        }
        return ChunkSnapshot.captured(chunkX, chunkZ, heights, colors);
    }

    /**
     * Surface color of one column: the surface block color, blended with the
     * fluid color when the column is flooded above it.
     */
    @SuppressWarnings("deprecation")
    private int columnColor(@Nonnull WorldChunk chunk, int localX, int localZ,
            int surfaceY, int surfaceBlockId) {
        int blockColor = palette.isRenderable(surfaceBlockId) ? palette.color(surfaceBlockId) : 0;
        int fluidId = topFluidAbove(chunk, localX, localZ, surfaceY);
        if (!fluidPalette.isFluid(fluidId)) {
            return blockColor;
        }
        return blendOver(fluidPalette.color(fluidId), blockColor);
    }

    @SuppressWarnings("deprecation")
    private int topFluidAbove(@Nonnull WorldChunk chunk, int localX, int localZ, int surfaceY) {
        if (surfaceY < 0) {
            return 0;
        }
        int topFluidId = 0;
        int maxY = Math.min(surfaceY + FLUID_SEARCH_DEPTH, ChunkUtil.HEIGHT - 1);
        for (int y = surfaceY + 1; y <= maxY; y++) {
            int fluidId = chunk.getFluidId(localX, y, localZ);
            if (fluidPalette.isFluid(fluidId)) {
                topFluidId = fluidId;
            }
        }
        return topFluidId;
    }

    private static int blendOver(int fluidColor, int blockColor) {
        if (blockColor == 0) {
            return fluidColor;
        }
        float keep = 1.0f - FLUID_OPACITY;
        int red = (int) Math.min(255, ((fluidColor >> 16) & 0xFF) * FLUID_OPACITY
                + ((blockColor >> 16) & 0xFF) * keep);
        int green = (int) Math.min(255, ((fluidColor >> 8) & 0xFF) * FLUID_OPACITY
                + ((blockColor >> 8) & 0xFF) * keep);
        int blue = (int) Math.min(255, (fluidColor & 0xFF) * FLUID_OPACITY
                + (blockColor & 0xFF) * keep);
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    @SuppressWarnings("deprecation")
    private int findSurfaceBlockY(@Nonnull WorldChunk chunk, int localX, int localZ, int height) {
        int y = Math.min(height, ChunkUtil.HEIGHT - 1);
        int blockId = chunk.getBlock(localX, y, localZ);
        int guard = SURFACE_SEARCH_DEPTH;
        while (!palette.isRenderable(blockId) && y > 0 && guard-- > 0) {
            y--;
            blockId = chunk.getBlock(localX, y, localZ);
        }
        return palette.isRenderable(blockId) ? y : -1;
    }
}
