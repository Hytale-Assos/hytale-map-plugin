package assos.hytale.map.render;

import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import java.util.Arrays;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Maps fluid ids to the color used to tint them on the map.
 *
 * <p>Fluids live in their own chunk layer, separate from blocks, so the map
 * paints them on top of the surface block of flooded columns. Colors come
 * from the fluid asset when it carries one, with fixed tints for the families
 * the client colors itself (water, lava).</p>
 */
public final class FluidPalette {

    private static final int WATER_TINT = 0xFF3B6FD0;
    private static final int LAVA_TINT = 0xFFD4530A;
    private static final int FALLBACK_COLOR = 0xFF5A5A5A;

    private final int[] colors;
    private final String[] names;

    private FluidPalette(int[] colors, String[] names) {
        this.colors = colors;
        this.names = names;
    }

    @Nonnull
    public static FluidPalette fromAssetMap() {
        var assetMap = Fluid.getAssetMap();
        int size = Math.max(1, assetMap.getNextIndex());
        int[] colors = new int[size];
        String[] names = new String[size];
        Arrays.fill(colors, FALLBACK_COLOR);
        for (int id = 0; id < size; id++) {
            Fluid fluid = assetMap.getAssetOrDefault(id, null);
            if (fluid == null) {
                continue;
            }
            names[id] = fluid.getId();
            Color particleColor = fluid.getParticleColor();
            int color = particleColor != null
                    ? 0xFF000000
                            | ((particleColor.red & 0xFF) << 16)
                            | ((particleColor.green & 0xFF) << 8)
                            | (particleColor.blue & 0xFF)
                    : FALLBACK_COLOR;
            String name = fluid.getId() != null ? fluid.getId().toLowerCase(Locale.ROOT) : "";
            if (name.contains("water")) {
                color = WATER_TINT;
            } else if (name.contains("lava")) {
                color = LAVA_TINT;
            }
            colors[id] = color;
        }
        return new FluidPalette(colors, names);
    }

    public boolean isFluid(int fluidId) {
        return fluidId > 0
                && fluidId != Fluid.EMPTY_ID
                && fluidId != Fluid.UNKNOWN_ID
                && fluidId < colors.length;
    }

    public int color(int fluidId) {
        if (fluidId < 0 || fluidId >= colors.length) {
            return FALLBACK_COLOR;
        }
        return colors[fluidId];
    }

    @Nullable
    public String name(int fluidId) {
        if (fluidId < 0 || fluidId >= names.length) {
            return null;
        }
        return names[fluidId];
    }
}
