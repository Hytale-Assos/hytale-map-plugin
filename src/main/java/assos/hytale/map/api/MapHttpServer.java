package assos.hytale.map.api;

import com.hypixel.hytale.logger.HytaleLogger;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import javax.annotation.Nonnull;

import assos.hytale.map.config.MapConfig;

/**
 * HTTP server exposing the map front, tiles and markers.
 *
 * <p>Built on the JDK's {@link HttpServer}; no third-party HTTP dependency is
 * bundled. By default it binds to loopback only; a non-loopback bind is refused
 * unless {@code AllowRemoteAccess} is explicitly enabled.</p>
 */
public final class MapHttpServer {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final MapConfig config;
    private final MapHttpHandler handler;

    private HttpServer server;
    private ExecutorService executor;

    public MapHttpServer(@Nonnull MapConfig config, @Nonnull MapHttpHandler handler) {
        this.config = config;
        this.handler = handler;
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        String host = resolveHost();
        int port = config.getApiPort();
        if (port <= 0 || port > 65535) {
            throw new IllegalStateException("Invalid map API port: " + port);
        }
        if (config.isApiRequireAuth() && isBlank(config.getApiToken())) {
            throw new IllegalStateException(
                    "Map API authentication is required but 'ApiToken' is empty; set a token in map_config.json");
        }

        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        executor = Executors.newFixedThreadPool(8, runnable -> {
            Thread thread = new Thread(runnable, "HytaleMap-Http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", handler);
        server.start();
        LOGGER.at(Level.INFO).log("Hytale Map web server listening on http://%s:%d", host, port);
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
    }

    @Nonnull
    private String resolveHost() {
        String host = config.getApiHost();
        if (isBlank(host)) {
            return "127.0.0.1";
        }
        if (config.isAllowRemoteAccess()) {
            return host;
        }
        if (!isLoopback(host)) {
            LOGGER.at(Level.WARNING).log(
                    "Refusing to bind the map web server to non-loopback host '%s'; falling back to 127.0.0.1. "
                            + "Set AllowRemoteAccess=true to expose the map on your network.",
                    host);
            return "127.0.0.1";
        }
        return host;
    }

    private static boolean isLoopback(@Nonnull String host) {
        return "127.0.0.1".equals(host)
                || "localhost".equalsIgnoreCase(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private static boolean isBlank(@javax.annotation.Nullable String value) {
        return value == null || value.isBlank();
    }
}
