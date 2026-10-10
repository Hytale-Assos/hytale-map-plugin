package assos.hytale.map.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * Root configuration for the map plugin.
 *
 * <p>The file is written to the plugin data directory as {@code map_config.json}.</p>
 */
public class MapConfig {

    public static final BuilderCodec<MapConfig> CODEC = BuilderCodec
            .builder(MapConfig.class, MapConfig::new)
            .append(new KeyedCodec<>("ApiEnabled", Codec.BOOLEAN),
                    (config, value, info) -> config.apiEnabled = value,
                    (config, info) -> config.apiEnabled)
            .add()
            .append(new KeyedCodec<>("ApiHost", Codec.STRING),
                    (config, value, info) -> config.apiHost = value,
                    (config, info) -> config.apiHost)
            .add()
            .append(new KeyedCodec<>("ApiPort", Codec.INTEGER),
                    (config, value, info) -> config.apiPort = value,
                    (config, info) -> config.apiPort)
            .add()
            .append(new KeyedCodec<>("ApiRequireAuth", Codec.BOOLEAN),
                    (config, value, info) -> config.apiRequireAuth = value,
                    (config, info) -> config.apiRequireAuth)
            .add()
            .append(new KeyedCodec<>("ApiToken", Codec.STRING),
                    (config, value, info) -> config.apiToken = value,
                    (config, info) -> config.apiToken)
            .add()
            .append(new KeyedCodec<>("AllowRemoteAccess", Codec.BOOLEAN),
                    (config, value, info) -> config.allowRemoteAccess = value,
                    (config, info) -> config.allowRemoteAccess)
            .add()
            .append(new KeyedCodec<>("Hillshading", Codec.BOOLEAN),
                    (config, value, info) -> config.hillshading = value,
                    (config, info) -> config.hillshading)
            .add()
            .append(new KeyedCodec<>("LoadChunksOnDemand", Codec.BOOLEAN),
                    (config, value, info) -> config.loadChunksOnDemand = value,
                    (config, info) -> config.loadChunksOnDemand)
            .add()
            .build();

    private boolean apiEnabled = true;
    private String apiHost = "127.0.0.1";
    private int apiPort = 8090;
    private boolean apiRequireAuth = false;
    private String apiToken = "";
    private boolean allowRemoteAccess = false;
    private boolean hillshading = true;
    private boolean loadChunksOnDemand = false;

    private MapConfig() {
    }

    public boolean isApiEnabled() {
        return apiEnabled;
    }

    public String getApiHost() {
        return apiHost;
    }

    public int getApiPort() {
        return apiPort;
    }

    public boolean isApiRequireAuth() {
        return apiRequireAuth;
    }

    public String getApiToken() {
        return apiToken;
    }

    public void setApiToken(String apiToken) {
        this.apiToken = apiToken;
    }

    public boolean isAllowRemoteAccess() {
        return allowRemoteAccess;
    }

    public boolean isHillshading() {
        return hillshading;
    }

    public boolean isLoadChunksOnDemand() {
        return loadChunksOnDemand;
    }
}
