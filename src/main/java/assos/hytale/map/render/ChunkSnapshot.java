package assos.hytale.map.render;

/**
 * Immutable top-down view of one chunk column: surface color and heightmap of
 * each of its 32×32 columns.
 *
 * <p>Chunks that are not loaded in memory are represented by
 * {@link #unloaded(int, int)}; their columns render as transparent.</p>
 */
public final class ChunkSnapshot {

    public static final int CHUNK_SIZE = 32;
    public static final int COLUMNS = CHUNK_SIZE * CHUNK_SIZE;

    private final int chunkX;
    private final int chunkZ;
    private final boolean loaded;
    private final short[] heights;
    private final int[] colors;

    private ChunkSnapshot(int chunkX, int chunkZ, boolean loaded, short[] heights, int[] colors) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.loaded = loaded;
        this.heights = heights;
        this.colors = colors;
    }

    public static ChunkSnapshot unloaded(int chunkX, int chunkZ) {
        return new ChunkSnapshot(chunkX, chunkZ, false, new short[COLUMNS], new int[COLUMNS]);
    }

    public static ChunkSnapshot captured(int chunkX, int chunkZ, short[] heights, int[] colors) {
        return new ChunkSnapshot(chunkX, chunkZ, true, heights, colors);
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public boolean loaded() {
        return loaded;
    }

    /**
     * @param localX chunk-local column X (0..31)
     * @param localZ chunk-local column Z (0..31)
     * @return the surface color in ARGB, 0 when the column was never generated
     */
    public int color(int localX, int localZ) {
        return colors[localX + localZ * CHUNK_SIZE];
    }

    /**
     * @param localX chunk-local column X (0..31)
     * @param localZ chunk-local column Z (0..31)
     * @return the height of the surface block
     */
    public int height(int localX, int localZ) {
        return heights[localX + localZ * CHUNK_SIZE];
    }
}
