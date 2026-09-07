# Land Claim POC — Design Spec

**Goal:** Let a land owner register a parcel by drawing it on a satellite map, and let a steward claim that parcel and view it as an immersive digital twin — all without authentication.

**Architecture:** Extend the existing Spring Boot + PostGIS backend with two new endpoints and one Flyway migration. Add three new Next.js pages. No new services, no auth layer. The steward's `/patch/[id]` URL is their only "key" to their land.

**Tech Stack:** Java 21 · Spring Boot 3.3.4 · PostGIS 3.4 · Next.js 14 · Mapbox GL JS v3 · `@mapbox/mapbox-gl-draw` · Docker Compose (unchanged)

**Prior spec:** `docs/superpowers/specs/2026-09-04-geospatial-poc-design.md`

---

## Global Constraints

- Java 21, Spring Boot 3.3.4, PostgreSQL 15 + PostGIS 3.4
- Next.js 14 App Router, TypeScript strict, Tailwind CSS v4
- `@mapbox/mapbox-gl-draw` v1.x for polygon drawing
- Flyway 10.x with `flyway-database-postgresql` module (already in pom.xml)
- No authentication, no sessions, no passwords
- One steward per patch (enforced by UNIQUE constraint on `claims.patch_id`)
- All existing API clients (GBIF, SoilGrids, GFW) and InsightsService are reused unchanged
- CORS already configured in `WebConfig.java` — no changes needed
- Existing 10 seed patches remain; they appear as AVAILABLE on the map

---

## Data Model

### Migration V3 — `V3__add_claim_support.sql`

```sql
-- Add status and owner columns to patches
ALTER TABLE patches
  ADD COLUMN status     VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',
  ADD COLUMN owner_name VARCHAR(255);

-- Claims table: one steward per patch
CREATE TABLE claims (
  id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  patch_id      UUID        NOT NULL UNIQUE REFERENCES patches(id) ON DELETE CASCADE,
  steward_name  VARCHAR(255) NOT NULL,
  steward_email VARCHAR(255) NOT NULL,
  claimed_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### Patch status values
- `AVAILABLE` — default; stewards can claim
- `CLAIMED` — a steward has claimed this patch; no further claims accepted

---

## Backend

### New / modified files

| File | Action |
|------|--------|
| `db/migration/V3__add_claim_support.sql` | Create |
| `model/PatchStatus.java` | Create (enum: AVAILABLE, CLAIMED) |
| `model/Claim.java` | Create (JPA entity) |
| `repository/ClaimRepository.java` | Create |
| `dto/ClaimDto.java` | Create |
| `dto/PatchRegistrationRequest.java` | Create |
| `dto/ClaimRequest.java` | Create |
| `service/ClaimService.java` | Create |
| `controller/ClaimController.java` | Create |
| `model/Patch.java` | Modify (add status, ownerName fields) |
| `dto/PatchDto.java` | Modify (add status, ownerName fields) |

### New endpoints

#### `POST /api/patches` — Register a land parcel

Request body:
```json
{
  "name": "Sunlit Meadow",
  "description": "A 4-acre meadow in the Scottish Highlands.",
  "ownerName": "Jane Smith",
  "country": "Scotland",
  "ecosystemType": "HIGHLAND",
  "boundary": {
    "type": "Polygon",
    "coordinates": [[[-3.89, 57.12], [-3.88, 57.12], [-3.88, 57.13], [-3.89, 57.13], [-3.89, 57.12]]]
  }
}
```

Processing:
1. Parse the GeoJSON Polygon into a JTS `Polygon` using `GeometryFactory` with SRID 4326
2. Compute `centerLng` and `centerLat` from `boundary.getCentroid().getX/Y()`
3. Compute `areaAcres` using PostGIS via a native query: `SELECT ST_Area(ST_GeogFromWKB(?)) / 4046.86` (convert m² to acres)
4. Persist as a new `Patch` with `status = AVAILABLE`
5. Return `PatchDto` with HTTP 201

Validation: name required (1–100 chars), polygon must have ≥ 4 coordinate pairs (first = last), ecosystemType must match enum.

#### `POST /api/patches/{id}/claim` — Claim a patch

Request body:
```json
{
  "stewardName": "Alice Johnson",
  "stewardEmail": "alice@example.com"
}
```

Processing:
1. Load patch by ID — 404 if not found
2. Check `patch.status == AVAILABLE` — 409 Conflict if already CLAIMED, body: `{"error": "This patch has already been claimed"}`
3. Create and save `Claim` entity
4. Set `patch.status = CLAIMED`, save
5. Return `ClaimDto` with HTTP 201

#### `GET /api/patches/{id}/claim` — Get claim details

Returns `ClaimDto` (id, patchId, stewardName, stewardEmail, claimedAt) or 404 if unclaimed.

### Model: `Claim.java`

```java
@Entity @Table(name = "claims")
public class Claim {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne @JoinColumn(name = "patch_id", nullable = false, unique = true)
    private Patch patch;

    @Column(name = "steward_name", nullable = false)
    private String stewardName;

    @Column(name = "steward_email", nullable = false)
    private String stewardEmail;

    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt = Instant.now();

    // getters + setters
}
```

### `PatchDto` additions

Add fields: `status` (String), `ownerName` (String).

### `ClaimService` logic

```java
@Transactional
public ClaimDto claimPatch(UUID patchId, ClaimRequest request) {
    Patch patch = patchRepository.findById(patchId)
        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
    if (patch.getStatus() != PatchStatus.AVAILABLE) {
        throw new ResponseStatusException(CONFLICT, "This patch has already been claimed");
    }
    Claim claim = new Claim();
    claim.setPatch(patch);
    claim.setStewardName(request.getStewardName());
    claim.setStewardEmail(request.getStewardEmail());
    claimRepository.save(claim);
    patch.setStatus(PatchStatus.CLAIMED);
    patchRepository.save(patch);
    return toDto(claim);
}
```

---

## Frontend

### New / modified files

| File | Action |
|------|--------|
| `app/register/page.tsx` | Create |
| `app/patch/[id]/page.tsx` | Create |
| `app/components/DrawMap.tsx` | Create |
| `app/components/ClaimModal.tsx` | Create |
| `app/components/MyPatchMap.tsx` | Create |
| `app/components/PatchInfoCard.tsx` | Create |
| `app/explore/page.tsx` | Modify (status colours, claim button) |
| `app/lib/types.ts` | Modify (add status, ownerName, ClaimDto) |
| `app/lib/api.ts` | Modify (add registerPatch, claimPatch, getClaim) |
| `app/lib/queries.ts` | Modify (add useRegisterPatch, useClaimPatch, useClaim) |

### New npm package

```
@mapbox/mapbox-gl-draw  ^1.4.3
@types/mapbox__mapbox-gl-draw  ^1.4.3
```

---

### `/register` — Land Registration Page

Full-screen layout: Mapbox satellite map behind, form panel overlaid on the left (400px wide, dark glass background).

**DrawMap component (`DrawMap.tsx`):**
- Renders `Map` in satellite mode, globe disabled (use `mercator` projection for drawing accuracy)
- Mounts `MapboxDraw` in `draw_polygon` mode on load
- Exposes `onPolygonDrawn(coordinates: number[][])` callback — fires when the user completes a polygon
- Shows a "Draw your land boundary" instruction banner until the polygon is complete
- After polygon is complete, shows area estimate in acres (computed client-side from coordinate bounding box as approximate — exact from backend on submit)

**Form fields:**
- Land name (required)
- Your name / owner name (required)
- Country (required)
- Ecosystem type (dropdown: FOREST, WETLAND, SAVANNA, HIGHLAND, DRYLAND, COASTAL)
- Description (optional, textarea)

**Submit flow:**
1. POST to `/api/patches` with form data + polygon coordinates
2. On success: show success banner with patch name and a "View on map →" link to `/explore`
3. On error: show inline error message

---

### `/explore` — Updated Explore Page

**Patch colour coding:**
- Available patches: amber fill `#f59e0b` at 40% opacity, amber outline
- Claimed patches: green fill `#10b981` at 40% opacity, green outline (matches existing style)
- Hovered patch: brighten fill to 70% opacity

**Sidebar additions:**
- Status badge next to patch name: `AVAILABLE` (amber pill) or `CLAIMED` (green pill)
- Owner name shown under ecosystem tag
- If AVAILABLE: "Claim This Patch" button (emerald, full-width) at bottom of sidebar
- If CLAIMED: "View Digital Twin →" link to `/patch/[id]`

**ClaimModal component:**
- Opens when "Claim This Patch" is clicked
- Fields: Your Name (required), Your Email (required)
- Submit → POST `/api/patches/{id}/claim`
- On success: redirect to `/patch/[id]`
- On 409: show "This patch was just claimed by someone else"

---

### `/patch/[id]` — My Patch Digital Twin

The centrepiece of the POC. Full-screen, immersive.

**MyPatchMap component (`MyPatchMap.tsx`):**

Mapbox satellite map with:
1. **Terrain 3D:**
   ```js
   map.addSource('mapbox-dem', {
     type: 'raster-dem',
     url: 'mapbox://mapbox.mapbox-terrain-v2',
     tileSize: 512,
   })
   map.setTerrain({ source: 'mapbox-dem', exaggeration: 1.5 })
   ```
2. **Sky layer** (atmosphere):
   ```js
   map.addLayer({ id: 'sky', type: 'sky',
     paint: { 'sky-type': 'atmosphere', 'sky-atmosphere-sun': [0.0, 90.0],
               'sky-atmosphere-sun-intensity': 15 }
   })
   ```
3. **Fly-in animation on load:**
   ```js
   map.flyTo({
     center: [patch.centerLng, patch.centerLat],
     zoom: 15.5, pitch: 60, bearing: -20, duration: 3500,
     essential: true,
   })
   ```
4. **Patch boundary:** glowing green polygon
   ```js
   // Fill layer: green at 25% opacity
   // Line layer: #10b981, width 3, blur 1
   ```

**Floating overlay cards (absolute, glass):**

Positioned over the map using `position: absolute` within the map container:

- **Top-left — Identity card:**
  ```
  [Patch name]            [CLAIMED badge]
  [Ecosystem] · [Country]
  Steward: [steward name]
  Land Owner: [owner name]
  Claimed [relative date]
  ```

- **Bottom strip (3 cards, left to right):**
  - Biodiversity: species count, threatened count, top species
  - Soil Health: organic carbon, pH, clay %
  - Carbon/Forest: tree cover %, carbon density, cover loss

  Card style: `bg-black/60 backdrop-blur-md border border-white/10 rounded-xl p-4 text-white`

**Data fetching:** `usePatch(id)` + `usePatchInsights(id)` + `useClaim(id)` — all existing hooks, one new `useClaim` hook calling `GET /api/patches/{id}/claim`.

**Loading state:** map loads immediately, cards show skeleton shimmer while insights fetch.

**Page header (minimal):**
- `4Acres Earth` logo text top-right, links back to `/explore`
- No other chrome — map fills the viewport

---

## API type additions (`types.ts`)

```typescript
export type PatchStatus = 'AVAILABLE' | 'CLAIMED'

export interface Patch {
  // existing fields...
  status: PatchStatus
  ownerName: string | null
}

export interface ClaimDto {
  id: string
  patchId: string
  stewardName: string
  stewardEmail: string
  claimedAt: string
}

export interface PatchRegistrationRequest {
  name: string
  description?: string
  ownerName: string
  country: string
  ecosystemType: string
  boundary: GeoJsonPolygon
}

export interface GeoJsonPolygon {
  type: 'Polygon'
  coordinates: number[][][]
}

export interface ClaimRequest {
  stewardName: string
  stewardEmail: string
}
```

---

## Testing

**Backend (unit tests with Mockito):**
- `ClaimServiceTest` — 4 tests:
  1. `claimPatch_success` — saves claim, sets status CLAIMED, returns ClaimDto
  2. `claimPatch_alreadyClaimed_throws409` — status CLAIMED → ResponseStatusException CONFLICT
  3. `claimPatch_notFound_throws404`
  4. `registerPatch_success` — creates patch with AVAILABLE status, returns PatchDto

**Frontend:**
- `ClaimModal` — renders form, submit calls `claimPatch`, redirects on success
- `MyPatchMap` — renders map container with correct mapboxAccessToken

---

## Error states

| Scenario | Behaviour |
|----------|-----------|
| Patch not found on `/patch/[id]` | Show "Patch not found" with link back to `/explore` |
| Insights fetch fails | Cards show "Data unavailable" gracefully |
| Claim race condition (409) | ClaimModal shows "Just claimed — try another patch" |
| Register with no polygon | Form shows "Please draw your land boundary first" |
| Register with invalid ecosystem | Backend returns 400, form shows inline error |
