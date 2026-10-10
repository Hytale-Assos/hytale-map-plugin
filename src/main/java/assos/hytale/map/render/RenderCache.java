package assos.hytale.map.render;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Disk cache of rendered chunk snapshots.
 *
 * <p>Every chunk that leaves memory (or is captured while loaded) is persisted
 * here, so the map can display previously visited areas without ever asking
 * the world to reload them. Writes happen on a dedicated IO thread so the
 * world thread never touches the filesystem.</p>
 */
public final class RenderCache {

    private final Path directory;
    private final ExecutorService ioExecutor;

    public RenderCache(@Nonnull Path dataDirectory) throws IOException {
        this.directory = dataDirectory.resolve("render-cache");
        Files.createDirectories(directory);
        this.ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "HytaleMap-CacheIO");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Queues a snapshot for asynchronous persistence.
     *
     * @param key the chunk key, as computed by ChunkUtil#indexChunk
     */
    public void store(long key, @Nonnull ChunkSnapshot snapshot) {
        if (!snapshot.loaded()) {
            return;
        }
        ioExecutor.execute(() -> write(key, snapshot));
    }

    /**
     * Reads a snapshot previously stored for this chunk.
     *
     * @param chunkX chunk X coordinate, used to validate the entry
     * @param chunkZ chunk Z coordinate, used to validate the entry
     * @return the stored snapshot, or null when absent or unreadable
     */
    @Nullable
    public ChunkSnapshot load(long key, int chunkX, int chunkZ) {
        Path file = file(key);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (DataInputStream input = new DataInputStream(
                Files.newInputStream(file))) {
            int storedX = input.readInt();
            int storedZ = input.readInt();
            if (storedX != chunkX || storedZ != chunkZ) {
                return null;
            }
            short[] heights = new short[ChunkSnapshot.COLUMNS];
            int[] colors = new int[ChunkSnapshot.COLUMNS];
            for (int i = 0; i < ChunkSnapshot.COLUMNS; i++) {
                heights[i] = input.readShort();
            }
            for (int i = 0; i < ChunkSnapshot.COLUMNS; i++) {
                colors[i] = input.readInt();
            }
            return ChunkSnapshot.captured(chunkX, chunkZ, heights, colors);
        } catch (EOFException exception) {
            return null;
        } catch (IOException exception) {
            return null;
        }
    }

    /**
     * Number of persisted chunk entries on disk.
     *
     * @return the entry count, or -1 when the directory cannot be read
     */
    public long entryCount() {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.toString().endsWith(".chunk")).count();
        } catch (IOException exception) {
            return -1;
        }
    }

    public void shutdown() {
        ioExecutor.shutdownNow();
        try {
            ioExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void write(long key, @Nonnull ChunkSnapshot snapshot) {
        Path file = file(key);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(
                Files.newOutputStream(tmp))) {
            output.writeInt(snapshot.chunkX());
            output.writeInt(snapshot.chunkZ());
            for (int localZ = 0; localZ < ChunkSnapshot.CHUNK_SIZE; localZ++) {
                for (int localX = 0; localX < ChunkSnapshot.CHUNK_SIZE; localX++) {
                    output.writeShort(snapshot.height(localX, localZ));
                }
            }
            for (int localZ = 0; localZ < ChunkSnapshot.CHUNK_SIZE; localZ++) {
                for (int localX = 0; localX < ChunkSnapshot.CHUNK_SIZE; localX++) {
                    output.writeInt(snapshot.color(localX, localZ));
                }
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException suppressed) {
                exception.addSuppressed(suppressed);
            }
        }
    }

    @Nonnull
    private Path file(long key) {
        return directory.resolve(Long.toHexString(key) + ".chunk");
    }
}
