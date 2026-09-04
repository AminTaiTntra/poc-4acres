# 4Acres Earth — Geospatial Data POC Design

**Date:** 2026-09-04  
**Status:** Approved  
**Scope:** POC — Core geospatial data pipeline and interactive patch visualization

---

## 1. Purpose

Prove that real environmental intelligence (biodiversity, soil, carbon/forest) can be fetched from free public APIs, aggregated per 4-acre patch, and presented on an interactive satellite map — before committing to the full platform build.

This POC is the technical foundation of the "My Patch Dashboard" module from the 4Acres feature list. It does not include user auth, onboarding, community, recognition, or advocacy modules.

---

## 2. Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 21 + Spring Boot 3 |
| Database | PostgreSQL 15 + PostGIS 3.4 |
| ORM | Spring Data JPA + Hibernate Spatial |
| DB Migrations | Flyway |
| Frontend | Next.js 14 (App Router) |
| Map | react-map-gl v7 + Mapbox GL JS v3 |
| Data Fetching | @tanstack/react-query v5 |
| Styling | Tailwind CSS v4 |
| Runtime | Docker Compose (local only — no cloud deployment for POC) |

---

## 3. System Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                    Next.js Frontend :3000                    │
│                                                              │
│  /           Globe hero (Mapbox globe projection)            │
│  /explore    Satellite map + PatchSidebar + InsightsDrawer   │
└──────────────────────┬───────────────────────────────────────┘
                       │ REST (http://localhost:8080)
┌──────────────────────▼───────────────────────────────────────┐
│                 Spring Boot API :8080                        │
│                                                              │
│  GET  /api/patches              → list all patches           │
│  GET  /api/patches/{id}         → patch boundary + metadata  │
│  GET  /api/patches/{id}/insights → aggregated env data       │
│                                                              │
│  InsightsService: fires GBIF + SoilGrids + GFW in parallel  │
│  via CompletableFuture.allOf(), caches result 24 hours       │
└──────────┬────────────────┬──────────────────┬──────────────┘
           │                │                  │
     ┌─────▼──────┐  ┌──────▼──────┐  ┌───────▼──────┐
     │    GBIF    │  │  SoilGrids  │  │ Global Forest│
     │ (no auth)  │  │  (no auth)  │  │ Watch (free  │
     │ species    │  │  soil props │  │ API key)     │
     └────────────┘  └─────────────┘  └──────────────┘
           │
┌──────────▼───────────────────────────────────────────────────┐
│             PostgreSQL 15 + PostGIS :5432                    │
│                                                              │
│  patches         patch boundaries (geometry) + metadata      │
│  insights_cache  JSONB payloads per patch per layer (24h TTL)│
└──────────────────────────────────────────────────────────────┘
```

### Key design decisions

- **Parallel API calls**: GBIF, SoilGrids, and GFW fire concurrently via `CompletableFuture.allOf()`. Total latency equals the slowest single call, not the sum.
- **24-hour DB cache**: Insights are stored in PostgreSQL as JSONB. After first fetch, the patch loads instantly. No Redis needed at POC scale.
- **PostGIS polygon storage**: Patch boundaries stored as `geometry(Polygon, 4326)`. Enables spatial queries (find patches within X km, ecosystem overlap) without rearchitecting later.
- **No Next.js API routes**: Frontend calls Spring Boot directly. No redundant proxy layer.
- **Single map library**: Mapbox GL JS v3 handles both the landing globe (`projection: 'globe'`) and the explore satellite map (`projection: 'mercator'`). No Cesium or Three.js.

---

## 4. Data Model

```sql
-- Stores pre-seeded patch boundaries
CREATE TABLE patches (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name            VARCHAR(100) NOT NULL,
  ecosystem_type  VARCHAR(50)  NOT NULL,  -- FOREST | SAVANNA | WETLAND | COASTAL | HIGHLAND | DRYLAND
  country         VARCHAR(100) NOT NULL,
  description     TEXT,
  center_lat      DECIMAL(10,7) NOT NULL,
  center_lng      DECIMAL(10,7) NOT NULL,
  boundary        GEOMETRY(Polygon, 4326) NOT NULL,  -- 127m × 127m square (~4 acres)
  area_acres      DECIMAL(5,2) NOT NULL DEFAULT 4.0,
  gfw_geostore_id VARCHAR(100),                      -- registered with GFW at seed time
  created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_patches_boundary ON patches USING GIST(boundary);

-- Caches external API responses per patch per layer
CREATE TABLE insights_cache (
  patch_id    UUID        NOT NULL REFERENCES patches(id) ON DELETE CASCADE,
  layer       VARCHAR(20) NOT NULL,   -- BIODIVERSITY | SOIL | CARBON
  payload     JSONB       NOT NULL,
  fetched_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
  expires_at  TIMESTAMP   NOT NULL,
  PRIMARY KEY (patch_id, layer)
);
```

The `boundary` polygon is generated from `center_lat`/`center_lng` at seed time (Flyway migration). It is a fixed 127m × 127m square in WGS84. All external API calls use the center point; the polygon is used only for map rendering.

---

## 5. External API Integration

### 5.1 GBIF — Biodiversity (no auth)

```
GET https://api.gbif.org/v1/occurrence/search
    ?decimalLatitude={lat}
    &decimalLongitude={lng}
    &radius=120
    &limit=300
    &hasCoordinate=true
```

**Extracted fields stored in `payload`:**
- `species_count` — distinct species recorded within 120m radius
- `top_species` — 5 most-recorded species (name + kingdom)
- `threatened_count` — records where `iucnRedListCategory` ∈ {VU, EN, CR}

### 5.2 SoilGrids / ISRIC — Soil (no auth)

```
GET https://rest.soilgrids.org/soilgrids/v2.0/properties/query
    ?lon={lng}
    &lat={lat}
    &property=soc,phh2o,clay
    &depth=0-5cm
    &value=mean
```

**Extracted fields:**
- `organic_carbon_g_kg` — soil organic carbon (raw value ÷ 10 for display)
- `ph` — soil pH (raw value ÷ 10, SoilGrids stores as integer × 10)
- `clay_percent` — clay content (%)

### 5.3 Global Forest Watch — Carbon/Forest (free API key)

**Step 1 — Register geostore (done once at seed time):**
```
POST https://data-api.globalforestwatch.org/dataset/geostore
Body: { "geojson": { patch boundary GeoJSON } }
Response: { "geostore_id": "abc123" }  → stored on patches.gfw_geostore_id
```

**Step 2 — Query forest stats:**
```
GET https://data-api.globalforestwatch.org/dataset/
    umd_tree_cover_density_2020/latest/query
    ?geostore_id={gfw_geostore_id}
```

**Extracted fields:**
- `tree_cover_percent` — % canopy density (>30% threshold)
- `carbon_density_mg_ha` — above-ground biomass carbon (Mg/ha)
- `cover_loss_ha` — hectares of cover lost since 2001

---

## 6. Spring Boot Service Shape

```java
// InsightsService.java
public PatchInsights getInsights(UUID patchId) {
    return cache.findFresh(patchId).orElseGet(() -> {
        Patch patch = patchRepository.findById(patchId).orElseThrow();

        var bio    = CompletableFuture.supplyAsync(() -> gbifClient.fetch(patch));
        var soil   = CompletableFuture.supplyAsync(() -> soilGridsClient.fetch(patch));
        var carbon = CompletableFuture.supplyAsync(() -> gfwClient.fetch(patch));

        CompletableFuture.allOf(bio, soil, carbon).join();

        var insights = new PatchInsights(bio.join(), soil.join(), carbon.join());
        cache.put(patchId, insights, Duration.ofHours(24));
        return insights;
    });
}
```

**API endpoints:**
- `GET /api/patches` → list of all patches (id, name, ecosystem_type, country, center_lat, center_lng, boundary GeoJSON)
- `GET /api/patches/{id}` → single patch detail
- `GET /api/patches/{id}/insights` → `{ biodiversity: {...}, soil: {...}, carbon: {...} }`

---

## 7. Frontend Structure

```
app/
├── page.tsx                  → Landing: globe hero + CTA
├── explore/
│   └── page.tsx              → Main POC experience
├── components/
│   ├── GlobeHero.tsx         → Mapbox globe projection, auto-rotation, patch pins
│   ├── PatchMap.tsx          → Satellite basemap, polygon overlay, fly-to on select
│   ├── PatchSidebar.tsx      → Ecosystem filter tabs + PatchCard list
│   ├── InsightsDrawer.tsx    → 3-panel data display
│   ├── BiodiversityCard.tsx  → Species count, top species, threatened count
│   ├── SoilCard.tsx          → Organic carbon, pH, clay %
│   └── CarbonCard.tsx        → Tree cover %, carbon density, cover loss
└── lib/
    ├── api.ts                → fetch wrappers for Spring Boot endpoints
    └── queries.ts            → react-query query definitions
```

**Data flow on `/explore`:**

1. `useQuery(['patches'])` → fetches patch list once, `staleTime: Infinity`
2. User clicks a `PatchCard` → `setSelectedPatchId`
3. `PatchMap` `useEffect(selectedPatchId)` → `map.flyTo(center, zoom: 15)`, updates GeoJSON source
4. `useQuery(['insights', selectedPatchId])` → fetches `/api/patches/{id}/insights`, `staleTime: 24h`
5. `InsightsDrawer` shows skeleton while loading, renders cards on success

**Globe hero on `/`:**
```typescript
map.setProjection('globe')
map.setFog({ color: '#0a1628', 'high-color': '#1a3a6b', 'horizon-blend': 0.02 })
// Slow rotation: requestAnimationFrame loop incrementing map.getBearing()
// All 10 patch centers rendered as glowing symbol layer on globe surface
```

---

## 8. Pre-seeded Patches

Loaded via Flyway migration `V2__seed_patches.sql`. Polygon boundaries computed from center coordinates as 127m × 127m squares.

| # | Name | Ecosystem | Country | Lat | Lng |
|---|------|-----------|---------|-----|-----|
| 1 | Amazon Várzea Forest | FOREST | Brazil | -3.4653 | -62.2159 |
| 2 | Sundarbans Mangrove | WETLAND | Bangladesh | 21.9497 | 89.1833 |
| 3 | Maasai Mara Savanna | SAVANNA | Kenya | -1.5442 | 35.1042 |
| 4 | Cairngorms Highland | HIGHLAND | Scotland | 57.1230 | -3.8940 |
| 5 | Borneo Rainforest | FOREST | Malaysia | 2.1896 | 113.9944 |
| 6 | Daintree Rainforest | FOREST | Australia | -16.1700 | 145.4200 |
| 7 | Yellowstone Forest | FOREST | USA | 44.4280 | -110.5885 |
| 8 | Sahel Dryland | DRYLAND | Mali | 14.8833 | -5.0000 |
| 9 | Białowieża Primeval Forest | FOREST | Poland | 52.7069 | 23.8601 |
| 10 | Patagonian Steppe | HIGHLAND | Argentina | -50.3498 | -72.2660 |

---

## 9. Local Runtime — Docker Compose

All services run locally via Docker Compose. No cloud account required.

```yaml
# docker-compose.yml
services:
  db:
    image: postgis/postgis:15-3.4
    environment:
      POSTGRES_DB: fouracres
      POSTGRES_USER: fouracres
      POSTGRES_PASSWORD: fouracres_dev
    ports: ["5432:5432"]
    volumes:
      - pgdata:/var/lib/postgresql/data

  api:
    build: ./backend
    ports: ["8080:8080"]
    env_file: .env
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/fouracres
      SPRING_DATASOURCE_USERNAME: fouracres
      SPRING_DATASOURCE_PASSWORD: fouracres_dev
    depends_on:
      db:
        condition: service_healthy

  frontend:
    build: ./frontend
    ports: ["3000:3000"]
    environment:
      NEXT_PUBLIC_API_URL: http://localhost:8080
      NEXT_PUBLIC_MAPBOX_TOKEN: ${MAPBOX_TOKEN}
    depends_on: [api]

volumes:
  pgdata:
```

**`.env` (gitignored):**
```
MAPBOX_TOKEN=pk.eyJ1...
GFW_API_KEY=...
```

**To run:**
```bash
cp .env.example .env   # fill in Mapbox + GFW keys
docker compose up --build
# Frontend: http://localhost:3000
# API:      http://localhost:8080
```

---

## 10. External Dependencies — Setup Requirements

| Dependency | Auth needed | How to get | Time |
|-----------|-------------|------------|------|
| Mapbox GL JS | Public token | Free account at mapbox.com | Instant |
| GBIF | None | Public API, no registration | None |
| SoilGrids | None | Public API, no registration | None |
| Global Forest Watch | Free API key | Register at globalforestwatch.org | Same day |

---

## 11. Known Constraints & Future Decisions

- **Spring Boot cold start**: On Docker with min-instances:0 equivalent behaviour, first request after idle takes ~4–6s. Acceptable for POC; GraalVM native-image compilation eliminates it when needed.
- **GFW geostore registration**: The 10 patch geostore IDs must be registered before seeding. A one-time setup script handles this; IDs are hardcoded into `V2__seed_patches.sql`.
- **4-acre polygon shape**: Fixed 127m × 127m square for POC. Real stewardship patches will need user-drawn polygon support (post-POC sprint).
- **GCP deployment**: Deferred. When ready: Cloud Run (API), Cloud SQL (DB), Artifact Registry (images), Secret Manager (env vars), Vercel (frontend).
- **Impact measurement methodology**: Not addressed in this POC. The carbon density figure from GFW is a reference measurement, not an attribution calculation. The full MRV (Measurement, Reporting, Verification) methodology is a separate workstream.
