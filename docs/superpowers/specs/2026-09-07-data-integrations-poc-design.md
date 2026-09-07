# Data Integrations POC — Design Spec

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Validate end-to-end integration of three new external data providers — Mapbox Geocoding (geographic context), WeatherAPI.com (weather & climate), and Sentinel-2 via Copernicus STAC API (earth observation) — into the 4Acres Earth patch insight system, proving that real data from each source can be fetched, cached, and displayed as UI cards on the `/patch/[id]` digital twin page.

**Architecture:** Extends the existing insights pipeline with no new tables and no new endpoints. Three new `@Component` client beans (`MapboxGeocodingClient`, `WeatherApiClient`, `SentinelClient`) follow the exact same pattern as the existing `GbifClient`, `SoilGridsClient`, and `GfwClient`. `InsightsService` is updated to fetch all six data layers in parallel using `CompletableFuture`, applying per-layer TTLs when writing to the `insights_cache` table. `PatchInsightsDto` is extended with three new fields. Three new glassmorphism cards appear on the `/patch/[id]` page above the existing biodiversity/soil/carbon strip.

**Tech Stack:** Spring Boot 3.3.4 · Java 21 · PostgreSQL 15 + PostGIS 3.4 · Flyway 10 · Next.js 14 App Router · TypeScript strict · Tailwind CSS · react-query v5 · Jackson · Java 11+ HttpClient

**Spec:** `docs/superpowers/specs/2026-09-07-data-integrations-poc-design.md` (this file)

## Global Constraints

- Java 21, Spring Boot 3.3.4, Next.js 14 App Router — no version changes
- All new clients must be `@Component` beans using the shared `HttpClient` bean injected from `HttpClientConfig`
- All new clients must return a non-null DTO even on API failure: catch all exceptions, log the error, return empty/zero-valued default DTO — never throw
- Per-layer cache TTL: `GEOCODING` = 30 days · `WEATHER` = 1 hour · `SATELLITE` = 7 days · existing layers keep 24 hours
- `MAPBOX_TOKEN` is shared: frontend already receives it via Next.js build arg; backend must also read it via `${MAPBOX_TOKEN}` in `application.properties` (the `api` docker service already inherits all `.env` vars via `env_file: .env`)
- New API key required: `WEATHERAPI_KEY` — add to `.env.example`; developer must register free account at weatherapi.com
- Sentinel-2 STAC catalog search requires no authentication
- TypeScript strict mode — 0 compilation errors
- No new database tables, no new REST endpoints — extend existing `PatchInsightsDto` and reuse `insights_cache`
- `insights_cache.layer` column is `VARCHAR(20)` with no CHECK constraint — new layer string values work without a migration
- All new frontend cards must match the existing glassmorphism style: `bg-black/50 backdrop-blur-sm border border-white/10 rounded-xl`

---

## Section 1: New DTOs

### GeographicContextData

File: `backend/src/main/java/io/fouracres/dto/GeographicContextData.java`

```java
package io.fouracres.dto;

public record GeographicContextData(
    String placeName,     // nearest named place (town, suburb, or feature)
    String neighborhood,  // null if not available
    String city,
    String region,        // state / province / county
    String country,
    String fullAddress    // full formatted place_name string from Mapbox
) {}
```

Default (failure) value: `new GeographicContextData("Unknown", null, "Unknown", "Unknown", "Unknown", "Unknown")`

### WeatherData

File: `backend/src/main/java/io/fouracres/dto/WeatherData.java`

```java
package io.fouracres.dto;

import java.util.List;

public record WeatherData(
    double tempC,
    String condition,
    String conditionIconUrl,  // absolute https: URL
    double windKph,
    int    humidity,
    double uvIndex,
    List<ForecastDay> forecast
) {
    public record ForecastDay(
        String date,        // "2026-09-07"
        double maxTempC,
        double minTempC,
        String condition
    ) {}
}
```

Default (failure) value: `new WeatherData(0, "Unavailable", "", 0, 0, 0, List.of())`

### SatelliteSceneData

File: `backend/src/main/java/io/fouracres/dto/SatelliteSceneData.java`

```java
package io.fouracres.dto;

public record SatelliteSceneData(
    boolean hasRecentScene,
    String  latestSceneDate,    // ISO 8601 date-time string, null when hasRecentScene=false
    double  cloudCoverPercent,  // 0.0 when hasRecentScene=false
    String  productType,        // "S2MSI2A" or "S2MSI1C", null when hasRecentScene=false
    String  thumbnailUrl        // quicklook URL, nullable even when hasRecentScene=true
) {}
```

Default (failure / no scene) value: `new SatelliteSceneData(false, null, 0.0, null, null)`

### Extended PatchInsightsDto

File: `backend/src/main/java/io/fouracres/dto/PatchInsightsDto.java` — **replace** the existing 3-field record:

```java
package io.fouracres.dto;

public record PatchInsightsDto(
    BiodiversityData       biodiversity,
    SoilData               soil,
    CarbonData             carbon,
    GeographicContextData  geographicContext,
    WeatherData            weather,
    SatelliteSceneData     satellite
) {}
```

---

## Section 2: MapboxGeocodingClient

File: `backend/src/main/java/io/fouracres/client/MapboxGeocodingClient.java`

### Endpoint

```
GET {app.mapbox.base-url}/geocoding/v5/mapbox.places/{longitude},{latitude}.json
    ?access_token={app.mapbox.access-token}
    &types=neighborhood,place,region,country
    &language=en
    &limit=1
```

Coordinates: `patch.getCenterLng()`, `patch.getCenterLat()` — in that order (lng first, Mapbox convention).

### Configuration keys

```properties
app.mapbox.base-url=https://api.mapbox.com
app.mapbox.access-token=${MAPBOX_TOKEN}
```

### Response structure

```json
{
  "features": [
    {
      "place_name": "Tulum, Quintana Roo, Mexico",
      "place_type": ["place"],
      "text": "Tulum",
      "context": [
        { "id": "region.123", "text": "Quintana Roo" },
        { "id": "country.456", "text": "Mexico" }
      ]
    }
  ]
}
```

### Parsing logic

1. If `features` is null or empty → return default `GeographicContextData`
2. `fullAddress` = `features[0].place_name`
3. `placeName` = `features[0].text`
4. Walk `context[]` array. For each entry inspect the `id` field:
   - starts with `"neighborhood."` → `neighborhood = entry.text`
   - starts with `"place."` → `city = entry.text`
   - starts with `"region."` → `region = entry.text`
   - starts with `"country."` → `country = entry.text`
5. Any field not found in context defaults to `"Unknown"`
6. If `city` is still `"Unknown"` and the top feature's `place_type[0]` is `"place"`, use `placeName` as `city`

### Cache

Layer: `"GEOCODING"`, TTL: 30 days

---

## Section 3: WeatherApiClient

File: `backend/src/main/java/io/fouracres/client/WeatherApiClient.java`

### Endpoint

```
GET {app.weatherapi.base-url}/v1/forecast.json
    ?key={app.weatherapi.api-key}
    &q={lat},{lng}
    &days=7
    &aqi=no
    &alerts=no
```

### Configuration keys

```properties
app.weatherapi.base-url=https://api.weatherapi.com
app.weatherapi.api-key=${WEATHERAPI_KEY}
```

### Response parsing

```
response.current.temp_c              → tempC
response.current.condition.text      → condition
response.current.condition.icon      → conditionIconUrl
                                       (prefix "https:" if the value starts with "//")
response.current.wind_kph            → windKph
response.current.humidity            → humidity (int)
response.current.uv                  → uvIndex

response.forecast.forecastday[]      → forecast (exactly 7 items):
  [i].date                           → ForecastDay.date
  [i].day.maxtemp_c                  → ForecastDay.maxTempC
  [i].day.mintemp_c                  → ForecastDay.minTempC
  [i].day.condition.text             → ForecastDay.condition
```

### Cache

Layer: `"WEATHER"`, TTL: 1 hour

---

## Section 4: SentinelClient

File: `backend/src/main/java/io/fouracres/client/SentinelClient.java`

### Endpoint

```
GET {app.sentinel.base-url}/stac/collections/SENTINEL-2/items
    ?bbox={lng-0.1},{lat-0.1},{lng+0.1},{lat+0.1}
    &datetime={iso30daysAgo}/{isoNow}
    &limit=5
    &sortby=-datetime
```

- `bbox` forms a ~0.2°×0.2° box (~22km²) around the patch centroid
- `datetime` range: `Instant.now().minus(30, ChronoUnit.DAYS)` to `Instant.now()`, both formatted as `{instant.toString()}` (ISO 8601 UTC, e.g. `2026-08-08T00:00:00Z/2026-09-07T10:00:00Z`)
- No `Authorization` header — public catalog access

### Configuration keys

```properties
app.sentinel.base-url=https://catalogue.dataspace.copernicus.eu
```

### Response parsing

```
features[]               → if null or empty → return default SatelliteSceneData(false,...)

features[0].properties.datetime        → latestSceneDate (string)
features[0].properties."eo:cloud_cover" → cloudCoverPercent (double)
features[0].properties."s2:product_type" → productType (string)
features[0].links[]     → find first entry where rel == "thumbnail" → thumbnailUrl
                           null if no thumbnail link present
hasRecentScene = true
```

Jackson note: property names containing `:` must be accessed via `JsonNode.get("eo:cloud_cover")` tree traversal, not `@JsonProperty` on a record.

### Cache

Layer: `"SATELLITE"`, TTL: 7 days

---

## Section 5: InsightsService Changes

File: `backend/src/main/java/io/fouracres/service/InsightsService.java`

### Per-layer TTL helper

Add private method:

```java
private Instant expiresAt(String layer) {
    return switch (layer) {
        case "WEATHER"   -> Instant.now().plus(1,  ChronoUnit.HOURS);
        case "SATELLITE" -> Instant.now().plus(7,  ChronoUnit.DAYS);
        case "GEOCODING" -> Instant.now().plus(30, ChronoUnit.DAYS);
        default          -> Instant.now().plus(24, ChronoUnit.HOURS);
    };
}
```

### Constructor

Add three new fields and constructor params: `MapboxGeocodingClient geocodingClient`, `WeatherApiClient weatherClient`, `SentinelClient sentinelClient`.

### Updated getInsights() logic

```java
public PatchInsightsDto getInsights(UUID patchId) {
    var now = Instant.now();

    // Check all 6 cache layers
    Optional<InsightsCache> bioCached       = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "BIODIVERSITY", now);
    Optional<InsightsCache> soilCached      = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "SOIL",         now);
    Optional<InsightsCache> carbonCached    = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "CARBON",       now);
    Optional<InsightsCache> geoCached       = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "GEOCODING",    now);
    Optional<InsightsCache> weatherCached   = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "WEATHER",      now);
    Optional<InsightsCache> satelliteCached = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "SATELLITE",    now);

    if (bioCached.isPresent() && soilCached.isPresent() && carbonCached.isPresent()
            && geoCached.isPresent() && weatherCached.isPresent() && satelliteCached.isPresent()) {
        return deserialize(bioCached.get(), soilCached.get(), carbonCached.get(),
                           geoCached.get(), weatherCached.get(), satelliteCached.get());
    }

    Patch patch = patchRepository.findById(patchId)
        .orElseThrow(() -> new NoSuchElementException("Patch not found: " + patchId));

    // Fetch all 6 in parallel
    var bioFuture       = CompletableFuture.supplyAsync(() -> gbifClient.fetch(patch));
    var soilFuture      = CompletableFuture.supplyAsync(() -> soilGridsClient.fetch(patch));
    var carbonFuture    = CompletableFuture.supplyAsync(() -> gfwClient.fetch(patch));
    var geoFuture       = CompletableFuture.supplyAsync(() -> geocodingClient.fetch(patch));
    var weatherFuture   = CompletableFuture.supplyAsync(() -> weatherClient.fetch(patch));
    var satelliteFuture = CompletableFuture.supplyAsync(() -> sentinelClient.fetch(patch));

    CompletableFuture.allOf(bioFuture, soilFuture, carbonFuture, geoFuture, weatherFuture, satelliteFuture).join();

    var bio       = bioFuture.join();
    var soil      = soilFuture.join();
    var carbon    = carbonFuture.join();
    var geo       = geoFuture.join();
    var weather   = weatherFuture.join();
    var satellite = satelliteFuture.join();

    saveCache(patchId, "BIODIVERSITY", bio,       expiresAt("BIODIVERSITY"));
    saveCache(patchId, "SOIL",         soil,      expiresAt("SOIL"));
    saveCache(patchId, "CARBON",       carbon,    expiresAt("CARBON"));
    saveCache(patchId, "GEOCODING",    geo,       expiresAt("GEOCODING"));
    saveCache(patchId, "WEATHER",      weather,   expiresAt("WEATHER"));
    saveCache(patchId, "SATELLITE",    satellite, expiresAt("SATELLITE"));

    return new PatchInsightsDto(bio, soil, carbon, geo, weather, satellite);
}
```

### Updated deserialize()

```java
private PatchInsightsDto deserialize(InsightsCache bio, InsightsCache soil, InsightsCache carbon,
                                      InsightsCache geo, InsightsCache weather, InsightsCache satellite) {
    try {
        return new PatchInsightsDto(
            mapper.readValue(bio.getPayload(),       BiodiversityData.class),
            mapper.readValue(soil.getPayload(),      SoilData.class),
            mapper.readValue(carbon.getPayload(),    CarbonData.class),
            mapper.readValue(geo.getPayload(),       GeographicContextData.class),
            mapper.readValue(weather.getPayload(),   WeatherData.class),
            mapper.readValue(satellite.getPayload(), SatelliteSceneData.class)
        );
    } catch (Exception e) {
        throw new RuntimeException("Cache deserialization failed", e);
    }
}
```

---

## Section 6: Frontend — Types

File: `frontend/app/lib/types.ts` — append to existing file:

```typescript
export interface GeographicContextData {
  placeName: string
  neighborhood: string | null
  city: string
  region: string
  country: string
  fullAddress: string
}

export interface ForecastDay {
  date: string        // "2026-09-07"
  maxTempC: number
  minTempC: number
  condition: string
}

export interface WeatherData {
  tempC: number
  condition: string
  conditionIconUrl: string
  windKph: number
  humidity: number
  uvIndex: number
  forecast: ForecastDay[]
}

export interface SatelliteSceneData {
  hasRecentScene: boolean
  latestSceneDate: string | null
  cloudCoverPercent: number
  productType: string | null
  thumbnailUrl: string | null
}
```

Also extend `PatchInsights` interface — replace the existing definition:

```typescript
export interface PatchInsights {
  biodiversity: BiodiversityData
  soil: SoilData
  carbon: CarbonData
  geographicContext: GeographicContextData | null
  weather: WeatherData | null
  satellite: SatelliteSceneData | null
}
```

---

## Section 7: Frontend — New Cards

All three cards go in `frontend/app/components/`.

Card base style (matches existing `PatchInfoCard`):
```
bg-black/50 backdrop-blur-sm border border-white/10 rounded-xl p-3
```

### GeographicContextCard.tsx

File: `frontend/app/components/GeographicContextCard.tsx`

Props: `{ insights: PatchInsights | undefined; className?: string }`

When `insights?.geographicContext` is null/undefined → show:
```
Label: "LOCATION"
Body:  "—" (em-dash, muted)
```

When data is present → show:
```
Label chip: "LOCATION"  (10px, uppercase, letter-spaced, slate-500)
Place name: insights.geographicContext.placeName  (white, 13px, font-medium)
Row:  city + ", " + region  (slate-400, 11px)
Row:  country  (slate-500, 11px)
```

### WeatherCard.tsx

File: `frontend/app/components/WeatherCard.tsx`

Props: `{ insights: PatchInsights | undefined; className?: string }`

When `insights?.weather` is null or `condition === "Unavailable"` → show:
```
Label: "WEATHER"
Body:  "—"
```

When data present → show:
```
Label chip:  "WEATHER"
Temp row:    "{tempC}°C" (white, 18px, font-bold) + condition text (slate-400, 11px)
Stats row:   💨 {windKph} km/h   💧 {humidity}%   ☀️ UV {uvIndex}
             (slate-500, 10px, spaced)
Forecast strip (7 items, flex row, overflow-x auto):
  each item:  day abbrev (Mon/Tue/...)  + ↑{maxTempC}° ↓{minTempC}°  (10px, slate-500)
```

Derive day abbreviation from `ForecastDay.date` using `new Date(date).toLocaleDateString('en', { weekday: 'short' })`.

### SatelliteCard.tsx

File: `frontend/app/components/SatelliteCard.tsx`

Props: `{ insights: PatchInsights | undefined; className?: string }`

When `insights?.satellite` is null → show:
```
Label: "SENTINEL-2"
Body:  "—"
```

When `hasRecentScene === false` → show:
```
Label chip:    "SENTINEL-2"
Body:          "No recent scene (30 days)"  (slate-500, 12px)
```

When `hasRecentScene === true` → show:
```
Label chip:    "SENTINEL-2"
Scene date:    formatted date from latestSceneDate (white, 13px)
               e.g. new Date(latestSceneDate).toLocaleDateString('en', { day:'numeric', month:'short', year:'numeric' })
Cloud cover:   badge — green if < 20%, amber if 20-60%, red if > 60%
               text: "{cloudCoverPercent.toFixed(0)}% cloud"
Product badge: "Analysis-Ready" if productType === "S2MSI2A", else "Raw (L1C)"
               (slate-300, 10px, bg-white/5 rounded px-1.5)
Thumbnail:     if thumbnailUrl is non-null, render <img src={thumbnailUrl} ... />
               width: 100%, max-height: 60px, object-cover, rounded, mt-1
               wrap in onError handler: hide img on load failure
```

---

## Section 8: Updated /patch/[id]/page.tsx Layout

File: `frontend/app/patch/[id]/page.tsx` — extend the bottom section:

Add imports for the three new card components. Replace the single bottom strip with two rows:

```tsx
{/* New integrations row */}
<div className="absolute bottom-36 left-4 right-4 z-10 flex gap-3">
  <GeographicContextCard insights={insights} className="flex-1" />
  <WeatherCard insights={insights} className="flex-1" />
  <SatelliteCard insights={insights} className="flex-1" />
</div>

{/* Existing metrics row */}
<div className="absolute bottom-6 left-4 right-4 z-10 flex gap-3">
  <PatchInfoCard type="biodiversity" insights={insights} className="flex-1" />
  <PatchInfoCard type="soil"         insights={insights} className="flex-1" />
  <PatchInfoCard type="carbon"       insights={insights} className="flex-1" />
</div>
```

`bottom-36` positions the new row 144px from the bottom, sitting directly above the existing strip (which occupies approximately bottom-6 to bottom-36).

---

## Section 9: Configuration Changes

### .env.example

Add after `MAPBOX_TOKEN` line:
```
WEATHERAPI_KEY=your-weatherapi-key-here
```

`GFW_API_KEY` and `MAPBOX_TOKEN` already present. No docker-compose.yml changes needed — `api` service inherits all `.env` vars via `env_file: .env`.

### backend/src/main/resources/application.properties

Add:
```properties
app.mapbox.base-url=https://api.mapbox.com
app.mapbox.access-token=${MAPBOX_TOKEN}
app.weatherapi.base-url=https://api.weatherapi.com
app.weatherapi.api-key=${WEATHERAPI_KEY}
app.sentinel.base-url=https://catalogue.dataspace.copernicus.eu
```

Existing properties (`app.gbif.base-url`, `app.soilgrids.base-url`, `app.gfw.base-url`, `app.gfw.api-key`) are unchanged.

---

## Section 10: Tests

### MapboxGeocodingClientTest

File: `backend/src/test/java/io/fouracres/client/MapboxGeocodingClientTest.java`

Test cases:
1. **Happy path** — mock HTTP response with `features[0]` containing `place_name`, `text`, and `context[]` with region and country entries. Assert `fullAddress`, `placeName`, `region`, `country` match.
2. **Empty features** — mock response with `"features": []`. Assert returns default `GeographicContextData` with all fields `"Unknown"` and `neighborhood` null.
3. **HTTP error** — mock HttpClient to throw `IOException`. Assert returns default `GeographicContextData`.

### WeatherApiClientTest

File: `backend/src/test/java/io/fouracres/client/WeatherApiClientTest.java`

Test cases:
1. **Happy path** — mock response with `current` and 7-item `forecastday`. Assert `tempC`, `condition`, `conditionIconUrl` starts with `"https:"`, `forecast` has 7 items, first item `date` and `maxTempC` correct.
2. **Icon URL prefixing** — response has `icon: "//cdn.weatherapi.com/..."`. Assert `conditionIconUrl` starts with `"https://cdn.weatherapi.com/"`.
3. **HTTP error** → returns `WeatherData(0, "Unavailable", "", 0, 0, 0, List.of())`.

### SentinelClientTest

File: `backend/src/test/java/io/fouracres/client/SentinelClientTest.java`

Test cases:
1. **Happy path** — mock response with one feature, `eo:cloud_cover: 15.3`, `s2:product_type: "S2MSI2A"`, `datetime: "2026-09-05T10:00:00Z"`, thumbnail link. Assert `hasRecentScene=true`, `cloudCoverPercent=15.3`, `productType="S2MSI2A"`, `thumbnailUrl` non-null.
2. **Empty features** — `"features": []`. Assert `hasRecentScene=false`, all other fields null/0.
3. **HTTP error** → returns default `SatelliteSceneData(false, null, 0.0, null, null)`.

### InsightsService — extend existing test

File: `backend/src/test/java/io/fouracres/service/InsightsServiceTest.java`

- Mock all 6 clients; mock `PatchRepository` and `InsightsCacheRepository` (no cache hit)
- Assert `getInsights(patchId)` returns a `PatchInsightsDto` where `geographicContext`, `weather`, `satellite` are all non-null
- Assert `cacheRepository.save()` is called 6 times (once per layer)

---

## Task Decomposition for Implementation Plan

Suggested task sequence:

| Task | Scope | Key files |
|------|-------|-----------|
| T1 | New DTOs + extend PatchInsightsDto | `GeographicContextData.java`, `WeatherData.java`, `SatelliteSceneData.java`, updated `PatchInsightsDto.java` |
| T2 | MapboxGeocodingClient + test | `MapboxGeocodingClient.java`, `MapboxGeocodingClientTest.java` |
| T3 | WeatherApiClient + test | `WeatherApiClient.java`, `WeatherApiClientTest.java` |
| T4 | SentinelClient + test | `SentinelClient.java`, `SentinelClientTest.java` |
| T5 | Extend InsightsService (6-way parallel, per-layer TTL, extended cache/deserialize) + extend InsightsServiceTest | `InsightsService.java`, `InsightsServiceTest.java` |
| T6 | Configuration (application.properties, .env.example) | config files only |
| T7 | Frontend types + GeographicContextCard | `types.ts`, `GeographicContextCard.tsx` |
| T8 | WeatherCard + SatelliteCard + wire into /patch/[id] page | `WeatherCard.tsx`, `SatelliteCard.tsx`, `app/patch/[id]/page.tsx` |
