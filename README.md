# 4Acres Earth — Geospatial POC

Real environmental intelligence per 4-acre patch: biodiversity, soil, and carbon data fetched from public APIs and displayed on an interactive satellite map.

**Stack:** Java 21 + Spring Boot 3.3.4 · PostgreSQL 15 + PostGIS 3.4 · Next.js 14 · Mapbox GL JS v3 · Docker Compose

---

## Prerequisites

| Tool | Version |
|------|---------|
| Docker | 24+ (with Compose v2) |
| Node.js | 18+ (for GFW registration script only) |
| Mapbox account | Public token required |
| GFW API key | Optional — Carbon data shows 0 without it |

---

## 1. Clone and configure

```bash
git clone <repo-url>
cd 4-acres-tech-poc

cp .env.example .env
```

Edit `.env` with your real credentials:

```env
MAPBOX_TOKEN=pk.eyJ1...          # Your Mapbox public token (required)
GFW_API_KEY=your_key_here        # Global Forest Watch API key (optional)
```

Get a free Mapbox token at [mapbox.com](https://account.mapbox.com).  
Get a GFW key at [api.resourcewatch.org](https://api.resourcewatch.org).

---

## 2. Start all services

```bash
docker compose up --build
```

First run takes 3–5 minutes (Maven + npm build inside Docker). Subsequent starts are fast.

Watch for these ready signals:

```
db   | database system is ready to accept connections
api  | Started FourAcresApplication in X seconds
```

The frontend is ready when port 3000 is listening (check with `curl -s http://localhost:3000`).

---

## 3. Verify the API

```bash
# Should return 10 patches with GeoJSON boundaries
curl -s http://localhost:8080/api/patches | python3 -m json.tool | head -30

# Get the first patch ID and fetch its insights
PATCH_ID=$(curl -s http://localhost:8080/api/patches | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")
curl -s http://localhost:8080/api/patches/$PATCH_ID/insights | python3 -m json.tool
```

Expected insights response:
```json
{
  "biodiversity": { "speciesCount": 42, "topSpecies": [...], "threatenedCount": 3 },
  "soil": { "organicCarbonGKg": 21.5, "ph": 5.7, "clayPercent": 32.4 },
  "carbon": { "treeCoverPercent": 0.0, "carbonDensityMgHa": 0.0, "coverLossHa": 0.0 }
}
```

> Carbon values are 0 until Step 4 (GFW registration). Biodiversity and soil fetch live from GBIF and SoilGrids.

---

## 4. Open the app

| URL | What you see |
|-----|-------------|
| `http://localhost:3000` | Rotating Mapbox globe with 10 patch pins |
| `http://localhost:3000/explore` | Satellite map + ecosystem filter sidebar + insights drawer |

On `/explore`:
- Click a patch card in the sidebar → map flies to it, polygon highlights green
- Insights drawer loads biodiversity + soil data (live from APIs on first load, cached 24h after)
- Use the ecosystem filter buttons to narrow the patch list

---

## 5. Enable Carbon data (optional)

Carbon data requires each patch to be registered with the Global Forest Watch Geostore API. Run this once after the stack is up:

```bash
GFW_API_KEY=your_key_here node scripts/register-gfw-geostores.js
```

The script prints `UPDATE patches SET gfw_geostore_id = '...' WHERE name = '...'` SQL for each patch. Run those statements against the database:

```bash
docker compose exec db psql -U fouracres -d fouracres
# paste the UPDATE statements
```

Then invalidate the cache (or wait 24h) and reload insights — Carbon cards will populate.

---

## 6. Run backend tests

Tests run inside Docker without a live database (Mockito mocks):

```bash
docker run --rm \
  -v "$(pwd)/backend:/app" \
  -v ~/.m2:/root/.m2 \
  -w /app \
  maven:3.9-eclipse-temurin-21-alpine \
  mvn test -q
```

Expected: **12 tests, 0 failures**.

---

## Troubleshooting

**Port 5432 already in use**  
Another PostgreSQL container is running. Either stop it (`docker stop <name>`) or change the `db` port mapping in `docker-compose.yml` to e.g. `"5433:5432"`.

**`api` service fails to start**  
Check logs: `docker compose logs api`. Common causes:
- `db` not healthy yet — wait 30s and retry
- Flyway migration failed — check for schema conflicts

**Mapbox map is blank**  
`NEXT_PUBLIC_MAPBOX_TOKEN` is missing or invalid. Verify your `.env` and rebuild: `docker compose up --build frontend`.

**Carbon data is all zeros**  
Expected until Step 4 (GFW registration). The GfwClient silently returns zeros when `gfw_geostore_id` is null.

**Insights load slowly on first click**  
Normal — the backend fetches GBIF, SoilGrids, and GFW in parallel. Subsequent loads are instant (24h cache).
# poc-4acres
