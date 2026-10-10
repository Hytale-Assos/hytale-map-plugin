package assos.hytale.map.render;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import assos.hytale.map.stats.MapStats;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;

/**
 * Renders 256×256 pixel tiles covering 256×256 world blocks (zoom level 1).
 *
 * <p>Each pixel is the surface color of its column, optionally shaded by the
 * local height difference (light from the north-west). Columns belonging to
 * chunks that are not loaded render as transparent. Encoded PNGs are kept in
 * a bounded LRU cache so revisited tiles are served without re-rendering.</p>
 */
public final class TileRenderer {

    public static final int TILE_PIXELS = 256;
    public static final int CHUNKS_PER_TILE = TILE_PIXELS / ChunkSnapshot.CHUNK_SIZE;
    private static final long SNAPSHOT_TIMEOUT_SECONDS = 20;
    private static final float SHADE_STRENGTH = 0.12f;
    private static final int MAX_CACHED_TILES = 512;
    private static final long TILE_CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(120);

    private final ChunkSnapshotService snapshots;
    private final boolean hillshading;
    private final MapStats stats;
    private final Map<String, Long> tileVersions = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, CachedTile> tileCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(@Nonnull Map.Entry<String, CachedTile> eldest) {
                    return size() > MAX_CACHED_TILES;
                }
            });

    private record CachedTile(byte[] png, long createdAtNanos) {
        boolean isFresh() {
            return System.nanoTime() - createdAtNanos < TILE_CACHE_TTL_NANOS;
        }
    }

    public TileRenderer(@Nonnull ChunkSnapshotService snapshots, boolean hillshading,
            @Nonnull MapStats stats) {
        this.snapshots = snapshots;
        this.hillshading = hillshading;
        this.stats = stats;
    }

    /**
     * Native zoom level served by this renderer. In Leaflet's CRS.Simple a
     * 256px tile covers 256/2^zoom map units, so a 256-block tile is the zoom
     * 0 grid; higher zooms upscale it client-side.
     */
    public static final int NATIVE_ZOOM = 0;

    /**
     * Version of the tile's content, to be used as HTTP ETag. Bumped by
     * {@link #invalidateChunk(int, int)}; two renders with the same version
     * are interchangeable, so a matching client ETag can be answered with 304.
     *
     * @return the version of the last render of this tile, or null when it was
     *         never rendered
     */
    @Nullable
    public Long tileVersion(int zoom, int tileX, int tileZ) {
        return tileVersions.get(tileKey(zoom, tileX, tileZ));
    }

    /**
     * Whether the tile has a cached render that has not expired yet.
     */
    public boolean hasFreshTile(int zoom, int tileX, int tileZ) {
        CachedTile cached = tileCache.get(tileKey(zoom, tileX, tileZ));
        return cached != null && cached.isFresh();
    }

    /**
     * @param world the world to read chunks from
     * @param zoom the requested zoom level; only {@link #NATIVE_ZOOM} is rendered
     * @param tileX tile X coordinate
     * @param tileZ tile Z coordinate
     * @return the encoded PNG, or null when the zoom level is not rendered
     * @throws TimeoutException when chunk snapshots do not complete in time
     * @throws IOException when PNG encoding fails
     */
    @Nullable
    public byte[] renderTile(@Nonnull World world, int zoom, int tileX, int tileZ)
            throws TimeoutException, IOException {
        if (zoom != NATIVE_ZOOM) {
            return null;
        }
        String cacheKey = tileKey(zoom, tileX, tileZ);
        CachedTile cached = tileCache.get(cacheKey);
        if (cached != null && cached.isFresh()) {
            stats.recordTileCacheHit();
            return cached.png();
        }
        long start = System.nanoTime();
        byte[] png = renderUncached(world, tileX, tileZ);
        if (png != null) {
            stats.recordTileRender(System.nanoTime() - start);
            tileVersions.putIfAbsent(cacheKey, 0L);
            tileCache.put(cacheKey, new CachedTile(png, System.nanoTime()));
        }
        return png;
    }

    public int tileCacheCount() {
        return tileCache.size();
    }

    public long tileCacheBytes() {
        synchronized (tileCache) {
            long bytes = 0;
            for (CachedTile tile : tileCache.values()) {
                bytes += tile.png().length;
            }
            return bytes;
        }
    }

    /**
     * Drops the cached render of every tile that could show the given chunk,
     * including the neighbouring tiles whose hillshading reads its heightmap,
     * and bumps their version so clients refetch.
     */
    public void invalidateChunk(int chunkX, int chunkZ) {
        int tileX = Math.floorDiv(chunkX, CHUNKS_PER_TILE);
        int tileZ = Math.floorDiv(chunkZ, CHUNKS_PER_TILE);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                String key = tileKey(NATIVE_ZOOM, tileX + dx, tileZ + dz);
                tileCache.remove(key);
                tileVersions.compute(key, (ignored, version) -> version == null ? null : version + 1);
            }
        }
    }

    @Nonnull
    private static String tileKey(int zoom, int tileX, int tileZ) {
        return zoom + "/" + tileX + "_" + tileZ;
    }

    @Nullable
    private byte[] renderUncached(@Nonnull World world, int tileX, int tileZ)
            throws TimeoutException, IOException {
        int baseChunkX = tileX * CHUNKS_PER_TILE;
        int baseChunkZ = tileZ * CHUNKS_PER_TILE;

        ChunkSnapshot[][] grid = captureGrid(world, baseChunkX, baseChunkZ);
        if (allUnloaded(grid)) {
            return EmptyTilePng.INSTANCE;
        }

        BufferedImage image = new BufferedImage(TILE_PIXELS, TILE_PIXELS, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[TILE_PIXELS * TILE_PIXELS];
        for (int z = 0; z < TILE_PIXELS; z++) {
            for (int x = 0; x < TILE_PIXELS; x++) {
                pixels[x + z * TILE_PIXELS] = pixelColor(grid, x, z);
            }
        }
        image.setRGB(0, 0, TILE_PIXELS, TILE_PIXELS, pixels, 0, TILE_PIXELS);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static boolean allUnloaded(@Nonnull ChunkSnapshot[][] grid) {
        for (int gz = 1; gz <= CHUNKS_PER_TILE; gz++) {
            for (int gx = 1; gx <= CHUNKS_PER_TILE; gx++) {
                if (grid[gz][gx].loaded()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static final class EmptyTilePng {
        static final byte[] INSTANCE = encode();

        private static byte[] encode() {
            BufferedImage image = new BufferedImage(TILE_PIXELS, TILE_PIXELS, BufferedImage.TYPE_INT_ARGB);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                ImageIO.write(image, "png", output);
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
            return output.toByteArray();
        }
    }

    @Nonnull
    private ChunkSnapshot[][] captureGrid(@Nonnull World world, int baseChunkX, int baseChunkZ)
            throws TimeoutException {
        int gridChunks = CHUNKS_PER_TILE + 2;
        CompletableFuture<ChunkSnapshot>[] futures = createFutureArray(gridChunks * gridChunks);
        for (int gz = 0; gz < gridChunks; gz++) {
            for (int gx = 0; gx < gridChunks; gx++) {
                futures[gx + gz * gridChunks] =
                        snapshots.snapshot(world, baseChunkX + gx - 1, baseChunkZ + gz - 1);
            }
        }
        try {
            CompletableFuture.allOf(futures).get(SNAPSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Chunk snapshot capture failed", exception);
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new TimeoutException("Chunk snapshot capture timed out");
        }
        ChunkSnapshot[][] grid = new ChunkSnapshot[gridChunks][gridChunks];
        for (int gz = 0; gz < gridChunks; gz++) {
            for (int gx = 0; gx < gridChunks; gx++) {
                grid[gz][gx] = futures[gx + gz * gridChunks].join();
            }
        }
        return grid;
    }

    @SuppressWarnings("unchecked")
    @Nonnull
    private static CompletableFuture<ChunkSnapshot>[] createFutureArray(int size) {
        return (CompletableFuture<ChunkSnapshot>[]) new CompletableFuture<?>[size];
    }

    private int pixelColor(@Nonnull ChunkSnapshot[][] grid, int x, int z) {
        int gx = x / ChunkSnapshot.CHUNK_SIZE + 1;
        int gz = z / ChunkSnapshot.CHUNK_SIZE + 1;
        int localX = x % ChunkSnapshot.CHUNK_SIZE;
        int localZ = z % ChunkSnapshot.CHUNK_SIZE;

        ChunkSnapshot center = grid[gz][gx];
        int color = center.color(localX, localZ);
        if (color == 0) {
            return 0;
        }
        if (!hillshading) {
            return color;
        }

        int height = center.height(localX, localZ);
        float delta = 0;
        delta += height - neighborHeight(grid, gx, gz, localX - 1, localZ - 1, height);
        delta += height - neighborHeight(grid, gx, gz, localX, localZ - 1, height);
        delta += height - neighborHeight(grid, gx, gz, localX - 1, localZ, height);
        delta -= height - neighborHeight(grid, gx, gz, localX + 1, localZ, height);
        delta -= height - neighborHeight(grid, gx, gz, localX, localZ + 1, height);
        float shade = 1.0f + clamp(delta * SHADE_STRENGTH, -0.5f, 0.5f);

        int alpha = color & 0xFF000000;
        int red = (int) Math.min(255, Math.max(0, ((color >> 16) & 0xFF) * shade));
        int green = (int) Math.min(255, Math.max(0, ((color >> 8) & 0xFF) * shade));
        int blue = (int) Math.min(255, Math.max(0, (color & 0xFF) * shade));
        return alpha | (red << 16) | (green << 8) | blue;
    }

    /**
     * Height of the column one step away, wrapping into the neighbouring chunk
     * snapshot when the step crosses a chunk boundary.
     *
     * @param fallback height used when the neighbouring chunk is unavailable
     */
    private static int neighborHeight(@Nonnull ChunkSnapshot[][] grid, int gx, int gz,
            int localX, int localZ, int fallback) {
        if (localX < 0) {
            gx--;
            localX += ChunkSnapshot.CHUNK_SIZE;
        } else if (localX >= ChunkSnapshot.CHUNK_SIZE) {
            gx++;
            localX -= ChunkSnapshot.CHUNK_SIZE;
        }
        if (localZ < 0) {
            gz--;
            localZ += ChunkSnapshot.CHUNK_SIZE;
        } else if (localZ >= ChunkSnapshot.CHUNK_SIZE) {
            gz++;
            localZ -= ChunkSnapshot.CHUNK_SIZE;
        }
        if (gx < 0 || gz < 0 || gx >= grid.length || gz >= grid.length) {
            return fallback;
        }
        ChunkSnapshot snapshot = grid[gz][gx];
        return snapshot.loaded() ? snapshot.height(localX, localZ) : fallback;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
