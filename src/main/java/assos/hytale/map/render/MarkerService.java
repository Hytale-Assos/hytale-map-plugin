package assos.hytale.map.render;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import org.joml.Vector3d;

/**
 * Collects the player markers of a world on the owning world thread.
 */
public final class MarkerService {

    /**
     * One player marker as served by {@code /markers.json}.
     */
    public record PlayerMarker(String uuid, String name, double x, double y, double z) {
    }

    /**
     * @param world the world whose players are listed
     * @return a future completed on the world thread with the current markers
     */
    @Nonnull
    public CompletableFuture<List<PlayerMarker>> players(@Nonnull World world) {
        CompletableFuture<List<PlayerMarker>> future = new CompletableFuture<>();
        world.execute(() -> {
            List<PlayerMarker> markers = new ArrayList<>();
            for (PlayerRef playerRef : world.getPlayerRefs()) {
                markers.add(toMarker(playerRef));
            }
            future.complete(markers);
        });
        return future;
    }

    @Nonnull
    private static PlayerMarker toMarker(@Nonnull PlayerRef playerRef) {
        Transform transform = playerRef.getTransform();
        Vector3d position = transform != null ? transform.getPosition() : null;
        double x = position != null ? position.x : 0;
        double y = position != null ? position.y : 0;
        double z = position != null ? position.z : 0;
        return new PlayerMarker(playerRef.getUuid().toString(), playerRef.getUsername(), x, y, z);
    }
}
