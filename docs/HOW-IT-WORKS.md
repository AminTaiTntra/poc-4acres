# How It Works — 4Acres Earth POC

Three things power this system: **PostGIS** for land geometry, **parallel API fetching** for environmental data, and **Mapbox GL JS** for the visual experience. This doc explains each one simply, and how to improve them.

---

## 1. PostGIS — Storing Land Geometry

PostgreSQL alone can't store shapes. PostGIS is an extension that adds geometry types, so we can store a land polygon as a real geometric object — not just coordinates in a text field.

### What we store

Every patch has two geometry-related columns:

```sql
boundary_geojson TEXT           -- GeoJSON string, sent directly to the frontend
boundary         geometry(Polygon, 4326)  -- PostGIS geometry for spatial queries
```

`SRID 4326` means the coordinates use the WGS-84 standard — the same system GPS uses. Longitude and latitude, in degrees.

### How a polygon gets in

When a land owner draws a polygon on the map, the frontend sends GeoJSON coordinates to the backend. Java converts them to a JTS (Java Topology Suite) Polygon using `GeometryFactory`:

```java
GeometryFactory gf = new GeometryFactory(new PrecisionModel(), 4326);
Coordinate[] coords = coordinates.stream()
    .map(p -> new Coordinate(p.get(0), p.get(1)))
    .toArray(Coordinate[]::new);
Polygon polygon = gf.createPolygon(coords);
```

From that polygon, we compute the centre point automatically:

```java
double centerLng = polygon.getCentroid().getX();
double centerLat = polygon.getCentroid().getY();
```

### Why store GeoJSON as text AND as PostGIS geometry?

- The **text column** lets the backend return GeoJSON to the frontend with zero conversion work. Fast reads.
- The **geometry column** enables PostGIS spatial functions — things like "find all patches within 10km of this point" (`ST_DWithin`) or computing the real area in acres (`ST_Area`).

For now we use both. At scale you'd keep only the geometry column and generate GeoJSON on the fly or with a view.

### How to make PostGIS better

- **Compute real area**: right now `areaAcres` is hardcoded to 4.0. The real query is:
  ```sql
  SELECT ST_Area(ST_GeogFromWKB(boundary)) / 4046.86 AS acres FROM patches WHERE id = ?
  ```
- **Spatial index**: add `CREATE INDEX patches_boundary_gix ON patches USING GIST (boundary);` — this makes "find patches near a point" queries fast even with millions of rows.
- **Overlap detection**: before inserting a new patch, check if it overlaps an existing one with `ST_Intersects(new_boundary, boundary)`.

---

## 2. Fetching — External APIs and the Cache

Each patch shows live environmental data from three public APIs. These are called on demand, not at registration time.

### The three APIs

| API | What it gives us | Auth |
|---|---|---|
| **GBIF** | Species count, threatened species, top species names | None (public) |
| **SoilGrids** | Organic carbon, pH, clay % at 0–5cm depth | None (public) |
| **GFW** (Global Forest Watch) | Tree cover %, carbon density, forest loss | API key |

Each has its own client class (`GbifClient`, `SoilGridsClient`, `GfwClient`) using Spring's `RestClient`. They take the patch's latitude/longitude (or a GFW geostore ID) and return typed Java objects.

### Parallel fetching

Instead of calling each API one after the other (which would add the latencies together), all three fire at the same time using `CompletableFuture`:

```java
CompletableFuture<BiodiversityData> bio  = CompletableFuture.supplyAsync(() -> gbifClient.fetch(patch));
CompletableFuture<SoilData>         soil = CompletableFuture.supplyAsync(() -> soilGridsClient.fetch(patch));
CompletableFuture<CarbonData>    carbon  = CompletableFuture.supplyAsync(() -> gfwClient.fetch(patch));

// Wait for all three — total time = slowest API, not sum of all three
return new InsightData(bio.join(), soil.join(), carbon.join());
```

### The cache — PostgreSQL as the cache store

External API calls are slow and rate-limited. We cache results in the same PostgreSQL database (no Redis needed for this scale) with a 24-hour TTL:

```sql
CREATE TABLE insights_cache (
  patch_id        UUID PRIMARY KEY REFERENCES patches(id),
  biodiversity_json TEXT,
  soil_json         TEXT,
  carbon_json       TEXT,
  cached_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

On each request, the service checks if a fresh row exists. If `cached_at > NOW() - INTERVAL '24 hours'`, return the cached JSON. Otherwise fetch fresh and upsert.

### How to make fetching better

- **Real error handling per API**: right now if one API fails the whole insights call can fail. Each `CompletableFuture` should catch its own exception and return a null/empty result gracefully, so two APIs can succeed even if one is down.
- **Shorter TTL for important data**: 24 hours is fine for soil (changes over years) but forest cover loss data might warrant 7 days. Tune TTL per data type.
- **Background refresh**: instead of blocking the user while fetching, pre-warm the cache in a background job for patches that are about to be viewed (e.g., after a claim is made).
- **Add Redis when needed**: if you have high concurrency (many users viewing the same patch simultaneously), Redis will handle cache reads faster. But don't add it until the data tells you to.

---

## 3. Maps — How the Visual Experience Works

We use **Mapbox GL JS v3** with the **react-map-gl v7** React wrapper. Three completely different map setups serve the three pages.

### The three map modes

**`/register` — DrawMap (polygon drawing)**

The user draws their land boundary. We use `@mapbox/mapbox-gl-draw` to add drawing tools to the map:

```ts
const draw = new MapboxDraw({ displayControlsDefault: false, controls: { polygon: true } })
map.addControl(draw as any)  // 'as any' needed — v7 types don't include addControl overload
map.on('draw.create', e => onPolygonDrawn(e.features[0].geometry.coordinates[0]))
```

Key: use **mercator projection** here (the default). Globe projection distorts coordinate accuracy near the poles.

**`/explore` — PatchMap (colour-coded world map)**

All patches load as a GeoJSON FeatureCollection. The fill colour is driven by the `status` property using a Mapbox `match` expression — no JavaScript needed for each patch:

```ts
'fill-color': ['match', ['get', 'status'],
  'AVAILABLE', '#f59e0b',   // amber
  'CLAIMED',   '#10b981',   // green
  '#888'                    // fallback
]
```

Each patch is a clickable layer. When clicked, the sidebar opens with details.

**`/patch/[id]` — MyPatchMap (3D terrain, cinematic fly-in)**

This is the most complex. Three things happen on map load:

1. **Terrain DEM** (Digital Elevation Model) — adds real 3D height to the terrain:
   ```ts
   map.addSource('mapbox-dem', { type: 'raster-dem', url: 'mapbox://mapbox.mapbox-terrain-v2', tileSize: 512 })
   map.setTerrain({ source: 'mapbox-dem', exaggeration: 1.5 })  // 1.5× height exaggeration
   ```

2. **Sky layer** — renders an atmosphere above the terrain:
   ```ts
   map.addLayer({ id: 'sky', type: 'sky',
     paint: { 'sky-type': 'atmosphere', 'sky-atmosphere-sun': [0.0, 90.0], 'sky-atmosphere-sun-intensity': 15 }
   })
   ```

3. **Cinematic fly-in** — animates the camera from the world view down to the patch:
   ```ts
   map.flyTo({ center: [patch.centerLng, patch.centerLat],
     zoom: 15.5, pitch: 60, bearing: -20, duration: 3500, essential: true })
   ```
   `pitch: 60` tilts the camera to a horizon view. `bearing: -20` rotates slightly for a dramatic angle.

The patch boundary is drawn as two layers on top: a semi-transparent green fill and a solid green outline.

### The `as any` workaround

react-map-gl v7's TypeScript types don't fully cover Mapbox GL JS v3 features (setTerrain, sky layer, globe projection). The pattern across this codebase is:

```ts
const map = mapRef.current?.getMap()  // get raw Mapbox GL JS instance
;(map as any).setTerrain({ source: 'mapbox-dem', exaggeration: 1.5 })
```

This is a known temporary workaround — not a design flaw.

### How to make maps better

- **Cluster markers at low zoom**: when the world map has hundreds of patches, replace individual markers with a cluster layer (`cluster: true` on the GeoJSON source). Mapbox handles this natively.
- **Smooth status transitions**: when a patch is claimed, use `map.setPaintProperty` to animate its colour change in real time — no page reload.
- **Add 3D buildings**: for urban patches, add the Mapbox `mapbox-buildings` layer on the `/patch/[id]` terrain view.
- **Offline tiles**: for field use, Mapbox GL JS supports tile caching. Pre-cache tiles for known patch areas.
- **Lower pitch on mobile**: `pitch: 60` on a phone can be disorienting. Detect screen size and use `pitch: 40` on small viewports.
- **Real-time claim markers**: use a WebSocket (or short polling on `/api/patches`) to update marker colours on `/explore` when another user claims a patch live.

---

## Summary

| Layer | Technology | Key pattern |
|---|---|---|
| Land geometry | PostgreSQL + PostGIS | JTS conversion, dual storage (text + geometry) |
| Environmental data | GBIF · SoilGrids · GFW | Parallel CompletableFuture, 24h Postgres cache |
| Visual maps | Mapbox GL JS v3 + react-map-gl v7 | Three modes: Draw, GeoJSON layers, 3D terrain |
| Frontend data | react-query v5 | useQuery + useMutation, retry:false for expected 404s |
