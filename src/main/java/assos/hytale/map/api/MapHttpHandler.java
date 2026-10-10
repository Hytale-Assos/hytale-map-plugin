package assos.hytale.map.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import assos.hytale.map.config.MapConfig;
import assos.hytale.map.render.BlockPalette;
import assos.hytale.map.render.ChunkSnapshot;
import assos.hytale.map.render.ChunkSnapshotService;
import assos.hytale.map.render.MarkerService;
import assos.hytale.map.render.MarkerService.PlayerMarker;
import assos.hytale.map.render.RenderCache;
import assos.hytale.map.render.TileRenderer;
import assos.hytale.map.stats.MapStats;

/**
 * Routes the map HTTP endpoints:
 *
 * <pre>
 * GET /                              front page (Leaflet)
 * GET /tiles/{zoom}/{x}_{y}.png      rendered tile (zoom 0 native)
 * GET /markers.json                  player markers of the default world
 * GET /stats.json                    runtime metrics of the plugin
 * GET /debug/palette.json            block id → name, group, color
 * GET /debug/column/{x}_{z}.json     surface blocks of one world column
 * </pre>
 *
 * <p>Static assets are served from a fixed whitelist of classpath resources,
 * which prevents any path traversal. When {@code ApiRequireAuth} is enabled,
 * every request must carry the configured bearer token.</p>
 */
public final class MapHttpHandler implements HttpHandler {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Pattern TILE_PATH = Pattern.compile("^/tiles/(\\d+)/(-?\\d+)_(-?\\d+)\\.png$");
    private static final Pattern COLUMN_PATH = Pattern.compile("^/debug/column/(-?\\d+)_(-?\\d+)\\.json$");
    private static final long RENDER_TIMEOUT_SECONDS = 25;
    private static final int DEBUG_COLUMN_DEPTH = 12;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final MapConfig config;
    private final BlockPalette palette;
    private final TileRenderer tileRenderer;
    private final MarkerService markerService;
    private final ExecutorService renderExecutor;
    private final ChunkSnapshotService snapshots;
    private final RenderCache renderCache;
    private final MapStats stats;
    private final Map<String, String> staticFiles = new HashMap<>();

    public MapHttpHandler(@Nonnull MapConfig config, @Nonnull BlockPalette palette,
            @Nonnull TileRenderer tileRenderer, @Nonnull MarkerService markerService,
            @Nonnull ExecutorService renderExecutor, @Nonnull ChunkSnapshotService snapshots,
            @Nonnull RenderCache renderCache, @Nonnull MapStats stats) {
        this.config = config;
        this.palette = palette;
        this.tileRenderer = tileRenderer;
        this.markerService = markerService;
        this.renderExecutor = renderExecutor;
        this.snapshots = snapshots;
        this.renderCache = renderCache;
        this.stats = stats;
        staticFiles.put("/", "text/html; charset=utf-8");
        staticFiles.put("/index.html", "text/html; charset=utf-8");
        staticFiles.put("/app.js", "application/javascript; charset=utf-8");
        staticFiles.put("/leaflet.js", "application/javascript; charset=utf-8");
        staticFiles.put("/leaflet.css", "text/css; charset=utf-8");
        staticFiles.put("/images/layers.png", "image/png");
        staticFiles.put("/images/layers-2x.png", "image/png");
        staticFiles.put("/images/marker-icon.png", "image/png");
        staticFiles.put("/images/marker-icon-2x.png", "image/png");
        staticFiles.put("/images/marker-shadow.png", "image/png");
    }

    @Override
    public void handle(@Nonnull HttpExchange exchange) throws IOException {
        stats.recordHttpRequest();
        try {
            if (!isAuthorized(exchange)) {
                send(exchange, 401, "text/plain; charset=utf-8", "Unauthorized".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = normalize(exchange.getRequestURI().getPath());
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) || "HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                route(exchange, path);
            } else {
                send(exchange, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes(StandardCharsets.UTF_8));
            }
        } catch (RuntimeException exception) {
            LOGGER.at(Level.WARNING).withCause(exception).log("Map HTTP request failed: %s",
                    exchange.getRequestURI().getPath());
            send(exchange, 500, "text/plain; charset=utf-8", "Internal error".getBytes(StandardCharsets.UTF_8));
        } finally {
            exchange.close();
        }
    }

    private void route(@Nonnull HttpExchange exchange, @Nonnull String path) throws IOException {
        if (path.equals("/markers.json")) {
            serveMarkers(exchange);
            return;
        }
        if (path.equals("/stats.json")) {
            sendJson(exchange, stats.toJson(snapshots.cacheSize(), tileRenderer.tileCacheCount(),
                    tileRenderer.tileCacheBytes(), renderCache.entryCount()));
            return;
        }
        if (path.equals("/debug/palette.json")) {
            serveDebugPalette(exchange);
            return;
        }
        Matcher column = COLUMN_PATH.matcher(path);
        if (column.matches()) {
            serveDebugColumn(exchange, Integer.parseInt(column.group(1)), Integer.parseInt(column.group(2)));
            return;
        }
        Matcher tile = TILE_PATH.matcher(path);
        if (tile.matches()) {
            serveTile(exchange, Integer.parseInt(tile.group(1)),
                    Integer.parseInt(tile.group(2)), Integer.parseInt(tile.group(3)));
            return;
        }
        String contentType = staticFiles.get(path);
        if (contentType != null) {
            serveStatic(exchange, path, contentType);
            return;
        }
        send(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
    }

    private void serveTile(@Nonnull HttpExchange exchange, int zoom, int tileX, int tileZ) throws IOException {
        World world = defaultWorld();
        if (world == null) {
            send(exchange, 503, "text/plain; charset=utf-8",
                    "No world is loaded".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String clientEtag = exchange.getRequestHeaders().getFirst("If-None-Match");
        Long version = tileRenderer.tileVersion(zoom, tileX, tileZ);
        if (version != null && clientEtag != null && clientEtag.equals(Long.toString(version))
                && tileRenderer.hasFreshTile(zoom, tileX, tileZ)) {
            exchange.getResponseHeaders().set("ETag", Long.toString(version));
            exchange.getResponseHeaders().set("Cache-Control", "max-age=15");
            exchange.sendResponseHeaders(304, -1);
            return;
        }
        CompletableFuture<byte[]> render = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return tileRenderer.renderTile(world, zoom, tileX, tileZ);
                    } catch (TimeoutException exception) {
                        throw new IllegalStateException("Tile render timed out", exception);
                    } catch (IOException exception) {
                        throw new IllegalStateException("Tile render failed", exception);
                    }
                },
                renderExecutor);
        byte[] png;
        try {
            png = render.get(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            send(exchange, 503, "text/plain; charset=utf-8",
                    "Tile render timed out".getBytes(StandardCharsets.UTF_8));
            return;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            send(exchange, 503, "text/plain; charset=utf-8",
                    "Tile render interrupted".getBytes(StandardCharsets.UTF_8));
            return;
        } catch (ExecutionException exception) {
            LOGGER.at(Level.WARNING).withCause(exception.getCause()).log(
                    "Failed to render tile %d/%d_%d", zoom, tileX, tileZ);
            send(exchange, 503, "text/plain; charset=utf-8",
                    "Tile render failed".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (png == null) {
            send(exchange, 404, "text/plain; charset=utf-8",
                    "Zoom level is not rendered".getBytes(StandardCharsets.UTF_8));
            return;
        }
        Long renderedVersion = tileRenderer.tileVersion(zoom, tileX, tileZ);
        if (renderedVersion != null) {
            exchange.getResponseHeaders().set("ETag", Long.toString(renderedVersion));
        }
        exchange.getResponseHeaders().set("Cache-Control", "max-age=15");
        send(exchange, 200, "image/png", png);
    }

    private void serveMarkers(@Nonnull HttpExchange exchange) throws IOException {
        World world = defaultWorld();
        if (world == null) {
            send(exchange, 503, "text/plain; charset=utf-8",
                    "No world is loaded".getBytes(StandardCharsets.UTF_8));
            return;
        }
        List<PlayerMarker> markers;
        try {
            markers = markerService.players(world).get(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            send(exchange, 503, "text/plain; charset=utf-8",
                    "Marker collection timed out".getBytes(StandardCharsets.UTF_8));
            return;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            send(exchange, 503, "text/plain; charset=utf-8",
                    "Marker collection interrupted".getBytes(StandardCharsets.UTF_8));
            return;
        } catch (ExecutionException exception) {
            LOGGER.at(Level.WARNING).withCause(exception.getCause()).log("Failed to collect player markers");
            send(exchange, 503, "text/plain; charset=utf-8",
                    "Marker collection failed".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] body = GSON.toJson(markers).getBytes(StandardCharsets.UTF_8);
        send(exchange, 200, "application/json; charset=utf-8", body);
    }

    private void serveStatic(@Nonnull HttpExchange exchange, @Nonnull String path, @Nonnull String contentType)
            throws IOException {
        String resource = "/web" + ("/".equals(path) ? "/index.html" : path);
        try (InputStream input = MapHttpHandler.class.getResourceAsStream(resource)) {
            if (input == null) {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] body = input.readAllBytes();
            exchange.getResponseHeaders().set("Cache-Control", "max-age=3600");
            send(exchange, 200, contentType, body);
        }
    }

    private void serveDebugPalette(@Nonnull HttpExchange exchange) throws IOException {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int blockId = 0; blockId < palette.colorCount(); blockId++) {
            BlockType type = BlockType.getAssetMap().getAssetOrDefault(blockId, null);
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", blockId);
            entry.put("name", type != null ? type.getId() : null);
            entry.put("group", type != null ? type.getGroup() : null);
            entry.put("renderable", palette.isRenderable(blockId));
            entry.put("color", palette.isRenderable(blockId)
                    ? String.format("#%06x", palette.color(blockId) & 0xFFFFFF)
                    : null);
            entries.add(entry);
        }
        sendJson(exchange, entries);
    }

    private void serveDebugColumn(@Nonnull HttpExchange exchange, int blockX, int blockZ) throws IOException {
        World world = defaultWorld();
        if (world == null) {
            send(exchange, 503, "text/plain; charset=utf-8",
                    "No world is loaded".getBytes(StandardCharsets.UTF_8));
            return;
        }
        long chunkKey = ChunkUtil.indexChunkFromBlock(blockX, blockZ);
        CompletableFuture<List<Map<String, Object>>> future = new CompletableFuture<>();
        world.execute(() -> {
            @SuppressWarnings("deprecation")
            WorldChunk chunk = world.loadChunkIfInMemory(chunkKey);
            if (chunk == null) {
                future.complete(List.of());
                return;
            }
            List<Map<String, Object>> blocks = new ArrayList<>();
            int localX = Math.floorMod(blockX, ChunkSnapshot.CHUNK_SIZE);
            int localZ = Math.floorMod(blockZ, ChunkSnapshot.CHUNK_SIZE);
            @SuppressWarnings("deprecation")
            int height = chunk.getBlockChunk().getHeight(localX, localZ);
            int y = Math.min(height, ChunkUtil.HEIGHT - 1);
            int guard = DEBUG_COLUMN_DEPTH;
            while (y >= 0 && guard-- >= 0) {
                @SuppressWarnings("deprecation")
                int blockId = chunk.getBlock(localX, y, localZ);
                BlockType type = BlockType.getAssetMap().getAssetOrDefault(blockId, null);
                Map<String, Object> entry = new HashMap<>();
                entry.put("y", y);
                entry.put("id", blockId);
                entry.put("name", type != null ? type.getId() : null);
                entry.put("color", palette.isRenderable(blockId)
                        ? String.format("#%06x", palette.color(blockId) & 0xFFFFFF)
                        : null);
                blocks.add(entry);
                y--;
            }
            future.complete(blocks);
        });
        List<Map<String, Object>> blocks;
        try {
            blocks = future.get(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            send(exchange, 503, "text/plain; charset=utf-8",
                    "Column read failed".getBytes(StandardCharsets.UTF_8));
            return;
        }
        sendJson(exchange, Map.of(
                "x", blockX,
                "z", blockZ,
                "blocks", blocks));
    }

    private void sendJson(@Nonnull HttpExchange exchange, @Nonnull Object payload) throws IOException {
        byte[] body = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        send(exchange, 200, "application/json; charset=utf-8", body);
    }

    private boolean isAuthorized(@Nonnull HttpExchange exchange) {
        if (!config.isApiRequireAuth()) {
            return true;
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        return authorization != null && authorization.equals("Bearer " + config.getApiToken());
    }

    @Nullable
    private static World defaultWorld() {
        Universe universe = Universe.get();
        return universe != null ? universe.getDefaultWorld() : null;
    }

    @Nonnull
    private static String normalize(@Nullable String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static void send(@Nonnull HttpExchange exchange, int status, @Nonnull String contentType,
            byte[] body) throws IOException {
        if (exchange.getResponseHeaders().getFirst("Cache-Control") == null) {
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        }
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
