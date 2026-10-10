# Recherche préalable : rendu de carte temps réel (BlueMap / Dynmap style)

Analyse faite le 2026-10-10 par introspection du jar du serveur Hytale
(`~/.gradle/caches/modules-2/files-2.1/com.hypixel.hytale/Server/0.6.8/.../Server-0.6.8.jar`,
fat jar 106 Mo, Java 21+). Tout ce qui suit a été vérifié dans le bytecode, pas dans une
documentation officielle : `com.hypixel.hytale.server.core.*` n'est pas une API publique
documentée et peut casser à chaque version 0.6.x.

## Verdict

- **Dynmap-style (2D + tuiles web + marqueurs) : FAISABLE en plugin serveur pur.**
  C'est la direction retenue : commencer par du 2D simple.
- BlueMap-style (3D texturé) : pas faisable proprement serveur seul. Le jar serveur
  contient 0 texture et 0 modèle 3D (aucun PNG/GLB vérifié) — les assets visuels sont
  côté client. Compromis futur possible : 3D voxel couleurs plates via
  `getTextureComputedColor()`, ou un mod client.

## API plugin (vérifiée dans le jar)

- `com.hypixel.hytale.server.core.plugin.JavaPlugin` / `PluginBase` (chargé depuis
  `mods/`, manifest JSON). Cycle de vie : `setup()`, `start()`, `shutdown()`.
- `PluginBase` expose : `getEventRegistry()`, `getTaskRegistry()`,
  `getChunkStoreRegistry()`, `getAssetRegistry()`, `getEntityStoreRegistry()`,
  `withConfig()`, logger, `getDataDirectory()`.
- `HytaleServer.get()` : eventBus, pluginManager, config.
- `Universe.get()` : `getWorlds()`, `getDefaultWorld()`, `getWorld(name)`,
  `getPlayers()` → `PlayerRef` (uuid, username, permissions, packetHandler).

## Accès monde / voxels

- `World implements ChunkAccessor, IWorldChunks` :
  - `WorldChunk getChunkIfInMemory(long)`, `getChunkIfLoaded(long)`,
    `getChunkAsync(long)`, `getNonTickingChunkAsync(long)`
  - `getBlockBulkRelative(Long2ObjectMap, IntUnaryOperator x3, GenericBlockBulkUpdater)`
    — lecture bulk
  - `getPlayers()`, `getChunkStore()`, `getChunkLighting()`
- `WorldChunk` :
  - `int getBlock(int,int,int)` (ID bloc),
    `BlockType getBlockType(int,int,int)` / `getBlockType(Vector3ic)`
  - surcharges `setBlock(...)`, `breakBlock(...)`
  - `getBlockComponentEntity/Holder(x,y,z)` → Ref ECS `ChunkStore`
- `ChunkStore` : `getChunkIndexes()`, `getLoadedChunksCount()`,
  `getChunkSectionReferenceAsync(x,y,z[,int])`, `supportsCubicSections()`,
  `getLoader()`, `getGenerator()`. Stockage RocksDB
  (`RocksDbChunkStorageProvider`), sections cubiques.

## Palette de couleurs (clé du rendu 2D sans textures)

- `BlockType` : `getId()`, `getGroup()`, `isState()`, `getDefaultStateKey()`, et surtout
  **`getTextureComputedColor()` → `com.hypixel.hytale.protocol.Color`** : couleur
  pré-calculée du bloc, disponible serveur alors que le jar n'a pas les textures.
- Itérer tous les blocs au boot : `BlockType.getAssetStore()` / `getAssetMap()` →
  construire la palette id → couleur.

## Événements temps réel

- `com.hypixel.hytale.server.core.event.events.ecs` : `PlaceBlockEvent`
  (`getItemInHand()`, `getTargetBlock()` → `Vector3i`), `BreakBlockEvent`,
  `EnvironmentBreakBlockEvent`, `DamageBlockEvent`, `UseBlockEvent` —
  `CancellableEcsEvent`.
- Joueur : `PlayerReadyEvent`, `PlayerDisconnectEvent`, `AddPlayerToWorldEvent`,
  `RemovedPlayerFromWorldEvent`, `PlayerChatEvent`... Pas de `PlayerMoveEvent`
  explicite (position via ECS ou polling).
- EventBus : `com.hypixel.hytale.event` (Sync/Async EventBusRegistry, EventPriority).

## Marqueurs / carte vanilla (contexte)

Le serveur a déjà un système worldmap côté client : `WorldMapTracker` (par joueur,
`getSentMarkers()`), `worldmap.markers.worldstore.WorldMarkersResource`, assets
`UserMapMarkerConfig` / `PlayersMapMarkerConfig`. Rien n'est rendu côté serveur —
c'est la valeur ajoutée de ce mod.

## Risques connus

1. **API interne non stable** : `com.hypixel.hytale.server.core.*` non documenté
   publiquement ; peut casser à chaque 0.6.x. Prévoir des tests de compilation tôt.
2. **Threading** : `World extends TickingThread` + `Executor`. Les lectures de chunks
   se font sur le thread du monde ou via les variantes async (`getChunkAsync`,
   `getChunkSectionReferenceAsync`). Le rendu lourd se fait sur un executor dédié
   (mono-thread, pattern déjà éprouvé dans hytale-server-management-plugin).
3. Heightmap disponible par chunk : `WorldChunk#getBlockChunk().getHeight(x, z)` (vérifié 0.6.8,
   non déprécié). Pas besoin de scanner les colonnes pour trouver la surface.
4. Pas de WebSocket dans `com.sun.net.httpserver` → polling JSON côté front.
5. JSON : Gson fourni par le serveur (ne jamais l'embarquer). HTTP : JDK uniquement,
   ne jamais ajouter de dépendance HTTP.

## Architecture d'affichage (phase 1 : 2D simple)

### Endpoints servis par le mod (com.sun.net.httpserver)

```
GET /                           → front statique (index.html, leaflet.css/js, app.js)
GET /tiles/{zoom}/{x}_{z}.png   → tuiles rendues depuis les chunks
GET /markers.json               → {uuid, name, world, x, y, z} (regénéré à chaque requête)
```

- Tuiles : modèle Dynmap — 256×256 px = 256×256 blocs au zoom 1, zoom out par facteur
  2 (pyramide). 1 tuile = scan de colonnes, couleur du bloc de surface via
  `getTextureComputedColor()`, + hillshading (ombrage nord/sud selon la pente) pour
  que le relief soit lisible.
- Rafraîchissement : numéro de version de tuile (incrémenté sur invalidation
  Place/Break/EnvironmentBreak), transporté dans `markers.json` ; le front redemande
  avec `?v=N` en cache-buster. Re-render incrémental, jamais full map.
- Front servi depuis le même serveur HTTP que les tuiles → pas de CORS.
- Bind loopback par défaut + équivalent `AllowRemoteManagement` pour le LAN + token
  Bearer à décider dès le départ (même politique que hytale-server-management-plugin).

### Front : Leaflet (~50 lignes, pas de framework)

```js
const map = L.map('map', { crs: L.CRS.Simple, minZoom: 0, maxZoom: 4 });
L.tileLayer('/tiles/{z}/{x}_{y}.png', {
  tileSize: 256, maxNativeZoom: 1, noWrap: true
}).addTo(map);
setInterval(async () => {
  const res = await fetch('/markers.json');
  // maj des L.circleMarker / L.marker
}, 2000);
```

- Piège (vérifié en pratique) : le flip `y = -z` appartient AU MARQUEUR (latlng),
  pas à l'URL des tuiles. CRS.Simple négative déjà lat à la projection, et
  lat = -z la négative une seconde fois : à l'écran, pixel y = bloc z. La rangée
  de tuile Leaflet `coords.y` correspond donc directement à la tuile monde z —
  ne pas appliquer `-z - 1` dans getTileUrl (décale toute la carte d'une tuile
  verticalement).
- `maxNativeZoom` : plusieurs zooms front pour une seule pyramide serveur au début.
- Convention à fixer en v0 : origine du monde = coin haut-gauche de la tuile 0,0.

## Ordre de dev

1. Tuiles statiques du monde par défaut au zoom 1, affichées dans Leaflet.
2. Palette de couleurs via `getAssetStore()` + hillshading.
3. Marqueurs polling (joueurs + personnalisés).
4. Invalidation incrémentale des tuiles sur Place/Break/EnvironmentBreak.
