package assos.hytale.map.render;

import com.hypixel.hytale.assetstore.map.BlockTypeAssetMap;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import java.util.Arrays;

import javax.annotation.Nonnull;

/**
 * Maps global block ids to the precomputed surface color of their block type.
 *
 * <p>Colors come from {@link BlockType#getTextureComputedColor()}, which the server
 * resolves at asset load time even though it bundles no textures.</p>
 */
public final class BlockPalette {

    private static final int FALLBACK_COLOR = 0xFF5A5A5A;
    private static final int GRASS_TINT = 0xFF709D2C;
    private static final int WATER_TINT = 0xFF3B6FD0;
    private static final int MOSS_TINT = 0xFF5D7D3A;
    private static final int LOW_SATURATION_THRESHOLD = 40;

    private final int[] colors;
    private final boolean[] known;

    private BlockPalette(int[] colors, boolean[] known) {
        this.colors = colors;
        this.known = known;
    }

    @Nonnull
    public static BlockPalette fromAssetMap() {
        BlockTypeAssetMap<String, BlockType> assetMap = BlockType.getAssetMap();
        int size = Math.max(1, assetMap.getNextIndex());
        int[] colors = new int[size];
        boolean[] known = new boolean[size];
        Arrays.fill(colors, FALLBACK_COLOR);
        for (int id = 0; id < size; id++) {
            BlockType type = assetMap.getAssetOrDefault(id, null);
            if (type == null) {
                continue;
            }
            Color color = type.getTextureComputedColor();
            if (color != null) {
                colors[id] = tintIfNeeded(type,
                        0xFF000000
                                | ((color.red & 0xFF) << 16)
                                | ((color.green & 0xFF) << 8)
                                | (color.blue & 0xFF));
                known[id] = true;
            }
        }
        return new BlockPalette(colors, known);
    }

    /**
     * Some block families ship a grayscale computed color because the client
     * tints them at render time. This map only has the server-side data, so
     * those families get a fixed representative color.
     */
    private static int tintIfNeeded(@Nonnull BlockType type, int color) {
        if (!isLowSaturation(color)) {
            return color;
        }
        String group = type.getGroup();
        if ("Grass".equals(group)) {
            return GRASS_TINT;
        }
        if ("Fluid_Water".equals(group)) {
            return WATER_TINT;
        }
        if ("Moss".equals(group) && type.getId() != null && type.getId().startsWith("Plant_Moss")) {
            return MOSS_TINT;
        }
        return color;
    }

    private static boolean isLowSaturation(int color) {
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        int max = Math.max(red, Math.max(green, blue));
        int min = Math.min(red, Math.min(green, blue));
        return max - min < LOW_SATURATION_THRESHOLD;
    }

    public int colorCount() {
        return colors.length;
    }

    public int color(int blockId) {
        if (blockId < 0 || blockId >= colors.length) {
            return FALLBACK_COLOR;
        }
        return colors[blockId];
    }

    public boolean isRenderable(int blockId) {
        return blockId > 0 && blockId < colors.length && known[blockId];
    }
}
