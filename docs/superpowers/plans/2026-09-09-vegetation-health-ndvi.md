# Vegetation Health + Historical Trend — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate Sentinel-2 NDVI time-series via Google Earth Engine to show whether a land patch's vegetation is healthy and improving or degrading.

**Architecture:** A new Python FastAPI microservice (`ndvi-service`) runs GEE computation server-side and exposes `POST /ndvi`. Spring Boot adds `NdviClient` + `VegetationService` that call it and cache results in the existing `insights_cache` table with layer `"VEGETATION"`. The frontend adds a `VegetationCard` that fetches from `GET /api/patches/{id}/vegetation`.

**Tech Stack:** Python 3.11 · FastAPI · earthengine-api · Java 21 · Spring Boot 3.3.4 · Next.js 14 · Tailwind CSS · JUnit 5 · Mockito · pytest

**Spec:** `docs/superpowers/specs/2026-09-09-vegetation-health-ndvi-design.md`

## Global Constraints

- Base Java package: `io.fouracres`
- Java clients use `java.net.http.HttpClient` — no RestTemplate, no WebClient
- Java DTOs are `record` types with `@JsonProperty` for snake_case deserialization from ndvi-service
- Caching uses existing `InsightsCacheRepository` with layer string `"VEGETATION"` and 24h TTL
- Next migration is V4 (V1–V3 exist)
- Python service: FastAPI on port 8001, GEE auth via service account JSON at `GEE_SERVICE_ACCOUNT_KEY` env var
- Frontend cards on top row use the Glass pattern: `bg-black/60 backdrop-blur-md border border-white/10 rounded-xl p-4 text-white`
- No new npm packages — sparkline is inline SVG

---

### Task 1: ndvi-service — Python skeleton + Docker

**Files:**
- Create: `ndvi-service/requirements.txt`
- Create: `ndvi-service/models.py`
- Create: `ndvi-service/gee_client.py` (stub — `init_gee` + `compute_ndvi` signatures only)
- Create: `ndvi-service/main.py`
- Create: `ndvi-service/Dockerfile`
- Create: `ndvi-service/tests/__init__.py`
- Create: `ndvi-service/tests/conftest.py`
- Create: `ndvi-service/tests/test_main.py`

**Interfaces:**
- Produces: `POST /ndvi` (body: `NdviRequest`) → `NdviResponse`; `GET /health` → `{"status": "ok"}`

- [ ] **Step 1: Write `ndvi-service/requirements.txt`**

```
fastapi==0.115.0
uvicorn==0.30.6
earthengine-api==0.1.415
pydantic==2.9.0
pytest==8.3.3
httpx==0.27.2
```

- [ ] **Step 2: Write `ndvi-service/models.py`**

```python
from pydantic import BaseModel
from typing import Optional

class NdviRequest(BaseModel):
    polygon: dict
    season_start_month: int
    season_end_month: int
    year_start: int = 2022
    year_end: int

class NdviYearlyPoint(BaseModel):
    year: int
    ndvi: Optional[float] = None
    valid_pixel_pct: Optional[float] = None
    observation_date: str

class NdviResponse(BaseModel):
    yearly: list[Optional[NdviYearlyPoint]]
    current_ndvi: float
    baseline_ndvi: float
    change_pct: float
    trend: str
    condition: str
    last_observation: str
    resolution_m: int
    source: str
```

- [ ] **Step 3: Write `ndvi-service/gee_client.py` stub**

```python
import ee
import json
import os
import math
import calendar
from typing import Optional


def init_gee():
    key_path = os.environ.get("GEE_SERVICE_ACCOUNT_KEY", "/secrets/gee-sa-key.json")
    with open(key_path) as f:
        key_data = json.load(f)
    credentials = ee.ServiceAccountCredentials(
        email=key_data["client_email"],
        key_data=json.dumps(key_data)
    )
    ee.Initialize(credentials)


def compute_ndvi(
    polygon_geojson: dict,
    season_start_month: int,
    season_end_month: int,
    year_start: int,
    year_end: int,
) -> dict:
    # Implemented in Task 2
    raise NotImplementedError
```

- [ ] **Step 4: Write `ndvi-service/main.py`**

```python
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
import gee_client
from models import NdviRequest, NdviResponse


@asynccontextmanager
async def lifespan(app: FastAPI):
    gee_client.init_gee()
    yield


app = FastAPI(lifespan=lifespan)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/ndvi", response_model=NdviResponse)
def compute_ndvi_endpoint(req: NdviRequest):
    try:
        result = gee_client.compute_ndvi(
            req.polygon,
            req.season_start_month,
            req.season_end_month,
            req.year_start,
            req.year_end,
        )
        return result
    except NotImplementedError:
        raise HTTPException(status_code=501, detail="GEE computation not yet implemented")
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
```

- [ ] **Step 5: Write `ndvi-service/Dockerfile`**

```dockerfile
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8001

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]
```

- [ ] **Step 6: Write `ndvi-service/tests/conftest.py`**

```python
import pytest
from unittest.mock import patch


@pytest.fixture(autouse=True)
def no_gee_init():
    """Prevent actual GEE initialization in all tests."""
    with patch("gee_client.init_gee"):
        yield
```

- [ ] **Step 7: Write the failing test**

```python
# ndvi-service/tests/test_main.py
import pytest
from fastapi.testclient import TestClient


@pytest.fixture
def client(no_gee_init):
    from main import app
    return TestClient(app)


def test_health_returns_ok(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


def test_ndvi_endpoint_returns_501_before_gee_implemented(client):
    payload = {
        "polygon": {"type": "Polygon", "coordinates": [[[77.0, 20.0], [77.1, 20.0], [77.1, 20.1], [77.0, 20.1], [77.0, 20.0]]]},
        "season_start_month": 10,
        "season_end_month": 11,
        "year_start": 2022,
        "year_end": 2024,
    }
    resp = client.post("/ndvi", json=payload)
    assert resp.status_code == 501
```

- [ ] **Step 8: Run tests — expect PASS (health) and PASS (501 stub)**

```bash
cd ndvi-service && pip install -r requirements.txt && pytest tests/test_main.py -v
```

Expected: 2 tests pass.

- [ ] **Step 9: Commit**

```bash
git add ndvi-service/
git commit -m "feat: scaffold ndvi-service FastAPI app with health endpoint and stub"
```

---

### Task 2: ndvi-service — GEE computation + derive_metrics

**Files:**
- Modify: `ndvi-service/gee_client.py` (implement `compute_ndvi`, `derive_metrics`, helpers)
- Create: `ndvi-service/tests/test_gee_client.py`

**Interfaces:**
- Consumes: stub signatures from Task 1
- Produces: `derive_metrics(yearly: list) -> dict` (pure function, tested without GEE)

- [ ] **Step 1: Write the failing tests for `derive_metrics`**

```python
# ndvi-service/tests/test_gee_client.py
import pytest
from gee_client import derive_metrics


def test_improving_trend():
    yearly = [
        {"year": 2022, "ndvi": 0.61},
        {"year": 2023, "ndvi": 0.64},
        {"year": 2024, "ndvi": 0.66},
        {"year": 2025, "ndvi": 0.69},
        {"year": 2026, "ndvi": 0.72},
    ]
    result = derive_metrics(yearly)
    assert result["trend"] == "improving"
    assert abs(result["baseline_ndvi"] - (0.61 + 0.64 + 0.66) / 3) < 0.01
    assert result["current_ndvi"] == 0.72
    assert result["condition"] == "good"
    assert result["change_pct"] > 0


def test_degrading_trend():
    yearly = [
        {"year": 2022, "ndvi": 0.72},
        {"year": 2023, "ndvi": 0.68},
        {"year": 2024, "ndvi": 0.63},
    ]
    result = derive_metrics(yearly)
    assert result["trend"] == "degrading"


def test_null_year_excluded_from_trend():
    yearly = [
        {"year": 2022, "ndvi": 0.61},
        None,
        {"year": 2024, "ndvi": 0.64},
    ]
    result = derive_metrics(yearly)
    assert result["current_ndvi"] == 0.64
    assert result["trend"] in ("improving", "stable", "degrading")


def test_condition_fair():
    yearly = [{"year": 2022, "ndvi": 0.45}, {"year": 2023, "ndvi": 0.48}]
    assert derive_metrics(yearly)["condition"] == "fair"


def test_condition_poor():
    yearly = [{"year": 2022, "ndvi": 0.20}, {"year": 2023, "ndvi": 0.22}]
    assert derive_metrics(yearly)["condition"] == "poor"


def test_all_null_years_returns_safe_defaults():
    result = derive_metrics([None, None, None])
    assert result["trend"] == "stable"
    assert result["condition"] == "poor"
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd ndvi-service && pytest tests/test_gee_client.py -v
```

Expected: all 6 fail with `NotImplementedError` or `NameError`.

- [ ] **Step 3: Implement `derive_metrics` and full `gee_client.py`**

Replace `ndvi-service/gee_client.py` with:

```python
import ee
import json
import os
import calendar
from typing import Optional


def init_gee():
    key_path = os.environ.get("GEE_SERVICE_ACCOUNT_KEY", "/secrets/gee-sa-key.json")
    with open(key_path) as f:
        key_data = json.load(f)
    credentials = ee.ServiceAccountCredentials(
        email=key_data["client_email"],
        key_data=json.dumps(key_data)
    )
    ee.Initialize(credentials)


def _mask_clouds(image):
    qa = image.select("QA60")
    mask = (
        qa.bitwiseAnd(1 << 10).eq(0)
        .And(qa.bitwiseAnd(1 << 11).eq(0))
    )
    return image.updateMask(mask)


def _add_ndvi(image):
    return image.addBands(
        image.normalizedDifference(["B8", "B4"]).rename("NDVI")
    )


def _compute_year(
    polygon_coords: list,
    year: int,
    start_month: int,
    end_month: int,
) -> Optional[dict]:
    geometry = ee.Geometry.Polygon(polygon_coords)
    last_day = calendar.monthrange(year, end_month)[1]
    start = f"{year}-{start_month:02d}-01"
    end = f"{year}-{end_month:02d}-{last_day:02d}"

    collection = (
        ee.ImageCollection("COPERNICUS/S2_SR_HARMONIZED")
        .filterBounds(geometry)
        .filterDate(start, end)
        .map(_mask_clouds)
        .map(_add_ndvi)
    )

    if collection.size().getInfo() == 0:
        return None

    composite = collection.median()

    mean_stats = composite.select("NDVI").reduceRegion(
        reducer=ee.Reducer.mean(),
        geometry=geometry,
        scale=10,
        maxPixels=1e9,
    ).getInfo()

    ndvi_val = mean_stats.get("NDVI")
    if ndvi_val is None:
        return None

    coverage = composite.select("NDVI").mask().reduceRegion(
        reducer=ee.Reducer.mean(),
        geometry=geometry,
        scale=10,
        maxPixels=1e9,
    ).getInfo()
    valid_pct = (coverage.get("NDVI") or 0.0) * 100

    mid_month = (start_month + end_month) // 2
    observation_date = f"{year}-{mid_month:02d}-15"

    return {
        "year": year,
        "ndvi": round(ndvi_val, 4),
        "valid_pixel_pct": round(valid_pct, 1),
        "observation_date": observation_date,
    }


def derive_metrics(yearly: list) -> dict:
    """Pure function — no GEE needed. Input list may contain None for years with no valid data."""
    valid = [y for y in yearly if y is not None and y.get("ndvi") is not None]

    if not valid:
        return {
            "current_ndvi": 0.0,
            "baseline_ndvi": 0.0,
            "change_pct": 0.0,
            "trend": "stable",
            "condition": "poor",
        }

    baseline_pool = valid[:3]
    baseline = sum(y["ndvi"] for y in baseline_pool) / len(baseline_pool)
    current = valid[-1]["ndvi"]
    change_pct = ((current - baseline) / baseline * 100) if baseline else 0.0

    n = len(valid)
    xs = list(range(n))
    ys = [y["ndvi"] for y in valid]
    x_mean = sum(xs) / n
    y_mean = sum(ys) / n
    numer = sum((x - x_mean) * (y - y_mean) for x, y in zip(xs, ys))
    denom = sum((x - x_mean) ** 2 for x in xs)
    slope = numer / denom if denom else 0.0

    if slope > 0.01:
        trend = "improving"
    elif slope < -0.01:
        trend = "degrading"
    else:
        trend = "stable"

    if current >= 0.6:
        condition = "good"
    elif current >= 0.3:
        condition = "fair"
    else:
        condition = "poor"

    return {
        "current_ndvi": round(current, 4),
        "baseline_ndvi": round(baseline, 4),
        "change_pct": round(change_pct, 2),
        "trend": trend,
        "condition": condition,
    }


def compute_ndvi(
    polygon_geojson: dict,
    season_start_month: int,
    season_end_month: int,
    year_start: int,
    year_end: int,
) -> dict:
    coords = polygon_geojson["coordinates"]
    yearly_raw = [
        _compute_year(coords, year, season_start_month, season_end_month)
        for year in range(year_start, year_end + 1)
    ]

    metrics = derive_metrics(yearly_raw)
    valid_points = [y for y in yearly_raw if y is not None]
    last_obs = valid_points[-1]["observation_date"] if valid_points else f"{year_end}-10-15"

    return {
        "yearly": yearly_raw,
        **metrics,
        "last_observation": last_obs,
        "resolution_m": 10,
        "source": "Sentinel-2",
    }
```

- [ ] **Step 4: Run tests — expect all 6 to pass**

```bash
cd ndvi-service && pytest tests/test_gee_client.py -v
```

Expected: 6 tests pass.

- [ ] **Step 5: Run full test suite to ensure Task 1 tests still pass**

```bash
cd ndvi-service && pytest tests/ -v
```

Expected: 8 tests pass.

- [ ] **Step 6: Commit**

```bash
git add ndvi-service/gee_client.py ndvi-service/tests/test_gee_client.py
git commit -m "feat: implement GEE NDVI computation with cloud masking and derive_metrics"
```

---

### Task 3: Docker Compose + secrets setup

**Files:**
- Modify: `docker-compose.yml`
- Create: `secrets/.gitkeep`
- Modify: `.gitignore`
- Modify: `.env.example` (or create if missing)

**Interfaces:**
- Produces: `ndvi-service` reachable at `http://ndvi-service:8001` from `api` container

- [ ] **Step 1: Check whether `.env.example` and `.gitignore` exist**

```bash
ls /home/tntra/Desktop/projects/4-acres-tech-poc/.env.example 2>/dev/null || echo "missing"
cat /home/tntra/Desktop/projects/4-acres-tech-poc/.gitignore 2>/dev/null || echo "missing"
```

- [ ] **Step 2: Create `secrets/.gitkeep`**

```bash
mkdir -p secrets && touch secrets/.gitkeep
```

- [ ] **Step 3: Add `secrets/gee-sa-key.json` to `.gitignore`**

Add this line to `.gitignore` (create the file if it does not exist):
```
secrets/gee-sa-key.json
```

- [ ] **Step 4: Document GEE key requirement in `.env.example`**

Add to `.env.example` (create if missing):
```
# Google Earth Engine — place your GCP service account JSON key at:
# secrets/gee-sa-key.json
# The service account must have the Earth Engine role enabled.
# See: https://developers.google.com/earth-engine/guides/service_account
```

- [ ] **Step 5: Add `ndvi-service` to `docker-compose.yml` and update `api` depends_on**

In `docker-compose.yml`, add the new service before `volumes:` and update `api`:

```yaml
  ndvi-service:
    build: ./ndvi-service
    ports: ["8001:8001"]
    environment:
      GEE_SERVICE_ACCOUNT_KEY: /secrets/gee-sa-key.json
    volumes:
      - ./secrets/gee-sa-key.json:/secrets/gee-sa-key.json:ro
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:8001/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 15s
```

Update `api` service:
```yaml
  api:
    ...
    depends_on:
      db:
        condition: service_healthy
      ndvi-service:
        condition: service_healthy
```

- [ ] **Step 6: Verify ndvi-service builds and health passes (requires GEE key to be present; skip if key not yet available)**

```bash
docker compose build ndvi-service
docker compose up -d ndvi-service
docker compose ps ndvi-service
```

- [ ] **Step 7: Commit**

```bash
git add docker-compose.yml secrets/.gitkeep .gitignore .env.example
git commit -m "feat: add ndvi-service to docker-compose with GEE service account volume"
```

---

### Task 4: DB migration V4 + Patch entity season fields

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__add_season_window.sql`
- Modify: `backend/src/main/java/io/fouracres/model/Patch.java`

**Interfaces:**
- Produces: `patch.getSeasonStartMonth()` and `patch.getSeasonEndMonth()` used by Task 6 (NdviClient)

- [ ] **Step 1: Write `V4__add_season_window.sql`**

```sql
ALTER TABLE patches
    ADD COLUMN season_start_month INT NOT NULL DEFAULT 10,
    ADD COLUMN season_end_month   INT NOT NULL DEFAULT 11;
```

- [ ] **Step 2: Add fields to `Patch.java`**

Add these two fields after the existing `gfwGeostoreId` field:

```java
@Column(name = "season_start_month", nullable = false)
private int seasonStartMonth = 10;

@Column(name = "season_end_month", nullable = false)
private int seasonEndMonth = 11;
```

Add getters (follow the existing getter style in the file):

```java
public int getSeasonStartMonth() { return seasonStartMonth; }
public int getSeasonEndMonth()   { return seasonEndMonth; }
```

- [ ] **Step 3: Restart the api service to apply the Flyway migration**

```bash
docker compose restart api
docker compose logs api --tail=20
```

Expected log line: `Successfully applied 1 migration to schema "public"` (V4).

- [ ] **Step 4: Verify columns in pgAdmin or psql**

```bash
docker compose exec db psql -U fouracres -d fouracres -c "\d patches" | grep season
```

Expected: two rows showing `season_start_month` and `season_end_month`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V4__add_season_window.sql \
        backend/src/main/java/io/fouracres/model/Patch.java
git commit -m "feat: add season window columns to patches (V4 migration)"
```

---

### Task 5: VegetationData + NdviYearlyPoint DTOs

**Files:**
- Create: `backend/src/main/java/io/fouracres/dto/NdviYearlyPoint.java`
- Create: `backend/src/main/java/io/fouracres/dto/VegetationData.java`

**Interfaces:**
- Produces: `VegetationData` record consumed by Task 6 (NdviClient), Task 7 (VegetationService), Task 8 (PatchController)

- [ ] **Step 1: Write `NdviYearlyPoint.java`**

```java
package io.fouracres.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NdviYearlyPoint(
        int year,
        Double ndvi,
        @JsonProperty("valid_pixel_pct") Double validPixelPct,
        @JsonProperty("observation_date") String observationDate
) {}
```

- [ ] **Step 2: Write `VegetationData.java`**

```java
package io.fouracres.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record VegetationData(
        List<NdviYearlyPoint> yearly,
        @JsonProperty("current_ndvi")    double currentNdvi,
        @JsonProperty("baseline_ndvi")   double baselineNdvi,
        @JsonProperty("change_pct")      double changePct,
        String trend,
        String condition,
        @JsonProperty("last_observation") String lastObservation,
        @JsonProperty("resolution_m")    int resolutionM,
        String source
) {}
```

- [ ] **Step 3: Verify compilation**

```bash
docker run --rm -v "$(pwd)/backend:/app" -v ~/.m2:/root/.m2 -w /app \
  maven:3.9-eclipse-temurin-21-alpine mvn compile -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/io/fouracres/dto/NdviYearlyPoint.java \
        backend/src/main/java/io/fouracres/dto/VegetationData.java
git commit -m "feat: add VegetationData and NdviYearlyPoint DTOs"
```

---

### Task 6: NdviClient

**Files:**
- Create: `backend/src/main/java/io/fouracres/client/NdviClient.java`
- Create: `backend/src/test/java/io/fouracres/client/NdviClientTest.java`

**Interfaces:**
- Consumes: `Patch` (Task 4 fields), `VegetationData` + `NdviYearlyPoint` (Task 5)
- Produces: `NdviClient.fetch(Patch patch)` → `VegetationData | null` consumed by Task 7

- [ ] **Step 1: Write the failing tests**

```java
// backend/src/test/java/io/fouracres/client/NdviClientTest.java
package io.fouracres.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.dto.VegetationData;
import io.fouracres.model.Patch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NdviClientTest {

    @Mock HttpClient httpClient;
    @Mock HttpResponse<String> httpResponse;

    NdviClient client;

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    @BeforeEach
    void setUp() {
        client = new NdviClient(httpClient, "http://localhost:8001");
    }

    private Patch patchWithBoundary() {
        Coordinate[] coords = {
            new Coordinate(77.0, 20.0), new Coordinate(77.1, 20.0),
            new Coordinate(77.1, 20.1), new Coordinate(77.0, 20.1),
            new Coordinate(77.0, 20.0)
        };
        Polygon polygon = GF.createPolygon(coords);
        Patch patch = new Patch();
        patch.setBoundary(polygon);
        return patch;
    }

    @Test
    void fetch_returnsVegetationData_onSuccess() throws Exception {
        String json = """
            {"yearly":[{"year":2022,"ndvi":0.61,"valid_pixel_pct":78.3,"observation_date":"2022-10-15"}],
             "current_ndvi":0.61,"baseline_ndvi":0.61,"change_pct":0.0,"trend":"stable",
             "condition":"good","last_observation":"2022-10-15","resolution_m":10,"source":"Sentinel-2"}
            """;
        when(httpClient.send(any(), any())).thenReturn(httpResponse);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(json);

        VegetationData result = client.fetch(patchWithBoundary());

        assertThat(result).isNotNull();
        assertThat(result.currentNdvi()).isEqualTo(0.61);
        assertThat(result.trend()).isEqualTo("stable");
        assertThat(result.yearly()).hasSize(1);
    }

    @Test
    void fetch_returnsNull_onIOException() throws Exception {
        when(httpClient.send(any(), any())).thenThrow(new IOException("Connection refused"));

        VegetationData result = client.fetch(patchWithBoundary());

        assertThat(result).isNull();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
docker run --rm -v "$(pwd)/backend:/app" -v ~/.m2:/root/.m2 -w /app \
  maven:3.9-eclipse-temurin-21-alpine mvn test -pl . -Dtest=NdviClientTest -q 2>&1 | tail -5
```

Expected: compilation error — `NdviClient` not found.

- [ ] **Step 3: Implement `NdviClient.java`**

```java
package io.fouracres.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.dto.VegetationData;
import io.fouracres.model.Patch;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Year;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Component
public class NdviClient {

    private final HttpClient httpClient;
    private final String serviceUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public NdviClient(HttpClient httpClient,
                      @Value("${app.ndvi.service-url}") String serviceUrl) {
        this.httpClient = httpClient;
        this.serviceUrl = serviceUrl;
    }

    public VegetationData fetch(Patch patch) {
        try {
            List<List<Double>> ring = Arrays.stream(patch.getBoundary().getCoordinates())
                    .map(c -> List.of(c.x, c.y))
                    .toList();
            Map<String, Object> polygon = Map.of(
                    "type", "Polygon",
                    "coordinates", List.of(ring)
            );
            Map<String, Object> body = Map.of(
                    "polygon", polygon,
                    "season_start_month", patch.getSeasonStartMonth(),
                    "season_end_month", patch.getSeasonEndMonth(),
                    "year_start", 2022,
                    "year_end", Year.now().getValue()
            );

            var request = HttpRequest.newBuilder(URI.create(serviceUrl + "/ndvi"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return mapper.readValue(response.body(), VegetationData.class);
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 4: Run tests — expect 2 to pass**

```bash
docker run --rm -v "$(pwd)/backend:/app" -v ~/.m2:/root/.m2 -w /app \
  maven:3.9-eclipse-temurin-21-alpine mvn test -Dtest=NdviClientTest -q
```

Expected: BUILD SUCCESS, 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/fouracres/client/NdviClient.java \
        backend/src/test/java/io/fouracres/client/NdviClientTest.java
git commit -m "feat: add NdviClient posting to ndvi-service"
```

---

### Task 7: VegetationService

**Files:**
- Create: `backend/src/main/java/io/fouracres/service/VegetationService.java`
- Create: `backend/src/test/java/io/fouracres/service/VegetationServiceTest.java`

**Interfaces:**
- Consumes: `NdviClient.fetch(Patch)` (Task 6), `InsightsCacheRepository`, `PatchRepository`, `VegetationData` (Task 5)
- Produces: `VegetationService.getVegetation(UUID patchId)` → `VegetationData | null` consumed by Task 8

- [ ] **Step 1: Write the failing tests**

```java
// backend/src/test/java/io/fouracres/service/VegetationServiceTest.java
package io.fouracres.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.client.NdviClient;
import io.fouracres.dto.VegetationData;
import io.fouracres.model.InsightsCache;
import io.fouracres.model.Patch;
import io.fouracres.repository.InsightsCacheRepository;
import io.fouracres.repository.PatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.Coordinate;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VegetationServiceTest {

    @Mock PatchRepository patchRepository;
    @Mock InsightsCacheRepository cacheRepository;
    @Mock NdviClient ndviClient;

    VegetationService service;

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    @BeforeEach
    void setUp() {
        service = new VegetationService(patchRepository, cacheRepository, ndviClient, new ObjectMapper());
    }

    private Patch mockPatch(UUID id) {
        Patch patch = new Patch();
        patch.setId(id);
        var coords = new Coordinate[]{
            new Coordinate(77.0, 20.0), new Coordinate(77.1, 20.0),
            new Coordinate(77.1, 20.1), new Coordinate(77.0, 20.1),
            new Coordinate(77.0, 20.0)
        };
        patch.setBoundary(GF.createPolygon(coords));
        return patch;
    }

    @Test
    void getVegetation_returnsCachedData_withoutCallingNdviClient() throws Exception {
        UUID id = UUID.randomUUID();
        var vegData = new VegetationData(List.of(), 0.72, 0.64, 12.5, "improving", "good",
                "2026-10-15", 10, "Sentinel-2");
        String payload = new ObjectMapper().writeValueAsString(vegData);

        var cached = new InsightsCache(id, "VEGETATION", payload, Instant.now().plusSeconds(3600));
        when(cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(eq(id), eq("VEGETATION"), any()))
                .thenReturn(Optional.of(cached));

        VegetationData result = service.getVegetation(id);

        assertThat(result).isNotNull();
        assertThat(result.currentNdvi()).isEqualTo(0.72);
        verifyNoInteractions(ndviClient);
    }

    @Test
    void getVegetation_callsNdviClient_onCacheMiss() {
        UUID id = UUID.randomUUID();
        Patch patch = mockPatch(id);
        var vegData = new VegetationData(List.of(), 0.61, 0.61, 0.0, "stable", "good",
                "2022-10-15", 10, "Sentinel-2");

        when(cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(eq(id), eq("VEGETATION"), any()))
                .thenReturn(Optional.empty());
        when(patchRepository.findById(id)).thenReturn(Optional.of(patch));
        when(ndviClient.fetch(patch)).thenReturn(vegData);

        VegetationData result = service.getVegetation(id);

        assertThat(result).isNotNull();
        assertThat(result.trend()).isEqualTo("stable");
        verify(cacheRepository).save(any(InsightsCache.class));
    }

    @Test
    void getVegetation_throwsNoSuchElement_whenPatchNotFound() {
        UUID id = UUID.randomUUID();
        when(cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(patchRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getVegetation(id))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
docker run --rm -v "$(pwd)/backend:/app" -v ~/.m2:/root/.m2 -w /app \
  maven:3.9-eclipse-temurin-21-alpine mvn test -Dtest=VegetationServiceTest -q 2>&1 | tail -5
```

Expected: compilation error — `VegetationService` not found.

- [ ] **Step 3: Implement `VegetationService.java`**

```java
package io.fouracres.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.client.NdviClient;
import io.fouracres.dto.VegetationData;
import io.fouracres.model.InsightsCache;
import io.fouracres.repository.InsightsCacheRepository;
import io.fouracres.repository.PatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@Transactional
public class VegetationService {

    private final PatchRepository patchRepository;
    private final InsightsCacheRepository cacheRepository;
    private final NdviClient ndviClient;
    private final ObjectMapper mapper;

    public VegetationService(PatchRepository patchRepository,
                              InsightsCacheRepository cacheRepository,
                              NdviClient ndviClient,
                              ObjectMapper mapper) {
        this.patchRepository = patchRepository;
        this.cacheRepository = cacheRepository;
        this.ndviClient = ndviClient;
        this.mapper = mapper;
    }

    public VegetationData getVegetation(UUID patchId) {
        var now = Instant.now();
        var cached = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "VEGETATION", now);
        if (cached.isPresent()) {
            try {
                return mapper.readValue(cached.get().getPayload(), VegetationData.class);
            } catch (Exception ignored) {}
        }

        var patch = patchRepository.findById(patchId)
                .orElseThrow(() -> new NoSuchElementException("Patch not found: " + patchId));

        VegetationData data = ndviClient.fetch(patch);
        if (data == null) {
            return null;
        }

        try {
            var entry = new InsightsCache(
                    patchId, "VEGETATION",
                    mapper.writeValueAsString(data),
                    now.plus(24, ChronoUnit.HOURS)
            );
            cacheRepository.save(entry);
        } catch (Exception ignored) {}

        return data;
    }
}
```

- [ ] **Step 4: Run tests — expect 3 to pass**

```bash
docker run --rm -v "$(pwd)/backend:/app" -v ~/.m2:/root/.m2 -w /app \
  maven:3.9-eclipse-temurin-21-alpine mvn test -Dtest=VegetationServiceTest -q
```

Expected: BUILD SUCCESS, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/fouracres/service/VegetationService.java \
        backend/src/test/java/io/fouracres/service/VegetationServiceTest.java
git commit -m "feat: add VegetationService with insights_cache integration"
```

---

### Task 8: PatchController endpoint + application.yml

**Files:**
- Modify: `backend/src/main/java/io/fouracres/controller/PatchController.java`
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**
- Consumes: `VegetationService.getVegetation(UUID)` (Task 7)
- Produces: `GET /api/patches/{id}/vegetation` → `200 VegetationData` | `404` | `503`

- [ ] **Step 1: Add `app.ndvi.service-url` to `application.yml`**

Under the `app:` block, add:
```yaml
app:
  ndvi:
    service-url: ${NDVI_SERVICE_URL:http://ndvi-service:8001}
```

- [ ] **Step 2: Add `VegetationService` injection and new endpoint to `PatchController.java`**

Add field:
```java
private final VegetationService vegetationService;
```

Update constructor to include `VegetationService vegetationService` parameter and assign it.

Add endpoint after the existing `getInsights` method:
```java
@GetMapping("/{id}/vegetation")
public ResponseEntity<?> getVegetation(@PathVariable UUID id) {
    try {
        VegetationData data = vegetationService.getVegetation(id);
        if (data == null) {
            return ResponseEntity.status(503).body("NDVI service unavailable");
        }
        return ResponseEntity.ok(data);
    } catch (NoSuchElementException e) {
        return ResponseEntity.notFound().build();
    }
}
```

Add the missing import at the top of the file:
```java
import io.fouracres.dto.VegetationData;
import io.fouracres.service.VegetationService;
```

- [ ] **Step 3: Run the full backend test suite**

```bash
docker run --rm -v "$(pwd)/backend:/app" -v ~/.m2:/root/.m2 -w /app \
  maven:3.9-eclipse-temurin-21-alpine mvn test -q
```

Expected: all existing tests + NdviClientTest + VegetationServiceTest pass (≥ 14 tests).

- [ ] **Step 4: Rebuild and verify the endpoint responds**

```bash
docker compose up --build -d api
sleep 10
PATCH_ID=$(curl -s http://localhost:8080/api/patches | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")
curl -s http://localhost:8080/api/patches/$PATCH_ID/vegetation
```

Expected: either real NDVI JSON (if GEE key is in `secrets/`) or `503` (no key yet — that's fine for this task).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/fouracres/controller/PatchController.java \
        backend/src/main/resources/application.yml
git commit -m "feat: expose GET /api/patches/{id}/vegetation endpoint"
```

---

### Task 9: Frontend — types, api, queries

**Files:**
- Modify: `frontend/app/lib/types.ts`
- Modify: `frontend/app/lib/api.ts`
- Modify: `frontend/app/lib/queries.ts`

**Interfaces:**
- Produces: `useVegetation(patchId)` hook consumed by Task 11 (page)

- [ ] **Step 1: Add types to `frontend/app/lib/types.ts`**

Add at the end of the file:

```typescript
export interface NdviYearlyPoint {
  year: number
  ndvi: number | null
  validPixelPct: number | null
  observationDate: string
}

export interface VegetationData {
  yearly: (NdviYearlyPoint | null)[]
  currentNdvi: number
  baselineNdvi: number
  changePct: number
  trend: 'improving' | 'stable' | 'degrading'
  condition: 'good' | 'fair' | 'poor'
  lastObservation: string
  resolutionM: number
  source: string
}
```

- [ ] **Step 2: Add `fetchVegetation` to `frontend/app/lib/api.ts`**

Add after the existing fetch functions (follow the same `BASE` constant and error pattern already in the file):

```typescript
export async function fetchVegetation(patchId: string): Promise<VegetationData> {
  const res = await fetch(`${BASE}/api/patches/${patchId}/vegetation`)
  if (!res.ok) throw new Error(`fetchVegetation failed: ${res.status}`)
  return res.json()
}
```

Add the `VegetationData` import at the top of `api.ts` where other types are imported.

- [ ] **Step 3: Add `useVegetation` hook to `frontend/app/lib/queries.ts`**

Add after the existing hooks (follow the same `useQuery` pattern):

```typescript
export function useVegetation(patchId: string | null) {
  return useQuery({
    queryKey: ['vegetation', patchId],
    queryFn: () => fetchVegetation(patchId!),
    enabled: patchId != null,
    staleTime: 1000 * 60 * 60 * 24, // 24 hours — matches server cache
    retry: false,
  })
}
```

Add the `fetchVegetation` import and `VegetationData` type where needed at the top.

- [ ] **Step 4: Verify TypeScript compiles**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add frontend/app/lib/types.ts frontend/app/lib/api.ts frontend/app/lib/queries.ts
git commit -m "feat: add VegetationData types, fetchVegetation, and useVegetation hook"
```

---

### Task 10: VegetationCard component

**Files:**
- Create: `frontend/app/components/VegetationCard.tsx`

**Interfaces:**
- Consumes: `VegetationData`, `NdviYearlyPoint` from `../lib/types`
- Produces: `<VegetationCard data={VegetationData | null} className?: string />` consumed by Task 11

- [ ] **Step 1: Write `VegetationCard.tsx`**

```tsx
'use client'

import type { VegetationData } from '../lib/types'

const GLASS = 'bg-black/60 backdrop-blur-md border border-white/10 rounded-xl p-4 text-white'

const TREND_COLOR = {
  improving: 'text-emerald-400',
  stable: 'text-amber-400',
  degrading: 'text-red-400',
} as const

const TREND_ARROW = {
  improving: '↑',
  stable: '→',
  degrading: '↓',
} as const

const CONDITION_COLOR = {
  good: 'text-emerald-400',
  fair: 'text-amber-400',
  poor: 'text-red-400',
} as const

interface Props {
  data: VegetationData | null
  className?: string
}

export function VegetationCard({ data, className }: Props) {
  return (
    <div className={`${GLASS} ${className ?? ''}`}>
      <p className="text-xs font-semibold text-emerald-400 mb-3">🌿 VEGETATION</p>
      {data ? (
        <div className="space-y-2">
          <Row label="Current condition">
            <span className={CONDITION_COLOR[data.condition]}>
              {capitalize(data.condition)}
            </span>
          </Row>
          <Row label="NDVI" value={data.currentNdvi.toFixed(2)} />
          <Row label="Historical baseline" value={data.baselineNdvi.toFixed(2)} />
          <Row label="Change">
            <span className={data.changePct >= 0 ? 'text-emerald-400' : 'text-red-400'}>
              {data.changePct >= 0 ? '+' : ''}{data.changePct.toFixed(1)}%
            </span>
          </Row>
          <Row label="Trend">
            <span className={TREND_COLOR[data.trend]}>
              {TREND_ARROW[data.trend]} {capitalize(data.trend)}
            </span>
          </Row>
          <Row label="Last observation" value={formatDate(data.lastObservation)} />
          <Row label="Resolution" value={`${data.resolutionM}m`} />
          <Row label="Source" value={data.source} />
          {data.yearly.length >= 2 && <NdviSparkline data={data} />}
        </div>
      ) : (
        <div className="space-y-2">
          {Array.from({ length: 8 }).map((_, i) => (
            <div key={i} className="h-4 bg-white/10 rounded animate-pulse" />
          ))}
        </div>
      )}
    </div>
  )
}

function Row({
  label,
  value,
  children,
}: {
  label: string
  value?: string
  children?: React.ReactNode
}) {
  return (
    <div className="flex justify-between items-center text-sm">
      <span className="text-white/60">{label}</span>
      <span className="font-medium">{children ?? value}</span>
    </div>
  )
}

function NdviSparkline({ data }: { data: VegetationData }) {
  const W = 180
  const H = 32
  const points = data.yearly
  const validPoints = points.filter((p): p is NonNullable<typeof p> & { ndvi: number } =>
    p !== null && p.ndvi !== null
  )
  if (validPoints.length < 2) return null

  const minV = Math.min(...validPoints.map(p => p.ndvi))
  const maxV = Math.max(...validPoints.map(p => p.ndvi))
  const range = maxV - minV || 0.1

  const toX = (i: number) => (i / (points.length - 1)) * W
  const toY = (v: number) => H - ((v - minV) / range) * (H - 4) - 2

  const segments: string[][] = []
  let current: string[] = []
  points.forEach((p, i) => {
    if (p !== null && p.ndvi !== null) {
      current.push(`${toX(i).toFixed(1)},${toY(p.ndvi).toFixed(1)}`)
    } else if (current.length > 0) {
      segments.push(current)
      current = []
    }
  })
  if (current.length > 0) segments.push(current)

  const firstYear = points[0]?.year ?? ''
  const lastYear = points[points.length - 1]?.year ?? ''

  return (
    <div className="mt-2 pt-2 border-t border-white/10">
      <p className="text-xs text-white/40 mb-1">
        NDVI trend ({firstYear}–{lastYear})
      </p>
      <svg width={W} height={H} className="overflow-visible">
        {segments.map((seg, i) => (
          <polyline
            key={i}
            points={seg.join(' ')}
            fill="none"
            stroke="#34d399"
            strokeWidth="1.5"
            strokeLinejoin="round"
          />
        ))}
        {points.map((p, i) =>
          p !== null && p.ndvi !== null ? (
            <circle
              key={i}
              cx={toX(i)}
              cy={toY(p.ndvi)}
              r="2.5"
              fill="#34d399"
            />
          ) : null
        )}
      </svg>
    </div>
  )
}

function capitalize(s: string) {
  return s.charAt(0).toUpperCase() + s.slice(1)
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString('en-GB', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  })
}
```

- [ ] **Step 2: Verify TypeScript compiles**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add frontend/app/components/VegetationCard.tsx
git commit -m "feat: add VegetationCard with sparkline and loading skeleton"
```

---

### Task 11: Patch detail page integration

**Files:**
- Modify: `frontend/app/patch/[id]/page.tsx`

**Interfaces:**
- Consumes: `useVegetation(id)` (Task 9), `<VegetationCard />` (Task 10)

- [ ] **Step 1: Add imports to `page.tsx`**

At the top of `frontend/app/patch/[id]/page.tsx`, add:
```typescript
import { VegetationCard } from '../../components/VegetationCard'
import { useVegetation } from '../../lib/queries'
```

- [ ] **Step 2: Add `useVegetation` hook call inside the component**

Inside the component function, alongside the existing `useInsights` call, add:
```typescript
const { data: vegetation } = useVegetation(id)
```

Where `id` is the patch ID already used by `useInsights`.

- [ ] **Step 3: Add `VegetationCard` to the layout**

In the top row of glass cards (alongside `GeographicContextCard`, `WeatherCard`, `SatelliteCard`), add:
```tsx
<VegetationCard data={vegetation ?? null} className="flex-1" />
```

- [ ] **Step 4: Verify TypeScript compiles**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

Expected: no errors.

- [ ] **Step 5: Start the dev server and visually verify the card**

```bash
docker compose up -d
```

Open `http://localhost:3000`, navigate to any patch detail page. Verify:
- VegetationCard renders in the top row
- Loading skeleton (animated pulses) shows while data is fetching
- With no GEE key: card stays in skeleton state (503 from backend → `useVegetation` retries disabled, no crash)
- With a valid GEE key: card shows NDVI, trend, sparkline

- [ ] **Step 6: Commit**

```bash
git add frontend/app/patch/\[id\]/page.tsx
git commit -m "feat: integrate VegetationCard into patch detail page (FA-13)"
```

---

## GEE Setup Checklist (required before real data flows)

After all tasks above are done:

1. Create a GCP project at [console.cloud.google.com](https://console.cloud.google.com)
2. Enable the **Earth Engine API** for the project
3. Register the project at [code.earthengine.google.com](https://code.earthengine.google.com) (one-time)
4. Create a service account with the **Earth Engine Resource Viewer** role
5. Download the JSON key → place at `secrets/gee-sa-key.json`
6. `docker compose up --build -d ndvi-service api`
7. Hit `GET /api/patches/{id}/vegetation` — expect real NDVI JSON within ~60 seconds
