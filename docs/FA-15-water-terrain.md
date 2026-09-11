# FA-15 — Water + Terrain Context

**Jira:** [FA-15](https://tntra.atlassian.net/browse/FA-15) · subtask of FA-11 (POC) · Sprint 0

This doc explains what was built for FA-15, why it's shaped the way it is, and how the pieces
fit together. It follows the same style as [HOW-IT-WORKS.md](HOW-IT-WORKS.md) — plain language,
real code, and a note on what to improve later.

---

## 1. What FA-15 asked for

Two new data layers on a patch's insights, grouped together because both describe **how the
land physically behaves**:

- **Water** — is there surface water nearby, how persistent is it, how far back does the record go?
- **Terrain** — how high is the land, how steep is it, what's the general shape (flat, hilly, steep)?

The ticket's own example output:

```
WATER                          TERRAIN
Surface water detected 0.08 ha Elevation 620–684m
Water occurrence  Seasonal     Average slope 17°
Water recurrence  42%          Maximum slope 31°
Historical period 1984–2021    Terrain  Hilly
```

**Important constraint from the ticket:** the underlying satellite data is 30m/pixel. A 4-acre
patch is only a few pixels wide, so this is a coarse signal, not a survey. The UI must say
*"surface water detected from available satellite observations,"* never *"we've mapped every
stream."*

---

## 2. Why this lives inside the existing Next.js project, not a new service

The existing three data layers (biodiversity, soil, carbon) are all plain public REST APIs —
call a URL, get JSON back. Water and terrain come from **Google Earth Engine** datasets instead:

- Water → [`JRC/GSW1_4/GlobalSurfaceWater`](https://developers.google.com/earth-engine/datasets/catalog/JRC_GSW1_4_GlobalSurfaceWater) (1984–2021 water history)
- Terrain → [`USGS/SRTMGL1_003`](https://developers.google.com/earth-engine/datasets/catalog/USGS_SRTMGL1_003) (30m elevation model)

Earth Engine isn't a REST API you can `curl` — it's a server-side computation graph you submit
via an authenticated client library, and **there is no official Java client**, only Python and
JavaScript/Node. This project only has two runtimes: the Java (Spring Boot) backend and the
Next.js frontend — so rather than stand up a third service just to host some JS, the Earth
Engine calls live as **server-side API routes inside the existing Next.js app** (Next.js Route
Handlers run on Node on the server, never in the browser, so this is safe — no credentials ever
reach the client bundle):

```
┌─────────────┐  HTTP GET /api/water, /api/terrain   ┌──────────────────┐   Earth Engine API   ┌────────────────────┐
│ Java backend│ ─────────────────────────────────────▶│  Next.js frontend │──────────────────────▶│ JRC GSW / SRTM DEM │
│(Spring Boot)│                                        │ (server route)   │                       │  (Google servers)  │
└─────────────┘                                        └──────────────────┘                       └────────────────────┘
```

The Java backend still treats it exactly like GBIF/SoilGrids/GFW: one more `HttpClient` call to
a URL that returns JSON — it just happens to be the frontend's own server this time, instead of
a public API. All the Earth Engine complexity is isolated inside
`frontend/app/lib/geoEngine/` and two small route handlers.

**Bonus:** this reuses credentials that were already sitting unused in the repo —
`GEE_PROJECT_ID` and `GEE_SERVICE_ACCOUNT_JSON` in `.env`, and the actual key file in
`secrets/ee-4acres-*.json`. They'd clearly been provisioned for exactly this kind of work
(and probably for sibling tickets FA-13/FA-14, which likely need Earth Engine too).

---

## 3. The Earth Engine logic, inside the Next.js app

Plain CommonJS modules under [`frontend/app/lib/geoEngine/`](../frontend/app/lib/geoEngine),
called from two Next.js Route Handlers.

### 3.1 Authentication — [`lib/geoEngine/earthEngine.js`](../frontend/app/lib/geoEngine/earthEngine.js)

```js
ee.data.authenticateViaPrivateKey(privateKey, () => {
  ee.initialize(null, null, () => resolve(ee), reject)
}, reject)
```

Reads the service-account JSON from `GEE_SERVICE_ACCOUNT_JSON`, authenticates once, and **caches
the resulting promise** — every request reuses the same authenticated session instead of
re-authenticating per call (Earth Engine auth is slow; doing it per-request would make every
insights fetch noticeably slower).

### 3.2 Water — [`lib/geoEngine/water.js`](../frontend/app/lib/geoEngine/water.js)

The `JRC/GSW1_4/GlobalSurfaceWater` image has an `occurrence` band: for every pixel, what % of
the 1984–2021 record showed water. The logic:

1. Draw a circle around the patch's centre point (`ee.Geometry.Point([lng, lat]).buffer(radiusM)`).
2. Mask to pixels where `occurrence > 0` — "was this ever water at all."
3. Sum the **real area** of those masked pixels (`ee.Image.pixelArea()`) → hectares of detected water.
4. Average the `seasonality` band (months/year with water, most recent year) and `recurrence`
   band (how consistently, year over year) — but only across the masked "is water" pixels, so a
   patch with no water doesn't get a meaningless average.
5. Classify: no water pixels → `"None"`; ≥10 months/year average → `"Permanent"`; otherwise
   `"Seasonal"`.

```js
const waterMask = gsw.select('occurrence').gt(0)
const areaStats = ee.Image.pixelArea().updateMask(waterMask)
  .reduceRegion({ reducer: ee.Reducer.sum(), geometry, scale: 30, maxPixels: 1e9 })
```

### 3.3 Terrain — [`lib/geoEngine/terrain.js`](../frontend/app/lib/geoEngine/terrain.js)

```js
const dem = ee.Image('USGS/SRTMGL1_003').select('elevation')
const slope = ee.Terrain.slope(dem)   // degrees, computed from the DEM

const elevationStats = dem.reduceRegion({ reducer: ee.Reducer.minMax(), geometry, scale: 30 })
const slopeStats = slope.reduceRegion({
  reducer: ee.Reducer.mean().combine({ reducer2: ee.Reducer.max(), sharedInputs: true }),
  geometry, scale: 30,
})
```

`minMax()` gives elevation min/max in one call; the combined `mean()+max()` reducer gives
average and maximum slope in one call too — one server round-trip each, rather than four.

Terrain classification is a simple slope threshold table, calibrated so the ticket's own
example lines up (17° average → "Hilly"):

| Avg slope | Class |
|---|---|
| < 5° | Flat |
| 5–15° | Rolling |
| 15–25° | Hilly |
| > 25° | Steep |

### 3.4 The HTTP layer — Next.js Route Handlers

[`app/api/water/route.ts`](../frontend/app/api/water/route.ts) and
[`app/api/terrain/route.ts`](../frontend/app/api/terrain/route.ts):

```
GET /api/water?lat=&lng=&radiusM=   → { surfaceWaterHa, occurrenceClass, recurrencePercent, period }
GET /api/terrain?lat=&lng=&radiusM= → { elevationMinM, elevationMaxM, avgSlopeDeg, maxSlopeDeg, terrainClass }
```

Both routes are marked `export const dynamic = 'force-dynamic'` and `export const runtime =
'nodejs'` — `force-dynamic` stops Next.js from trying to statically prerender a route that reads
request-time query params, and `nodejs` (rather than the Edge runtime) is required because
`@google/earthengine` needs Node's `fs`/`crypto` to read the service-account key and sign
requests.

Unlike the existing Java clients (which swallow every failure into zeroed data — the root
cause of a whole separate debugging session on this project), these routes **log failures**
(`console.error`) and return a real `502` on error. The Java side will still fall back to
defaults on a bad response (same pattern as the other clients), but now there's a log line on
the frontend side telling you it actually failed, instead of a silent, indistinguishable zero.

---

## 4. Backend (Java) changes

Same shape as the existing `GfwClient` / `GbifClient` / `SoilGridsClient` pattern — nothing
architecturally new here, just one more client feeding one more pair of fields.

| File | What changed |
|---|---|
| [`GeoEngineClient.java`](../backend/src/main/java/io/fouracres/client/GeoEngineClient.java) | New client. Calls the frontend's `/api/water` and `/api/terrain` routes. |
| [`WaterData.java`](../backend/src/main/java/io/fouracres/dto/WaterData.java) | New DTO: `surfaceWaterHa`, `occurrenceClass`, `recurrencePercent`, `period`. |
| [`TerrainData.java`](../backend/src/main/java/io/fouracres/dto/TerrainData.java) | New DTO: `elevationMinM`, `elevationMaxM`, `avgSlopeDeg`, `maxSlopeDeg`, `terrainClass`. |
| [`PatchInsightsDto.java`](../backend/src/main/java/io/fouracres/dto/PatchInsightsDto.java) | Now carries `water` and `terrain` alongside `biodiversity`/`soil`/`carbon`. |
| [`InsightsService.java`](../backend/src/main/java/io/fouracres/service/InsightsService.java) | Fetches water + terrain in parallel with the other three (`CompletableFuture`, same pattern), caches them under `"WATER"`/`"TERRAIN"` cache layers. |
| [`application.yml`](../backend/src/main/resources/application.yml) | New `app.geoengine.base-url` (defaults to `localhost:3000/api` for local dev, overridden to `http://frontend:3000/api` in Docker). |

### Why a fixed radius instead of the real polygon?

`GeoEngineClient` converts the patch's fixed 4-acre area into a circular radius
(`≈71.8m`) and passes just `lat/lng/radiusM` — it doesn't send the patch's actual boundary
polygon. This mirrors a simplification the codebase already relies on (`GfwClient` also
hardcodes `4 * ACRES_TO_HA` rather than reading the patch's real area). It's a known POC
shortcut, not a new one — worth fixing (send the real boundary) if patches ever vary in size.

### Why a longer cache TTL?

```java
var expires = now.plus(Duration.ofHours(24));       // biodiversity / soil / carbon
var staticExpires = now.plus(Duration.ofDays(30));  // water / terrain
```

Biodiversity/soil/carbon are "live" queries that can genuinely change day to day. Water
history (1984–2021) and elevation are static datasets — refetching them every 24h would just
burn Earth Engine quota for identical answers. `docs/HOW-IT-WORKS.md` already flagged
"tune TTL per data type" as a future improvement; this is that improvement applied to two
concrete layers.

---

## 5. Docker / infra

No new service was added to `docker-compose.yml` — the existing `api` and `frontend` services
just gained a new connection between them:

```yaml
api:
  ...
  environment:
    GEO_ENGINE_URL: http://frontend:3000/api   # new — points at the frontend's own server routes
  depends_on:
    db: { condition: service_healthy }

frontend:
  build: ...
  ports: ["3000:3000"]
  env_file: .env                # new — needed for GEE_PROJECT_ID + GEE_SERVICE_ACCOUNT_JSON
  volumes:
    - ./secrets:/secrets:ro      # new — mounts the existing service-account key, read-only
  depends_on: [api]
```

`.env` already pointed `GEE_SERVICE_ACCOUNT_JSON` at `/secrets/ee-4acres-*.json` — that's a
container path, which is exactly why the `./secrets:/secrets:ro` mount matches it. No new
secrets were introduced; this just finally *uses* what was already configured, now inside the
`frontend` container instead of a separate one.

`api` calling `frontend` for these two layers only works because it's a plain runtime HTTP call
on demand — Docker Compose won't allow a circular `depends_on` (frontend already depends on
`api` at startup), so `api` doesn't declare a `depends_on` on `frontend`. In practice this is a
non-issue: nobody can trigger an insights request until the frontend has loaded in their
browser, by which point the frontend container is already up.

---

## 6. Frontend

| File | What changed |
|---|---|
| [`lib/types.ts`](../frontend/app/lib/types.ts) | New `WaterData` / `TerrainData` types; `PatchInsights` now includes both. |
| [`WaterCard.tsx`](../frontend/app/components/WaterCard.tsx) | New card — surface water (ha), occurrence class, recurrence %, plus the ticket-mandated hedged caption. |
| [`TerrainCard.tsx`](../frontend/app/components/TerrainCard.tsx) | New card — elevation range, terrain class, avg/max slope. |
| [`InsightsDrawer.tsx`](../frontend/app/components/InsightsDrawer.tsx) | Renders both new cards after the Carbon card. |
| [`lib/geoEngine/`](../frontend/app/lib/geoEngine) | New — the actual Earth Engine query logic (§3), server-only. |
| [`api/water/route.ts`](../frontend/app/api/water/route.ts), [`api/terrain/route.ts`](../frontend/app/api/terrain/route.ts) | New — Route Handlers the Java backend calls. |
| [`package.json`](../frontend/package.json) | New dependency: `@google/earthengine`. |
| [`types/google-earthengine.d.ts`](../frontend/types/google-earthengine.d.ts) | New — a one-line ambient module declaration so TypeScript doesn't complain about the untyped `@google/earthengine` package. |

Both cards follow the exact visual pattern of `SoilCard`/`CarbonCard` (same `Stat` sub-component,
same Tailwind classes) so they look native to the existing drawer, not bolted on.

The water card's caption is the one place the ticket's "don't overpromise" instruction becomes
literal UI copy:

> *"Surface water detected from available satellite observations (1984–2021). At this scale,
> readings are indicative, not a survey of every stream."*

---

## 7. End-to-end request flow

1. User opens a patch on `/explore` → `InsightsDrawer` calls `GET /api/patches/{id}/insights`.
2. `PatchController` → `InsightsService.getInsights(patchId)`.
3. Cache check across **five** layers (`BIODIVERSITY`, `SOIL`, `CARBON`, `WATER`, `TERRAIN`).
   If all five are cached and unexpired, return immediately — no external calls at all.
4. On a miss, five `CompletableFuture`s fire in parallel: `GbifClient`, `SoilGridsClient`,
   `GfwClient`, and `GeoEngineClient` (called twice — once for water, once for terrain).
5. `GeoEngineClient` calls the frontend's `/api/water` and `/api/terrain` routes, which run the
   Earth Engine reductions described in §3 and return JSON. This is the Java backend calling
   back into the Next.js server — an unusual direction, but it avoids running a third service
   just to host a few dozen lines of JS.
6. All five results join, get cached (24h or 30d depending on layer), and are returned as one
   `PatchInsightsDto`.
7. Frontend renders five cards: Biodiversity, Soil, Carbon, Water, Terrain.

---

## 8. What's still manual / left for later

- **First Docker build is a bit slower** — `@google/earthengine` is a nontrivial npm install,
  now part of the `frontend` image build instead of a separate one.
- **No automated tests** were added for the new `lib/geoEngine/` modules, the API routes, or
  `GeoEngineClient` (explicitly out of scope for this pass).
- **Existing patches' cache rows** don't have `WATER`/`TERRAIN` entries yet — they populate
  automatically on the next insights request per patch, same as any newly-added layer.
- **Fixed-radius approximation** (§4) should eventually use the patch's real boundary polygon
  instead of a circle, once patch sizes stop being uniformly "4 acres."
- **Terrain/water classification thresholds** are simple and POC-appropriate; they're isolated
  in one function each (`classifyOccurrence`, `classifyTerrain`) so they're easy to tune later
  without touching the Earth Engine query logic.
