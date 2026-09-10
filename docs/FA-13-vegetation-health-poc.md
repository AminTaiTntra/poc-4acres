# FA-13: Vegetation Health + Historical Trend

**Author:** Amin Tai  
**Status:** POC Successful  
**Technology:** Google Earth Engine + Sentinel-2  
**Capability:** Vegetation Health

---

## What are we trying to prove?

Can 4 Acres determine whether vegetation on a specific land parcel is healthy, improving, or degrading over time?

---

## What did we prove?

**Yes.** For a registered land polygon, we can use Sentinel-2 imagery through Google Earth Engine to calculate:

- Current vegetation health
- Historical NDVI
- Baseline NDVI
- % change vs baseline
- Improving / Stable / Degrading trend
- Good / Fair / Poor condition

The POC successfully produced these results at **10m resolution**, with approximately **30–90 seconds** processing time per uncached parcel.

---

## How does it work?

```
4-Acre Polygon
      ↓
Sentinel-2
      ↓
Google Earth Engine
      ↓
Cloud + Seasonal Filtering
      ↓
NDVI Calculation  (NIR − Red) / (NIR + Red)
      ↓
Historical Comparison (2022 → present)
      ↓
Vegetation Health & Trend
```

### Example result

| Metric | Value |
|---|---|
| Current NDVI | 0.7465 |
| Baseline NDVI | 0.5154 |
| Change | +44.84% |
| Trend | Improving |
| Condition | Good |
| Resolution | 10m |
| Source | Sentinel-2 |

---

## Technology used

| Area | Decision |
|---|---|
| Satellite | Sentinel-2 (ESA / Copernicus) |
| Processing | Google Earth Engine |
| Resolution | 10m |
| Backend integration | Next.js API route + Spring Boot |
| Storage / cache | PostgreSQL (24h TTL) |
| Map visualisation | Mapbox GL JS |
| **Status** | **Proceed** |

### Services & APIs

| Service | Role | Cost |
|---|---|---|
| **Google Earth Engine** | Cloud satellite computation — cloud masking, median compositing, NDVI band maths, `reduceRegion` stats | Free (developer tier) |
| **Copernicus / Sentinel-2** (`COPERNICUS/S2_SR_HARMONIZED`) | Surface-reflectance imagery at 10m, 5-day revisit, globally available from 2017 | Free |
| **PostgreSQL** | Caches computed NDVI results per patch for 24 hours | Self-hosted |
| **Mapbox GL JS** | Map display, 3D terrain, satellite basemap | Existing POC dependency |

---

## Why this approach?

- Free Sentinel-2 data — no satellite subscription required
- Global coverage — works for any registered polygon worldwide
- 10m resolution is suitable for our 4-acre use case
- No need to build satellite-processing infrastructure — GEE handles it
- Historical analysis is possible back to 2017 (Sentinel-2 launch)
- Can be extended to other vegetation indicators (NDWI, EVI, NBR) with minimal additional work

The POC found Sentinel-2 to be the appropriate current choice. Higher-resolution commercial imagery such as Planet Labs (3m, daily) can be considered later if sub-parcel detail is required.

---

## Limitations

- Initial computation is slow (~30–90 sec per parcel, sequential year-by-year GEE calls)
- Current implementation calculates a single average NDVI per parcel — no per-pixel map overlay yet
- No within-season analysis — only annual composites during a configured season window
- Current NDVI thresholds (good ≥ 0.6, fair ≥ 0.3, poor < 0.3) need ecological validation per region
- Production-scale processing requires a background / async architecture (e.g. BullMQ job queue)
- GEE free tier limits apply at scale (250k requests/month, 100 concurrent)

---

## Alternatives considered

| Area | Alternative | Why not chosen |
|---|---|---|
| Satellite source | Landsat 8/9 (30m), MODIS (250m), Planet Labs (3m) | Landsat/MODIS too coarse for 4-acre plots; Planet requires paid subscription |
| Delivery API | SentinelHub | Fast (~3s) but paid (~$30–100/month); right choice for production |
| GEE language | Python (FastAPI microservice) | Migrated to Next.js API route — keeps stack to two languages (Java + JS) and removes a fourth Docker service |
| Architecture | Background job queue (BullMQ) | Right for production; overkill for POC |

---

## Path to production

| What | Change needed |
|---|---|
| Latency | Switch to SentinelHub REST API (~3s per patch) or parallel GEE year computation |
| Scale | Move from personal GEE account to GEE Cloud Project with paid quota |
| Non-blocking | Move computation to BullMQ job queue; return status 202 + poll / WebSocket |
| Per-pixel map | Use GEE `getThumbUrl` or WMS to render NDVI heat map overlay on Mapbox |
| More indices | Add NDWI (drought stress) and NBR (fire damage) — composite already exists, minimal extra cost |
| Historical depth | Extend `year_start` to 2017 for a full 8-year baseline |
| Season config UI | Let land owners set `season_start_month` / `season_end_month` in patch settings |

---

## Current Integrations & References

The integration architecture is provider-agnostic. The following providers and platforms are currently in use across the MVP data and integration approach.

| Integration | Purpose |
|---|---|
| **Google Earth Engine** | Satellite imagery computation — NDVI, cloud masking, seasonal compositing |
| **Copernicus / Sentinel-2** | Free 10m satellite imagery, globally available from 2017 |
| **GBIF** | Biodiversity data — species count, threatened species, top species names |
| **SoilGrids** | Soil data — organic carbon, pH, clay % at 0–5cm depth |
| **Global Forest Watch** | Forest and environmental intelligence — tree cover %, carbon density, cover loss |
| **OpenWeather** | Weather and environmental data |
| **Open-Meteo** | Weather forecast data |
| **OpenEPI** | Environmental / geospatial data |
| **Google Maps Platform** | Mapping and location services |
| **Mapbox** | Maps and geospatial visualisation — satellite basemap, 3D terrain, polygon drawing |

### Reference Links

- [Google Earth Engine API](https://developers.google.com/earth-engine)
- [Copernicus Data Space Ecosystem](https://dataspace.copernicus.eu)
- [GBIF API](https://www.gbif.org/developer/summary)
- [SoilGrids](https://soilgrids.org/)
- [Global Forest Watch Data API](https://data-api.globalforestwatch.org)
- [OpenWeather API](https://openweathermap.org/api)
- [Open-Meteo](https://open-meteo.com)
- [OpenEPI Data Catalog](https://api.openepi.io)
- [Google Maps Platform](https://developers.google.com/maps)
- [Mapbox](https://docs.mapbox.com)
