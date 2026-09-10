# FA-13 — Vegetation Health & Historical Trend: POC Results

**Question answered:** Can we tell whether vegetation on a specific land patch is healthy, and whether it is improving or degrading over time?

**Verdict: Yes.** Using free, publicly-available Sentinel-2 satellite imagery routed through Google Earth Engine, we can compute a reliable NDVI time series for any polygon and classify its health and trend in under 90 seconds.

---

## What We Proved

A single call to our API returns this for any registered patch:

```json
{
  "yearly": [
    { "year": 2022, "ndvi": 0.2209, "validPixelPct": 100, "observationDate": "2022-10-15" },
    { "year": 2023, "ndvi": 0.5053, "validPixelPct": 100, "observationDate": "2023-10-15" },
    { "year": 2024, "ndvi": 0.8200, "validPixelPct": 100, "observationDate": "2024-10-15" },
    { "year": 2025, "ndvi": 0.7465, "validPixelPct": 100, "observationDate": "2025-10-15" },
    null
  ],
  "currentNdvi": 0.7465,
  "baselineNdvi": 0.5154,
  "changePct": 44.84,
  "trend": "improving",
  "condition": "good",
  "lastObservation": "2025-10-15",
  "resolutionM": 10,
  "source": "Sentinel-2"
}
```

`null` entries represent years with no cloud-free imagery in the season window — handled gracefully throughout the pipeline.

---

## Architecture

```
Browser
  ↓  GET /api/patches/{id}/vegetation
Spring Boot (api:8080)
  ├── Check insights_cache (layer = "VEGETATION", TTL 24h)
  └── Cache miss → POST /api/ndvi to frontend container
                      ↓
                  Next.js (frontend:3000)
                  app/api/ndvi/route.ts
                      ↓
                  Google Earth Engine (COPERNICUS/S2_SR_HARMONIZED)
                  ← NDVI stats per year
                      ↑
              Spring Boot stores result in insights_cache
              Browser receives camelCase JSON
```

**Three-service stack:** `db → api → frontend`. The GEE computation runs server-side inside the Next.js process — no separate microservice, no Python runtime.

---

## How It Works

### 1. Satellite data source — Sentinel-2

We use the `COPERNICUS/S2_SR_HARMONIZED` collection in Google Earth Engine. This is the surface reflectance (atmospherically corrected) product from ESA's Sentinel-2 satellites — free, global, 10m resolution, updated every 5 days.

NDVI is computed from two bands:

```
NDVI = (B8 − B4) / (B8 + B4)
     = (Near-Infrared − Red) / (Near-Infrared + Red)
```

Healthy green vegetation reflects strongly in NIR (B8) and absorbs red (B4), so NDVI ranges from −1 to +1. Dense canopy typically scores 0.6–0.9.

### 2. Cloud masking

Clouds corrupt pixel values. Before compositing, we filter using the QA60 quality band. Bits 10 and 11 flag opaque and cirrus clouds:

```typescript
const qa   = img.select('QA60')
const mask = qa.bitwiseAnd(1 << 10).eq(0).and(qa.bitwiseAnd(1 << 11).eq(0))
return img.updateMask(mask)
```

After masking, all scenes in the season window are composited into a single median image. The median is more robust than the mean against residual haze and shadow.

### 3. Season window

Rather than averaging across all 12 months (which mixes wet/dry seasons), each patch has a configurable `season_start_month` and `season_end_month`. The default is October–November, which captures the post-monsoon green flush in South India.

This avoids the seasonal bias that would make NDVI look artificially low in dry months and high in wet months — even when the underlying health hasn't changed.

### 4. Trend detection — linear regression

To determine whether vegetation is improving or degrading, we fit a linear regression slope across all valid yearly NDVI values:

```typescript
const xMean = (n - 1) / 2
const yMean = valid.reduce((s, y) => s + y.ndvi, 0) / n
const numer = valid.reduce((s, y, i) => s + (i - xMean) * (y.ndvi - yMean), 0)
const denom = valid.reduce((s, _, i) => s + (i - xMean) ** 2, 0)
const slope = denom ? numer / denom : 0
```

| Slope | Label |
|-------|-------|
| > 0.01 NDVI/year | `improving` |
| < −0.01 NDVI/year | `degrading` |
| Between ±0.01 | `stable` |

The 0.01 threshold filters out noise in the satellite observations (clouds, sensor differences) while still catching real multi-year trends.

### 5. Condition classification

Condition is based on the most recent year's NDVI:

| Current NDVI | Condition |
|---|---|
| ≥ 0.6 | `good` (dense, healthy vegetation) |
| 0.3 – 0.6 | `fair` (moderate or sparse vegetation) |
| < 0.3 | `poor` (bare soil, degraded, or non-vegetated) |

### 6. Caching

GEE computation takes 30–90 seconds per patch (4–5 API round trips to Google's servers for each year in the range). Results are cached in PostgreSQL's `insights_cache` table with a 24-hour TTL. Subsequent page loads return instantly.

```sql
SELECT * FROM insights_cache
WHERE patch_id = ? AND layer = 'VEGETATION' AND expires_at > NOW()
```

### 7. Frontend display

`VegetationCard.tsx` renders:
- Condition badge (colour-coded: green / amber / red)
- Current and baseline NDVI with `toFixed(4)` precision
- Change percentage vs baseline
- Trend label
- An inline SVG sparkline showing the yearly NDVI time series, with gap-handling for null (cloud-obscured) years
- Last observation date, resolution, and data source

---

## Files Added / Changed

| File | What it does |
|------|-------------|
| `frontend/app/api/ndvi/route.ts` | Next.js API route — authenticates with GEE, runs per-year NDVI computation, returns JSON |
| `backend/src/main/java/io/fouracres/client/NdviClient.java` | Spring component that POSTs to the Next.js route and deserialises the response |
| `backend/src/main/java/io/fouracres/dto/VegetationData.java` | Java record for the GEE response (uses `@JsonAlias` for snake_case → camelCase) |
| `backend/src/main/java/io/fouracres/dto/NdviYearlyPoint.java` | Java record for one year of NDVI data |
| `backend/src/main/java/io/fouracres/service/VegetationService.java` | Cache-first lookup, falls back to NdviClient, stores result |
| `backend/src/main/java/io/fouracres/controller/PatchController.java` | Adds `GET /api/patches/{id}/vegetation` endpoint |
| `backend/src/main/resources/db/migration/V4__add_season_window.sql` | Adds `season_start_month`, `season_end_month` columns to patches |
| `frontend/app/components/VegetationCard.tsx` | Glass card with sparkline, loading skeleton, 8 data rows |
| `frontend/app/lib/types.ts` | `VegetationData` and `NdviYearlyPoint` TypeScript interfaces |
| `frontend/app/lib/queries.ts` | `useVegetation(patchId)` React Query hook (24h stale time, no retry) |

---

## Key Decisions Made During the POC

### JS over Python for the GEE client

We initially built the GEE client in Python (FastAPI). Halfway through, we migrated it to a Next.js API route. The reasons:

- The product will use React/Next.js on the frontend. Adding Python creates a third language in a two-language stack (Java + JS).
- A separate Python microservice adds operational complexity: a fourth Docker container, a separate healthcheck, a separate build pipeline, and a separate dependency tree.
- The `@google/earthengine` npm package exposes the same API as the Python `earthengine-api`, so the port was direct.
- Server-side Next.js API routes run in Node.js and can hold long-lived connections to GEE without any architectural change.

### `@JsonAlias` not `@JsonProperty` in Java DTOs

GEE returns snake_case JSON (`current_ndvi`, `valid_pixel_pct`). We initially used `@JsonProperty("current_ndvi")` in the Java records, which affected *both* deserialization (reading from GEE) and serialization (writing to browser). The browser received `current_ndvi` but TypeScript expected `currentNdvi`, causing a runtime crash.

The fix: `@JsonAlias` affects only deserialization, so the Java field name (`currentNdvi`) is used for the browser response.

### Season window per patch, not global

A fixed month range hardcoded globally would work for a single region but fails as the product expands to different climates (e.g., Europe's summer is May–August; South India's green flush is October–November). Storing `season_start_month` and `season_end_month` on each patch row makes this configurable without any code change.

---

## Limitations of This POC

| Limitation | Impact |
|---|---|
| **Sequential year computation** — years are processed one after another, not in parallel | 4-year range takes ~60–90s; 10-year range would take ~3–4 min |
| **24h cache only** — no background refresh | First load after cache expiry is always slow |
| **Single season window per patch** — no sub-season analysis | Can't detect within-season drought events |
| **No per-pixel map** — only a single mean NDVI value per patch per year | Can't show spatial variation within a patch |
| **GEE free tier** — 250,000 requests/month, 100 concurrent requests | Needs a paid GEE Cloud Project at scale |
| **10m resolution** — sufficient for 4-acre patches, overkill for large farms | Fine for this use case |

---

## Alternatives We Could Use

### A. Different satellite source

| Source | Resolution | Revisit | Free? | Notes |
|---|---|---|---|---|
| **Sentinel-2** *(chosen)* | 10m | 5 days | Yes | Best free option for vegetation |
| **Landsat 8/9** | 30m | 16 days | Yes | Longer historical record (1972–), coarser resolution |
| **MODIS** | 250m | 1 day | Yes | Good for large areas, too coarse for 4-acre patches |
| **Planet Labs** | 3m | Daily | No ($$$) | Best resolution, subscription required |
| **Maxar** | 0.5m | On demand | No ($$$) | Very high resolution, very expensive |

For 4-acre plots at the current scale, Sentinel-2 is the right choice. Planet Labs would be the upgrade if sub-metre resolution matters.

### B. Different NDVI delivery mechanism

| Option | How | Pros | Cons |
|---|---|---|---|
| **Google Earth Engine (Server-side)** *(chosen)* | GEE JS/Python API → compute in GEE cloud | Free, no data download, scalable | 90s latency per patch, GEE quota limits |
| **STAC + local compute** | Fetch COG tiles from element84/AWS, compute NDVI locally | No GEE dependency, full control | Complex tile fetching, need compute infra |
| **SentinelHub** | Commercial API, returns NDVI as image or stats | Simple REST API, fast (~2–5s) | Paid — ~$30–100/month for moderate usage |
| **Copernicus Data Space** | EU's free Sentinel data portal + OGC APIs | Free, official source | API is complex, slower than SentinelHub |
| **Pre-computed NDVI products** | Use MODIS MOD13Q1 (pre-computed, 16-day composites) | Zero compute cost, instant | 250m resolution, not per-patch |

**Recommended upgrade path:** SentinelHub for production. It handles the cloud masking, compositing, and statistics server-side with a simple REST call, reducing latency from ~90s to ~3s. Cost is predictable at scale.

### C. Different architecture for the GEE call

| Option | Description | Pros | Cons |
|---|---|---|---|
| **Next.js API route (chosen)** | GEE computation runs inside Next.js server process | No extra service, pure JS stack | Computation blocks a Next.js server thread during GEE requests |
| **Spring Boot calls GEE directly** | Java GEE client (`com.google.api.client`) | Keeps computation in the backend layer | Adds Java GEE dependency, less mature than JS/Python clients |
| **Background job queue** | Enqueue vegetation requests in a job queue (BullMQ, Sidekiq) | Non-blocking, retriable, prioritisable | Needs a queue service (Redis + worker); overkill for POC |
| **Dedicated worker service (Node.js)** | Standalone Node.js process, same JS code as the route | Can scale independently | Adds operational complexity (back to the extra-service problem) |

For production with many concurrent users, the background job queue approach is the right next step — so the 90s GEE computation doesn't hold a server thread and can be polled or pushed via WebSocket.

### D. Additional vegetation indices

NDVI is the most widely used, but others give complementary information:

| Index | Formula | What it reveals |
|---|---|---|
| **NDVI** *(implemented)* | `(NIR − Red) / (NIR + Red)` | General vegetation density and greenness |
| **EVI** | `2.5 × (NIR − Red) / (NIR + 6×Red − 7.5×Blue + 1)` | Less soil-influenced, better in dense canopy |
| **NDWI** | `(Green − NIR) / (Green + NIR)` | Water content in vegetation — early drought stress |
| **SAVI** | `1.5 × (NIR − Red) / (NIR + Red + 0.5)` | Corrects for bare soil exposure (better for sparse vegetation) |
| **NBR** | `(NIR − SWIR) / (NIR + SWIR)` | Burn severity — useful post-fire assessment |

For 4AcresEarth, NDWI (drought stress) and NBR (fire damage) are the most relevant additions.

---

## Path to Production

| What | Change needed |
|---|---|
| **Latency** | Replace sequential year loop with parallel GEE computation, or switch to SentinelHub for <5s responses |
| **GEE quota** | Move from personal GEE account to a GEE Cloud Project with paid quota |
| **Background processing** | Move vegetation computation to a BullMQ job queue so page load is not blocked |
| **Invalidation** | Add a "refresh" button that bypasses the 24h cache for a specific patch |
| **Per-pixel map** | Use GEE `getThumbUrl` or a WMS endpoint to render an NDVI heat map overlay on Mapbox |
| **Season window UI** | Let land owners configure `season_start_month` / `season_end_month` in the patch settings |
| **More indices** | Add NDWI (drought) and NBR (fire) to the same computation pipeline — minimal extra cost since the composite already exists |
| **Historical depth** | Extend `year_start` back to 2017 (Sentinel-2 launch) for a full 8-year trend |
