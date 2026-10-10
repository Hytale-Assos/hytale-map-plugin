package assos.hytale.map;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import javax.annotation.Nonnull;

import org.joml.Vector3i;

import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.EnvironmentBreakBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.hypixel.hytale.server.core.universe.world.events.ecs.ChunkUnloadEvent;
import com.hypixel.hytale.server.core.util.Config;

import assos.hytale.map.api.MapHttpHandler;
import assos.hytale.map.api.MapHttpServer;
import assos.hytale.map.config.MapConfig;
import assos.hytale.map.render.BlockPalette;
import assos.hytale.map.render.ChunkSnapshot;
import assos.hytale.map.render.ChunkSnapshotService;
import assos.hytale.map.render.MarkerService;
import assos.hytale.map.render.RenderCache;
import assos.hytale.map.render.TileRenderer;
import assos.hytale.map.stats.MapStats;

/**
 * Entry point of the Hytale Map plugin.
 *
 * <p>Renders the default world as 2D tiles served over HTTP with a Leaflet
 * front. All rendering happens off the world threads: chunk data is captured
 * on the owning world thread into immutable snapshots, then painted on a
 * dedicated render executor. Block changes and chunk unloads invalidate the
 * affected snapshots and tiles so the map stays current.</p>
 */
public class HytaleMapPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String CONFIG_NAME = "map_config";

    private Config<MapConfig> config;
    private MapHttpServer httpServer;
    private ExecutorService renderExecutor;
    private RenderCache renderCache;
    private final List<EventRegistration<?, ?>> invalidationRegistrations = new ArrayList<>();

    public HytaleMapPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        this.config = this.withConfig(CONFIG_NAME, MapConfig.CODEC);
    }

    @Override
    protected void setup() {
        config.save();
    }

    @Override
    protected void start() {
        MapConfig mapConfig = config.get();
        if (!mapConfig.isApiEnabled()) {
            LOGGER.at(Level.INFO).log("Hytale Map is disabled in configuration (ApiEnabled=false)");
            return;
        }
        startHttp(mapConfig);
    }

    @Override
    protected void shutdown() {
        stopHttp();
        LOGGER.at(Level.INFO).log("Hytale Map shut down");
    }

    private void startHttp(@Nonnull MapConfig mapConfig) {
        BlockPalette palette = BlockPalette.fromAssetMap();
        try {
            renderCache = new RenderCache(getDataDirectory());
        } catch (IOException exception) {
            LOGGER.at(Level.SEVERE).withCause(exception)
                    .log("Failed to create the render cache directory; map rendering is disabled");
            return;
        }
        MapStats stats = new MapStats();
        ChunkSnapshotService snapshots = new ChunkSnapshotService(palette, renderCache,
                mapConfig.isLoadChunksOnDemand(), stats);
        TileRenderer tileRenderer = new TileRenderer(snapshots, mapConfig.isHillshading(), stats);
        MarkerService markerService = new MarkerService();
        registerInvalidation(snapshots, tileRenderer);
        renderExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "HytaleMap-Render");
            thread.setDaemon(true);
            return thread;
        });
        MapHttpHandler handler = new MapHttpHandler(mapConfig, palette, tileRenderer, markerService,
                renderExecutor, snapshots, renderCache, stats);
        httpServer = new MapHttpServer(mapConfig, handler);
        try {
            httpServer.start();
        } catch (Exception exception) {
            LOGGER.at(Level.SEVERE).withCause(exception).log("Failed to start the map web server");
            stopHttp();
        }
    }

    /**
     * Subscribes to the events that make rendered data stale: block changes
     * drop the chunk snapshot and the tiles showing it, chunk unloads drop the
     * snapshot so it is recaptured once the chunk loads again.
     */
    private void registerInvalidation(@Nonnull ChunkSnapshotService snapshots,
            @Nonnull TileRenderer tileRenderer) {
        invalidationRegistrations.add(getEventRegistry().registerGlobal(PlaceBlockEvent.class,
                event -> invalidateBlockChunk(snapshots, tileRenderer, event.getTargetBlock())));
        invalidationRegistrations.add(getEventRegistry().registerGlobal(BreakBlockEvent.class,
                event -> invalidateBlockChunk(snapshots, tileRenderer, event.getTargetBlock())));
        invalidationRegistrations.add(getEventRegistry().registerGlobal(EnvironmentBreakBlockEvent.class,
                event -> invalidateBlockChunk(snapshots, tileRenderer, event.getTargetBlock())));
        invalidationRegistrations.add(getEventRegistry().registerGlobal(ChunkUnloadEvent.class,
                event -> {
                    WorldChunk chunk = event.getChunk();
                    snapshots.persistOnUnload(chunk);
                    tileRenderer.invalidateChunk(chunk.getX(), chunk.getZ());
                }));
        invalidationRegistrations.add(getEventRegistry().registerGlobal(ChunkPreLoadProcessEvent.class,
                event -> {
                    WorldChunk chunk = event.getChunk();
                    tileRenderer.invalidateChunk(chunk.getX(), chunk.getZ());
                }));
    }

    private static void invalidateBlockChunk(@Nonnull ChunkSnapshotService snapshots,
            @Nonnull TileRenderer tileRenderer, @Nonnull Vector3i block) {
        int chunkX = Math.floorDiv(block.x, ChunkSnapshot.CHUNK_SIZE);
        int chunkZ = Math.floorDiv(block.z, ChunkSnapshot.CHUNK_SIZE);
        snapshots.invalidate(ChunkUtil.indexChunk(chunkX, chunkZ));
        tileRenderer.invalidateChunk(chunkX, chunkZ);
    }

    private void unregisterInvalidation() {
        for (EventRegistration<?, ?> registration : invalidationRegistrations) {
            registration.unregister();
        }
        invalidationRegistrations.clear();
    }

    private synchronized void stopHttp() {
        unregisterInvalidation();
        if (httpServer != null) {
            httpServer.stop();
            httpServer = null;
        }
        if (renderExecutor != null) {
            renderExecutor.shutdownNow();
            try {
                renderExecutor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            renderExecutor = null;
        }
        if (renderCache != null) {
            renderCache.shutdown();
            renderCache = null;
        }
    }
}
