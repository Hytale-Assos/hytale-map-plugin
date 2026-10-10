'use strict';

// Leaflet CRS.Simple: a 256px tile covers 256/2^zoom map units. A 256-block
// tile is therefore the zoom 0 grid; higher zooms upscale it client-side.
const NATIVE_ZOOM = 0;
const TILE_BLOCK_SIZE = 256;

const map = L.map('map', {
    crs: L.CRS.Simple,
    minZoom: 0,
    maxZoom: 4,
    noWrap: true,
    attributionControl: false
});

const HytaleTileLayer = L.TileLayer.extend({
    getTileUrl: function (coords) {
        const zoom = this._getZoomForUrl();
        // CRS.Simple negates lat when projecting, and blockToLatLng already
        // negates z into lat, so on-screen pixel y equals block z: the Leaflet
        // tile row IS the world tile z, no flip here. Freshness is handled by
        // the server-side ETag revalidation, no cache-buster needed.
        return '/tiles/' + zoom + '/' + coords.x + '_' + coords.y + '.png';
    }
});

const tileLayer = new HytaleTileLayer('', {
    tileSize: 256,
    minNativeZoom: NATIVE_ZOOM,
    maxNativeZoom: NATIVE_ZOOM,
    noWrap: true,
    bounds: [[-1e7, -1e7], [1e7, 1e7]],
    updateWhenIdle: false,
    keepBuffer: 8
}).addTo(map);

setInterval(function () {
    tileLayer.redraw();
}, 15000);

function blockToLatLng(x, z) {
    return [-(z + 1) + 0.5, x + 0.5];
}

function formatCoords(latlng) {
    const z = -latlng.lat - 0.5;
    const x = latlng.lng - 0.5;
    return 'x ' + Math.floor(x) + '  z ' + Math.floor(z);
}

const status = document.getElementById('status');
const statsBox = document.getElementById('stats');
const markers = new Map();
let focused = false;

async function refreshStats() {
    try {
        const response = await fetch('/stats.json');
        if (!response.ok) {
            return;
        }
        const s = await response.json();
        statsBox.textContent =
            'mem ' + s.memory.usedMb + '/' + s.memory.maxMb + ' Mo'
            + ' | tuiles ' + s.tiles.rendered + ' (' + s.tiles.avgRenderMs + ' ms, max ' + s.tiles.maxRenderMs + ' ms)'
            + ' | snapshots ' + s.snapshots.cacheCount + ' (' + s.snapshots.approxMemoryMb + ' Mo'
            + ', capt ' + s.snapshots.avgCaptureMs + ' ms)'
            + ' | disque ' + s.renderCache.diskEntries
            + ' | http ' + s.http.requests;
    } catch (error) {
        // laissons le panneau tel quel en cas d'erreur ponctuelle
    }
}

async function refreshMarkers() {
    try {
        const response = await fetch('/markers.json');
        if (!response.ok) {
            throw new Error('HTTP ' + response.status);
        }
        const players = await response.json();

        const seen = new Set();
        for (const player of players) {
            seen.add(player.uuid);
            const latlng = blockToLatLng(player.x, player.z);
            let marker = markers.get(player.uuid);
            if (marker) {
                marker.setLatLng(latlng);
            } else {
                marker = L.circleMarker(latlng, {
                    radius: 6,
                    color: '#ffffff',
                    weight: 2,
                    fillColor: '#2196f3',
                    fillOpacity: 1
                }).addTo(map).bindTooltip(player.name, {
                    permanent: true,
                    direction: 'right',
                    className: 'player-label'
                });
                markers.set(player.uuid, marker);
            }
        }
        for (const [uuid, marker] of markers) {
            if (!seen.has(uuid)) {
                map.removeLayer(marker);
                markers.delete(uuid);
            }
        }

        if (!focused && players.length > 0) {
            const group = players.map(p => blockToLatLng(p.x, p.z));
            map.fitBounds(L.latLngBounds(group).pad(0.5));
            focused = true;
        }
        status.textContent = players.length + ' joueur(s) en ligne';
    } catch (error) {
        status.textContent = 'erreur: ' + error.message;
    }
}

map.on('mousemove', function (event) {
    status.textContent = formatCoords(event.latlng);
});

map.setView([0, 0], 1);
refreshMarkers();
setInterval(refreshMarkers, 2000);
refreshStats();
setInterval(refreshStats, 5000);
