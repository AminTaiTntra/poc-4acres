# Vegetation Health + Historical Trend — Design Spec

**Jira:** FA-13 (subtask of FA-11 POC)
**Date:** 2026-09-09
**Status:** Approved

---

## 1. Problem Statement

Answer the question: *Can we tell whether vegetation on this specific land is healthy, and whether it is improving or degrading?*

This is the most critical POC because it produces the first real answer to "Is the land getting better or worse?" and becomes the foundation for future impact measurement (before/after NDVI per action).

---

## 2. Data Source

**Dataset:** `COPERNICUS/S2_SR_HARMONIZED` via Google Earth Engine
**Satellite:** Sentinel-2 (multispectral, 10m resolution, ~5-day revisit)
**Access:** GEE Python client (`earthengine-api`), authenticated via GCP service account

---

## 3. Architecture

```
Frontend (Next.js)
  └─ VegetationCard
        │  GET /api/patches/{id}/vegetation
        ▼
Spring Boot — VegetationService → NdviClient
        │  POST http://ndvi-service:8001/ndvi
        ▼
ndvi-service (Python FastAPI — new Docker Compose service)
  └─ earthengine-api (GCP service account)
        │
        ▼
Google Earth Engine — COPERNICUS/S2_SR_HARMONIZED
```

No satellite imagery is downloaded locally. All computation runs server-side on GEE. The Python microservice is the only component that touches `earthengine-api`.

---

## 4. Database Schema

### Migration V5 — Patch season window
```sql
ALTER TABLE patches
  ADD COLUMN season_start_month INT NOT NULL DEFAULT 10,
  ADD COLUMN season_end_month   INT NOT NULL DEFAULT 11;
```
Default: October–November (post-monsoon, India). Configurable per polygon so monsoon vs dry-season variation is never mistaken for ecological change.

### Migration V6 — Vegetation cache
```sql
ALTER TABLE patches
  ADD COLUMN vegetation_data      JSONB,
  ADD COLUMN vegetation_cached_at TIMESTAMP;
```
Same 24-hour cache pattern as existing biodiversity/soil/carbon data.

---

## 5. ndvi-service (Python FastAPI)

### Location
`ndvi-service/` — new top-level directory, added to Docker Compose as service `ndvi-service` on port 8001.

### API Contract

**Request:**
```
POST /ndvi
Content-Type: application/json

{
  "polygon": { "type": "Polygon", "coordinates": [[[...]]] },
  "season_start_month": 10,
  "season_end_month": 11,
  "year_start": 2022,
  "year_end": 2026
}
```

**Response:**
```json
{
  "yearly": [
    { "year": 2022, "ndvi": 0.61, "valid_pixel_pct": 78.3, "observation_date": "2022-10-15" },
    { "year": 2023, "ndvi": 0.64, "valid_pixel_pct": 82.1, "observation_date": "2023-10-12" },
    { "year": 2024, "ndvi": 0.66, "valid_pixel_pct": 75.6, "observation_date": "2024-11-03" },
    { "year": 2025, "ndvi": 0.69, "valid_pixel_pct": 80.2, "observation_date": "2025-10-28" },
    { "year": 2026, "ndvi": 0.72, "valid_pixel_pct": 88.0, "observation_date": "2026-09-06" }
  ],
  "current_ndvi": 0.72,
  "baseline_ndvi": 0.64,
  "change_pct": 12.5,
  "trend": "improving",
  "condition": "good",
  "last_observation": "2026-09-06",
  "resolution_m": 10,
  "source": "Sentinel-2"
}
```

If a year has no images passing the valid-pixel threshold, its entry in `yearly[]` is `null` and it is excluded from the trend calculation.

### GEE Computation Logic (per year)

1. Load `COPERNICUS/S2_SR_HARMONIZED`
2. Filter by polygon bounds + date window `{year}-{start_month}-01` → `{year}-{end_month}-30`
3. Cloud-mask using QA60 band — mask bits 10 (opaque clouds) and 11 (cirrus clouds)
4. Filter images where valid pixel % over the polygon ≥ 10% (discard heavily clouded scenes)
5. Compute NDVI per image: `(B8 - B4) / (B8 + B4)`
6. Reduce to **median composite** across all valid images for the window
7. Compute mean NDVI over the polygon geometry
8. Record `observation_date` as the midpoint of the season window for that year (e.g. for Oct–Nov 2026 → `2026-10-15`)

### Derived Metrics

| Metric | Calculation |
|--------|-------------|
| `baseline_ndvi` | Mean NDVI of years 1–3 of the range |
| `current_ndvi` | NDVI of the most recent year with valid data |
| `change_pct` | `(current - baseline) / baseline × 100` |
| `trend` | Linear regression slope over all valid yearly values: slope > +0.01/yr → `"improving"`, slope < -0.01/yr → `"degrading"`, else `"stable"` |
| `condition` | `"good"` (≥ 0.6) · `"fair"` (0.3–0.6) · `"poor"` (< 0.3) |

### Auth
Service account JSON key mounted as a Docker volume. Path set via env var `GEE_SERVICE_ACCOUNT_KEY`. Initialised once at startup with `ee.Initialize(credentials)`.

### Health endpoint
`GET /health` → `{ "status": "ok" }` — used by Docker Compose healthcheck.

---

## 6. Spring Boot Integration

### New files

| File | Responsibility |
|------|---------------|
| `VegetationData.java` | Response DTO — mirrors ndvi-service JSON |
| `NdviYearlyPoint.java` | Inner DTO for each year in `yearly[]` |
| `NdviClient.java` | `RestTemplate` POST to ndvi-service — same pattern as `GbifClient`, `SoilGridsClient`. `year_start` defaults to 2022, `year_end` defaults to current calendar year. |
| `VegetationService.java` | Reads patch polygon + season window, calls `NdviClient`, writes to JSONB cache |

### Modified files

| File | Change |
|------|--------|
| `Patch.java` | Add `seasonStartMonth`, `seasonEndMonth` fields |
| `PatchController.java` | Add `GET /api/patches/{id}/vegetation` endpoint |
| `application.yml` | Add `ndvi.service-url: http://ndvi-service:8001` |

### Cache logic
```
GET /api/patches/{id}/vegetation
  if vegetation_cached_at is within 24h → return vegetation_data (deserialise JSONB)
  else → call ndvi-service → store JSON to vegetation_data, update vegetation_cached_at → return
```

---

## 7. Frontend — VegetationCard

New card added to the existing 6-card grid on `/patch/[id]`, fetching from `GET /api/patches/{id}/vegetation`.

### Card layout
```
┌─────────────────────────────────┐
│ VEGETATION                      │
│                                 │
│ Current condition    Good       │
│ NDVI                 0.72       │
│ Historical baseline  0.64       │
│ Change               +12.5%     │
│ Trend       ↑ Improving         │
│ Last observation  06 Sep 2026   │
│ Resolution           10m        │
│ Source            Sentinel-2    │
│                                 │
│ [sparkline: 2022–2026 NDVI]     │
└─────────────────────────────────┘
```

### Visual treatment
- **Trend** arrow + colour: green (improving), amber (stable), red (degrading)
- **Condition** badge: green (good), amber (fair), red (poor)
- **Sparkline** — small inline line chart of yearly NDVI values, null years shown as gaps

---

## 8. Docker Compose Changes

### New service: ndvi-service
```yaml
ndvi-service:
  build: ./ndvi-service
  ports: ["8001:8001"]
  environment:
    GEE_SERVICE_ACCOUNT_KEY: /secrets/gee-sa-key.json
  volumes:
    - ./secrets/gee-sa-key.json:/secrets/gee-sa-key.json:ro
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8001/health"]
    interval: 10s
    timeout: 5s
    retries: 5
```

### api service dependency
```yaml
api:
  depends_on:
    db:
      condition: service_healthy
    ndvi-service:
      condition: service_healthy
```

---

## 9. Configuration & Secrets

A GCP service account must be created with the **Earth Engine** role and its JSON key placed at `secrets/gee-sa-key.json` (gitignored). `.env.example` documents this requirement.

---

## 10. Success Criteria

- NDVI trend card renders on `/patch/[id]` with real Sentinel-2 data for a polygon
- Year-over-year comparison uses the same season window, not arbitrary dates
- Cloud-masked scenes with < 10% valid pixels are excluded from the trend
- Results are cached 24h — second load is instant
- A year with no valid scenes is skipped gracefully (no crash, shown as gap in sparkline)
