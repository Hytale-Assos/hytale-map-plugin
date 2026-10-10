package assos.hytale.map.stats;

import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Aggregated runtime metrics of the map plugin.
 *
 * <p>Counters are lock-free; the JSON snapshot is built on demand by
 * {@code GET /stats.json}.</p>
 */
public final class MapStats {

    private static final double BYTES_PER_MB = 1024.0 * 1024.0;
    private static final long BYTES_PER_SNAPSHOT =
            (long) ChunkSnapshotBytes.HEIGHTS + ChunkSnapshotBytes.COLORS + 32;

    private static final class ChunkSnapshotBytes {
        private static final long HEIGHTS = 1024L * 2;
        private static final long COLORS = 1024L * 4;
    }

    private final long startNanos = System.nanoTime();

    private final AtomicLong tilesRendered = new AtomicLong();
    private final AtomicLong tileRenderNanos = new AtomicLong();
    private final AtomicLong maxTileRenderNanos = new AtomicLong();
    private final AtomicLong tileCacheHits = new AtomicLong();

    private final AtomicLong snapshotsCaptured = new AtomicLong();
    private final AtomicLong snapshotCaptureNanos = new AtomicLong();
    private final AtomicLong maxSnapshotCaptureNanos = new AtomicLong();
    private final AtomicLong diskCacheHits = new AtomicLong();
    private final AtomicLong onDemandLoads = new AtomicLong();

    private final AtomicLong httpRequests = new AtomicLong();

    public void recordTileRender(long nanos) {
        tilesRendered.incrementAndGet();
        tileRenderNanos.addAndGet(nanos);
        maxTileRenderNanos.accumulateAndGet(nanos, Math::max);
    }

    public void recordTileCacheHit() {
        tileCacheHits.incrementAndGet();
    }

    public void recordSnapshotCapture(long nanos) {
        snapshotsCaptured.incrementAndGet();
        snapshotCaptureNanos.addAndGet(nanos);
        maxSnapshotCaptureNanos.accumulateAndGet(nanos, Math::max);
    }

    public void recordDiskCacheHit() {
        diskCacheHits.incrementAndGet();
    }

    public void recordOnDemandLoad() {
        onDemandLoads.incrementAndGet();
    }

    public void recordHttpRequest() {
        httpRequests.incrementAndGet();
    }

    public JsonObject toJson(int snapshotCacheCount, int tileCacheCount, long tileCacheBytes,
            long renderCacheDiskEntries) {
        JsonObject root = new JsonObject();
        root.addProperty("uptimeSeconds", elapsedSeconds());
        root.add("memory", memory());
        root.add("tiles", tiles(tileCacheCount, tileCacheBytes));
        root.add("snapshots", snapshots(snapshotCacheCount));
        root.add("renderCache", renderCache(renderCacheDiskEntries));
        root.add("http", http());
        return root;
    }

    private JsonObject memory() {
        Runtime runtime = Runtime.getRuntime();
        JsonObject memory = new JsonObject();
        memory.addProperty("usedMb", round((runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MB));
        memory.addProperty("committedMb", round(runtime.totalMemory() / BYTES_PER_MB));
        memory.addProperty("maxMb", round(runtime.maxMemory() / BYTES_PER_MB));
        return memory;
    }

    private JsonObject tiles(int tileCacheCount, long tileCacheBytes) {
        JsonObject tiles = new JsonObject();
        tiles.addProperty("rendered", tilesRendered.get());
        tiles.addProperty("avgRenderMs", avgMs(tileRenderNanos.get(), tilesRendered.get()));
        tiles.addProperty("maxRenderMs", round(maxTileRenderNanos.get() / 1_000_000.0));
        tiles.addProperty("cacheHits", tileCacheHits.get());
        tiles.addProperty("cacheCount", tileCacheCount);
        tiles.addProperty("cacheBytesMb", round(tileCacheBytes / BYTES_PER_MB));
        return tiles;
    }

    private JsonObject snapshots(int snapshotCacheCount) {
        JsonObject snapshots = new JsonObject();
        snapshots.addProperty("captured", snapshotsCaptured.get());
        snapshots.addProperty("avgCaptureMs", avgMs(snapshotCaptureNanos.get(), snapshotsCaptured.get()));
        snapshots.addProperty("maxCaptureMs", round(maxSnapshotCaptureNanos.get() / 1_000_000.0));
        snapshots.addProperty("diskCacheHits", diskCacheHits.get());
        snapshots.addProperty("onDemandLoads", onDemandLoads.get());
        snapshots.addProperty("cacheCount", snapshotCacheCount);
        snapshots.addProperty("approxMemoryMb",
                round(snapshotCacheCount * BYTES_PER_SNAPSHOT / BYTES_PER_MB));
        return snapshots;
    }

    private JsonObject renderCache(long diskEntries) {
        JsonObject renderCache = new JsonObject();
        renderCache.addProperty("diskEntries", diskEntries);
        return renderCache;
    }

    private JsonObject http() {
        JsonObject http = new JsonObject();
        http.addProperty("requests", httpRequests.get());
        return http;
    }

    private long elapsedSeconds() {
        return (System.nanoTime() - startNanos) / 1_000_000_000L;
    }

    private static double avgMs(long totalNanos, long count) {
        if (count == 0) {
            return 0;
        }
        return round(totalNanos / (double) count / 1_000_000.0);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
