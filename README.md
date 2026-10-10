# Hytale Map

Carte 2D temps réel du monde Hytale, servie par HTTP directement depuis le plugin serveur
(dynmap-like). Développé par l'association **HytaleAssos**.

- Cible : serveur Hytale `>=0.6.5 <0.7.0`
- Langage : Java 25, build Gradle (`com.azuredoom.hytale-tools`)
- HTTP : `com.sun.net.httpserver` du JDK, aucune dépendance ajoutée
- JSON : Gson fourni par le serveur Hytale, jamais embarqué

## Fonctionnement

Le plugin rend le monde par défaut en tuiles PNG de 256×256 pixels couvrant 256×256 blocs.
Dans Leaflet (CRS.Simple), une tuile de 256 px couvre `256/2^zoom` unités de carte : une
tuile de 256 blocs correspond donc à la **grille de zoom 0**, native sur le serveur ; les
zooms supérieurs (1 à 4) l'upscalent côté client (`maxNativeZoom: 0`). Les chunks chargés
sont lus en mémoire et leur état final est persisté dans un cache de rendu disque au
déchargement (`render-cache/` dans le répertoire de données) — la carte affiche donc toute
zone déjà visitée, où que l'on navigue, sans coût pour le serveur. Le chargement à la
demande depuis le stockage monde (zones jamais visitées) passe par le pipeline complet de
chargement (coûteux pour le thread du monde) : il est opt-in via
`LoadChunksOnDemand=true` et limité à 8 chargements simultanés. La couleur de
surface de chaque bloc vient de
`BlockType#getTextureComputedColor`, pré-calculée par le serveur (avec teintes
correctives pour les blocs teintés côté client : herbe, eau, mousse), complétée par un
hillshading basé sur la heightmap des chunks. Les fluides (eau, lave) vivent dans une
couche de chunk séparée des blocs : ils sont lus via `getFluidId` et peints en blend
semi-transparent (~68 %) sur le fond de la colonne inondée. Le front (Leaflet 1.9.4,
vendored) est servi par le même serveur HTTP : pas de CORS, pas de CDN externe.

Toutes les lectures de chunks se font sur le thread du monde (via `World#execute`) dans des
snapshots immuables ; l'assemblage PNG se fait sur un executor dédié mono-thread.

## Endpoints

```
GET /                          front Leaflet (index.html, app.js, leaflet.*)
GET /tiles/{zoom}/{x}_{z}.png  tuile rendue (zoom 0 natif : 256×256 blocs)
GET /markers.json               [{uuid, name, x, y, z}] joueurs du monde par défaut
GET /stats.json                métriques runtime : mémoire, temps de rendu, caches
GET /debug/palette.json        correspondance id bloc → nom, groupe, couleur
GET /debug/column/{x}_{z}.json blocs de surface d'une colonne du monde
```

## Build et dev

```bash
./gradlew build            # compile + valide le manifest
./gradlew runServer        # serveur de dev
./gradlew updatePluginManifest
```

Aucune commande en jeu : la carte se consulte dans un navigateur sur l'URL affichée au
démarrage (par défaut `http://127.0.0.1:8090/`).

## Configuration

Fichier `map_config.json`, généré dans le répertoire de données du plugin au premier
démarrage :

| Clé                 | Défaut      | Description                                                            |
|---------------------|-------------|------------------------------------------------------------------------|
| `ApiEnabled`        | `true`      | Active le serveur HTTP de la carte.                                     |
| `ApiHost`           | `127.0.0.1` | Adresse d'écoute ; non-loopback refusé sauf `AllowRemoteAccess=true`.   |
| `ApiPort`           | `8090`      | Port d'écoute HTTP.                                                     |
| `ApiRequireAuth`     | `false`     | Exige un token Bearer sur tous les endpoints (désactivé par défaut car le front s'ouvre dans un navigateur). |
| `ApiToken`          | `""`        | Token Bearer attendu quand `ApiRequireAuth=true`.                       |
| `AllowRemoteAccess` | `false`     | Autorise l'écoute hors loopback (LAN).                                  |
| `Hillshading`       | `true`      | Ombrage du relief selon la heightmap.                                  |
| `LoadChunksOnDemand` | `false`    | Charge depuis le stockage monde les chunks jamais visités (coûteux pour le serveur : pipeline de pre-load complet, 8 chargements simultanés max). |

## Roadmap

1. [x] Tuiles statiques du monde par défaut au zoom 1, affichées dans Leaflet.
2. [x] Palette de couleurs via `getTextureComputedColor` + hillshading.
3. [x] Marqueurs polling (joueurs).
4. [x] Invalidation incrémentale des tuiles sur Place/Break/EnvironmentBreak.
5. [x] Cache de rendu disque : les zones visitées restent affichées sans rechargement.
6. [x] Métriques runtime (`/stats.json` + panneau front) et revalidation ETag (304).
7. [ ] Rendu multi-mondes et pyramide de zooms natifs (zooms < 0).

## Développement

- Branche d'intégration : `develop`. Fonctionnalités : `feat/<sujet>` depuis `develop`.
- Un commit = un sujet, format `type(scope): description` (voir `AGENTS.md`).
