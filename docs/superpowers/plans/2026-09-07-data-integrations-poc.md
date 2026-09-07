# Data Integrations POC — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Mapbox Geocoding, WeatherAPI.com, and Sentinel-2 STAC data integrations to the 4Acres Earth patch insight system, showing each as a new card on the `/patch/[id]` digital twin page.

**Architecture:** Three new Spring Boot `@Component` client beans slot into the existing `InsightsService` parallel-fetch pipeline alongside the three existing clients. `PatchInsightsDto` is extended from 3 to 6 fields. Three new frontend cards render in a second row above the existing biodiversity/soil/carbon strip. No new tables, no new endpoints, no structural migrations.

**Tech Stack:** Spring Boot 3.3.4 · Java 21 · Next.js 14 App Router · TypeScript strict · Tailwind CSS · react-query v5 · Jackson · Java 11+ HttpClient · Mockito · AssertJ

**Spec:** `docs/superpowers/specs/2026-09-07-data-integrations-poc-design.md`

## Global Constraints

- Java 21, Spring Boot 3.3.4, Next.js 14 App Router — no version changes
- All new clients are `@Component` beans using the shared `HttpClient` bean from `HttpClientConfig`
- All new clients catch ALL exceptions and return a non-null default DTO — never throw
- Per-layer TTL: `GEOCODING` = 30 days · `WEATHER` = 1 hour · `SATELLITE` = 7 days · existing layers keep 24 hours
- `MAPBOX_TOKEN` env var is already in `.env` — backend reads it as `${MAPBOX_TOKEN}` in `application.properties`
- New env var required: `WEATHERAPI_KEY` — add to `.env.example`; no Sentinel key needed
- TypeScript strict mode — 0 compilation errors
- No new database tables, no new REST endpoints
- `insights_cache.layer` is `VARCHAR(20)` with no CHECK constraint — new layer strings work without migration
- New cards match existing glassmorphism style: `bg-black/60 backdrop-blur-md border border-white/10 rounded-xl p-4 text-white`

---

### Task 1: New DTOs + Extend PatchInsightsDto

**Files:**
- Create: `backend/src/main/java/io/fouracres/dto/GeographicContextData.java`
- Create: `backend/src/main/java/io/fouracres/dto/WeatherData.java`
- Create: `backend/src/main/java/io/fouracres/dto/SatelliteSceneData.java`
- Modify: `backend/src/main/java/io/fouracres/dto/PatchInsightsDto.java`

**Interfaces:**
- Consumes: nothing from other tasks
- Produces: `GeographicContextData`, `WeatherData`, `WeatherData.ForecastDay`, `SatelliteSceneData` records — used by Tasks 2, 3, 4, 5, 7, 8

- [ ] **Step 1: Create GeographicContextData**

```java
// backend/src/main/java/io/fouracres/dto/GeographicContextData.java
package io.fouracres.dto;

public record GeographicContextData(
    String placeName,
    String neighborhood,  // null if not present in Mapbox context
    String city,
    String region,
    String country,
    String fullAddress
) {}
```

- [ ] **Step 2: Create WeatherData**

```java
// backend/src/main/java/io/fouracres/dto/WeatherData.java
package io.fouracres.dto;

import java.util.List;

public record WeatherData(
    double tempC,
    String condition,
    String conditionIconUrl,
    double windKph,
    int    humidity,
    double uvIndex,
    List<ForecastDay> forecast
) {
    public record ForecastDay(
        String date,
        double maxTempC,
        double minTempC,
        String condition
    ) {}
}
```

- [ ] **Step 3: Create SatelliteSceneData**

```java
// backend/src/main/java/io/fouracres/dto/SatelliteSceneData.java
package io.fouracres.dto;

public record SatelliteSceneData(
    boolean hasRecentScene,
    String  latestSceneDate,    // null when hasRecentScene=false
    double  cloudCoverPercent,
    String  productType,        // null when hasRecentScene=false
    String  thumbnailUrl        // nullable even when hasRecentScene=true
) {}
```

- [ ] **Step 4: Replace PatchInsightsDto**

The current file has 3 fields. Replace it entirely:

```java
// backend/src/main/java/io/fouracres/dto/PatchInsightsDto.java
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

- [ ] **Step 5: Verify compilation**

```bash
cd backend && ./mvnw compile -q
```

Expected: BUILD SUCCESS. If it fails, check that all four DTO files are in the same package `io.fouracres.dto`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/io/fouracres/dto/
git commit -m "feat: add GeographicContextData, WeatherData, SatelliteSceneData DTOs; extend PatchInsightsDto to 6 fields"
```

---

### Task 2: MapboxGeocodingClient + Test

**Files:**
- Create: `backend/src/main/java/io/fouracres/client/MapboxGeocodingClient.java`
- Create: `backend/src/test/java/io/fouracres/client/MapboxGeocodingClientTest.java`

**Interfaces:**
- Consumes: `GeographicContextData` from Task 1 · `Patch` entity (existing)
- Produces: `MapboxGeocodingClient.fetch(Patch) → GeographicContextData` — used by Task 5

**How the Mapbox Geocoding API works:**
```
GET https://api.mapbox.com/geocoding/v5/mapbox.places/{lng},{lat}.json
    ?access_token=TOKEN&types=neighborhood,place,region,country&language=en&limit=1

Response:
{
  "features": [{
    "place_name": "Tulum, Quintana Roo, Mexico",
    "place_type": ["place"],
    "text": "Tulum",
    "context": [
      {"id": "region.123", "text": "Quintana Roo"},
      {"id": "country.456", "text": "Mexico"}
    ]
  }]
}
```

Note: `lng` comes before `lat` in the URL path — Mapbox convention (opposite of most APIs).

`Patch.getCenterLat()` and `Patch.getCenterLng()` return `BigDecimal`. Use `.toPlainString()` to avoid scientific notation.

- [ ] **Step 1: Write the failing test**

```java
// backend/src/test/java/io/fouracres/client/MapboxGeocodingClientTest.java
package io.fouracres.client;

import io.fouracres.dto.GeographicContextData;
import io.fouracres.model.Patch;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class MapboxGeocodingClientTest {

    private static final String FULL_RESPONSE = """
        {
          "features": [{
            "place_name": "Tulum, Quintana Roo, Mexico",
            "place_type": ["place"],
            "text": "Tulum",
            "context": [
              {"id": "region.123", "text": "Quintana Roo"},
              {"id": "country.456", "text": "Mexico"}
            ]
          }]
        }
        """;

    private static final String NEIGHBORHOOD_RESPONSE = """
        {
          "features": [{
            "place_name": "Condesa, Mexico City, Mexico City, Mexico",
            "place_type": ["neighborhood"],
            "text": "Condesa",
            "context": [
              {"id": "place.789", "text": "Mexico City"},
              {"id": "region.101", "text": "Mexico City"},
              {"id": "country.202", "text": "Mexico"}
            ]
          }]
        }
        """;

    @SuppressWarnings("unchecked")
    @Test
    void fetch_parsesPlaceWithRegionAndCountry() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        var response = Mockito.mock(HttpResponse.class);
        when(response.body()).thenReturn(FULL_RESPONSE);
        when(httpClient.send(any(), any())).thenReturn(response);

        var client = new MapboxGeocodingClient(httpClient, "https://api.mapbox.com", "test-token");
        GeographicContextData result = client.fetch(mockPatch(20.2114, -87.4654));

        assertThat(result.fullAddress()).isEqualTo("Tulum, Quintana Roo, Mexico");
        assertThat(result.placeName()).isEqualTo("Tulum");
        assertThat(result.city()).isEqualTo("Tulum");   // place_type is "place" → city = placeName
        assertThat(result.region()).isEqualTo("Quintana Roo");
        assertThat(result.country()).isEqualTo("Mexico");
        assertThat(result.neighborhood()).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void fetch_parsesNeighborhoodWithCity() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        var response = Mockito.mock(HttpResponse.class);
        when(response.body()).thenReturn(NEIGHBORHOOD_RESPONSE);
        when(httpClient.send(any(), any())).thenReturn(response);

        var client = new MapboxGeocodingClient(httpClient, "https://api.mapbox.com", "test-token");
        GeographicContextData result = client.fetch(mockPatch(19.4, -99.2));

        assertThat(result.neighborhood()).isEqualTo("Condesa");
        assertThat(result.city()).isEqualTo("Mexico City");
        assertThat(result.region()).isEqualTo("Mexico City");
        assertThat(result.country()).isEqualTo("Mexico");
    }

    @SuppressWarnings("unchecked")
    @Test
    void fetch_returnsDefault_onEmptyFeatures() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        var response = Mockito.mock(HttpResponse.class);
        when(response.body()).thenReturn("{\"features\":[]}");
        when(httpClient.send(any(), any())).thenReturn(response);

        var client = new MapboxGeocodingClient(httpClient, "https://api.mapbox.com", "test-token");
        GeographicContextData result = client.fetch(mockPatch(0, 0));

        assertThat(result.fullAddress()).isEqualTo("Unknown");
        assertThat(result.neighborhood()).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void fetch_returnsDefault_onHttpError() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        when(httpClient.send(any(), any())).thenThrow(new java.io.IOException("timeout"));

        var client = new MapboxGeocodingClient(httpClient, "https://api.mapbox.com", "test-token");
        GeographicContextData result = client.fetch(mockPatch(0, 0));

        assertThat(result.fullAddress()).isEqualTo("Unknown");
    }

    private Patch mockPatch(double lat, double lng) {
        var patch = Mockito.mock(Patch.class);
        when(patch.getCenterLat()).thenReturn(BigDecimal.valueOf(lat));
        when(patch.getCenterLng()).thenReturn(BigDecimal.valueOf(lng));
        return patch;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./mvnw test -pl . -Dtest=MapboxGeocodingClientTest -q 2>&1 | tail -5
```

Expected: FAIL — `MapboxGeocodingClient` does not exist yet.

- [ ] **Step 3: Implement MapboxGeocodingClient**

```java
// backend/src/main/java/io/fouracres/client/MapboxGeocodingClient.java
package io.fouracres.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.dto.GeographicContextData;
import io.fouracres.model.Patch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Component
public class MapboxGeocodingClient {

    private static final GeographicContextData DEFAULT =
        new GeographicContextData("Unknown", null, "Unknown", "Unknown", "Unknown", "Unknown");

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String accessToken;
    private final ObjectMapper mapper = new ObjectMapper();

    public MapboxGeocodingClient(HttpClient httpClient,
                                  @Value("${app.mapbox.base-url}") String baseUrl,
                                  @Value("${app.mapbox.access-token}") String accessToken) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.accessToken = accessToken;
    }

    public GeographicContextData fetch(Patch patch) {
        // Mapbox expects lng,lat (longitude first)
        String url = "%s/geocoding/v5/mapbox.places/%s,%s.json?access_token=%s&types=neighborhood,place,region,country&language=en&limit=1"
            .formatted(baseUrl,
                       patch.getCenterLng().toPlainString(),
                       patch.getCenterLat().toPlainString(),
                       accessToken);
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parse(mapper.readTree(response.body()));
        } catch (Exception e) {
            return DEFAULT;
        }
    }

    private GeographicContextData parse(JsonNode root) {
        JsonNode features = root.path("features");
        if (!features.isArray() || features.isEmpty()) return DEFAULT;

        JsonNode first = features.get(0);
        String fullAddress = first.path("place_name").asText("Unknown");
        String placeName   = first.path("text").asText("Unknown");
        String placeType   = first.path("place_type").path(0).asText("");

        String neighborhood = null;
        String city    = "Unknown";
        String region  = "Unknown";
        String country = "Unknown";

        for (JsonNode ctx : first.path("context")) {
            String id   = ctx.path("id").asText("");
            String text = ctx.path("text").asText("Unknown");
            if (id.startsWith("neighborhood.")) neighborhood = text;
            else if (id.startsWith("place."))   city    = text;
            else if (id.startsWith("region."))  region  = text;
            else if (id.startsWith("country.")) country = text;
        }

        // If the top feature IS a place, it is the city
        if ("place".equals(placeType) && "Unknown".equals(city)) {
            city = placeName;
        }

        return new GeographicContextData(placeName, neighborhood, city, region, country, fullAddress);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend && ./mvnw test -pl . -Dtest=MapboxGeocodingClientTest -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS, 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/fouracres/client/MapboxGeocodingClient.java \
        backend/src/test/java/io/fouracres/client/MapboxGeocodingClientTest.java
git commit -m "feat: MapboxGeocodingClient — reverse geocode patch centroid to geographic context"
```

---

### Task 3: WeatherApiClient + Test

**Files:**
- Create: `backend/src/main/java/io/fouracres/client/WeatherApiClient.java`
- Create: `backend/src/test/java/io/fouracres/client/WeatherApiClientTest.java`

**Interfaces:**
- Consumes: `WeatherData`, `WeatherData.ForecastDay` from Task 1 · `Patch` entity
- Produces: `WeatherApiClient.fetch(Patch) → WeatherData` — used by Task 5

**How WeatherAPI.com works:**
```
GET https://api.weatherapi.com/v1/forecast.json?key=KEY&q={lat},{lng}&days=7&aqi=no&alerts=no

Response:
{
  "current": {
    "temp_c": 22.5,
    "condition": {"text": "Partly cloudy", "icon": "//cdn.weatherapi.com/weather/64x64/day/116.png"},
    "wind_kph": 15.3,
    "humidity": 68,
    "uv": 6.0
  },
  "forecast": {
    "forecastday": [
      {"date": "2026-09-07", "day": {"maxtemp_c": 26.0, "mintemp_c": 18.0, "condition": {"text": "Sunny"}}},
      ...7 items total
    ]
  }
}
```

The `icon` field comes as `//cdn.weatherapi.com/...` — prefix `"https:"` before storing.

- [ ] **Step 1: Write the failing test**

```java
// backend/src/test/java/io/fouracres/client/WeatherApiClientTest.java
package io.fouracres.client;

import io.fouracres.dto.WeatherData;
import io.fouracres.model.Patch;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class WeatherApiClientTest {

    private static final String WEATHER_RESPONSE = """
        {
          "current": {
            "temp_c": 22.5,
            "condition": {"text": "Partly cloudy", "icon": "//cdn.weatherapi.com/weather/64x64/day/116.png"},
            "wind_kph": 15.3,
            "humidity": 68,
            "uv": 6.0
          },
          "forecast": {
            "forecastday": [
              {"date": "2026-09-07", "day": {"maxtemp_c": 26.0, "mintemp_c": 18.0, "condition": {"text": "Sunny"}}},
              {"date": "2026-09-08", "day": {"maxtemp_c": 24.0, "mintemp_c": 17.0, "condition": {"text": "Cloudy"}}},
              {"date": "2026-09-09", "day": {"maxtemp_c": 22.0, "mintemp_c": 16.0, "condition": {"text": "Rain"}}},
              {"date": "2026-09-10", "day": {"maxtemp_c": 23.0, "mintemp_c": 17.0, "condition": {"text": "Sunny"}}},
              {"date": "2026-09-11", "day": {"maxtemp_c": 25.0, "mintemp_c": 18.0, "condition": {"text": "Partly cloudy"}}},
              {"date": "2026-09-12", "day": {"maxtemp_c": 27.0, "mintemp_c": 19.0, "condition": {"text": "Sunny"}}},
              {"date": "2026-09-13", "day": {"maxtemp_c": 26.0, "mintemp_c": 18.0, "condition": {"text": "Partly cloudy"}}}
            ]
          }
        }
        """;

    @SuppressWarnings("unchecked")
    @Test
    void fetch_parsesCurrentWeatherAndForecast() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        var response   = Mockito.mock(HttpResponse.class);
        when(response.body()).thenReturn(WEATHER_RESPONSE);
        when(httpClient.send(any(), any())).thenReturn(response);

        var client = new WeatherApiClient(httpClient, "https://api.weatherapi.com", "test-key");
        WeatherData result = client.fetch(mockPatch(-3.4653, -62.2159));

        assertThat(result.tempC()).isEqualTo(22.5);
        assertThat(result.condition()).isEqualTo("Partly cloudy");
        assertThat(result.windKph()).isEqualTo(15.3);
        assertThat(result.humidity()).isEqualTo(68);
        assertThat(result.forecast()).hasSize(7);
        assertThat(result.forecast().get(0).date()).isEqualTo("2026-09-07");
        assertThat(result.forecast().get(0).maxTempC()).isEqualTo(26.0);
        assertThat(result.forecast().get(0).minTempC()).isEqualTo(18.0);
    }

    @SuppressWarnings("unchecked")
    @Test
    void fetch_prefixesIconUrlWithHttps() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        var response   = Mockito.mock(HttpResponse.class);
        when(response.body()).thenReturn(WEATHER_RESPONSE);
        when(httpClient.send(any(), any())).thenReturn(response);

        var client = new WeatherApiClient(httpClient, "https://api.weatherapi.com", "test-key");
        WeatherData result = client.fetch(mockPatch(0, 0));

        assertThat(result.conditionIconUrl()).startsWith("https://cdn.weatherapi.com");
    }

    @SuppressWarnings("unchecked")
    @Test
    void fetch_returnsDefault_onHttpError() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        when(httpClient.send(any(), any())).thenThrow(new IOException("connection refused"));

        var client = new WeatherApiClient(httpClient, "https://api.weatherapi.com", "test-key");
        WeatherData result = client.fetch(mockPatch(0, 0));

        assertThat(result.condition()).isEqualTo("Unavailable");
        assertThat(result.forecast()).isEmpty();
    }

    private Patch mockPatch(double lat, double lng) {
        var patch = Mockito.mock(Patch.class);
        when(patch.getCenterLat()).thenReturn(BigDecimal.valueOf(lat));
        when(patch.getCenterLng()).thenReturn(BigDecimal.valueOf(lng));
        return patch;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./mvnw test -pl . -Dtest=WeatherApiClientTest -q 2>&1 | tail -5
```

Expected: FAIL — `WeatherApiClient` does not exist.

- [ ] **Step 3: Implement WeatherApiClient**

```java
// backend/src/main/java/io/fouracres/client/WeatherApiClient.java
package io.fouracres.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.dto.WeatherData;
import io.fouracres.model.Patch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@Component
public class WeatherApiClient {

    private static final WeatherData DEFAULT =
        new WeatherData(0, "Unavailable", "", 0, 0, 0, List.of());

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public WeatherApiClient(HttpClient httpClient,
                             @Value("${app.weatherapi.base-url}") String baseUrl,
                             @Value("${app.weatherapi.api-key}") String apiKey) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public WeatherData fetch(Patch patch) {
        String url = "%s/v1/forecast.json?key=%s&q=%s,%s&days=7&aqi=no&alerts=no"
            .formatted(baseUrl, apiKey,
                       patch.getCenterLat().toPlainString(),
                       patch.getCenterLng().toPlainString());
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parse(mapper.readTree(response.body()));
        } catch (Exception e) {
            return DEFAULT;
        }
    }

    private WeatherData parse(JsonNode root) {
        JsonNode current = root.path("current");
        double tempC    = current.path("temp_c").asDouble(0);
        String cond     = current.path("condition").path("text").asText("Unknown");
        String icon     = current.path("condition").path("icon").asText("");
        if (icon.startsWith("//")) icon = "https:" + icon;
        double wind     = current.path("wind_kph").asDouble(0);
        int    humidity = current.path("humidity").asInt(0);
        double uv       = current.path("uv").asDouble(0);

        List<WeatherData.ForecastDay> forecast = new ArrayList<>();
        for (JsonNode day : root.path("forecast").path("forecastday")) {
            String date   = day.path("date").asText("");
            double maxC   = day.path("day").path("maxtemp_c").asDouble(0);
            double minC   = day.path("day").path("mintemp_c").asDouble(0);
            String dayC   = day.path("day").path("condition").path("text").asText("");
            forecast.add(new WeatherData.ForecastDay(date, maxC, minC, dayC));
        }

        return new WeatherData(tempC, cond, icon, wind, humidity, uv, forecast);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend && ./mvnw test -pl . -Dtest=WeatherApiClientTest -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/fouracres/client/WeatherApiClient.java \
        backend/src/test/java/io/fouracres/client/WeatherApiClientTest.java
git commit -m "feat: WeatherApiClient — current conditions and 7-day forecast from WeatherAPI.com"
```

---

### Task 4: SentinelClient + Test

**Files:**
- Create: `backend/src/main/java/io/fouracres/client/SentinelClient.java`
- Create: `backend/src/test/java/io/fouracres/client/SentinelClientTest.java`

**Interfaces:**
- Consumes: `SatelliteSceneData` from Task 1 · `Patch` entity
- Produces: `SentinelClient.fetch(Patch) → SatelliteSceneData` — used by Task 5

**How the Copernicus STAC API works:**
```
GET https://catalogue.dataspace.copernicus.eu/stac/collections/SENTINEL-2/items
    ?bbox={lng-0.1},{lat-0.1},{lng+0.1},{lat+0.1}
    &datetime={30daysAgo}/{now}
    &limit=5
    &sortby=-datetime

No Authorization header needed.

Response:
{
  "features": [{
    "properties": {
      "datetime": "2026-09-05T10:23:45.000Z",
      "eo:cloud_cover": 12.4,
      "s2:product_type": "S2MSI2A"
    },
    "links": [
      {"rel": "self", "href": "..."},
      {"rel": "thumbnail", "href": "https://catalogue.../quicklook.jpg"}
    ]
  }]
}
```

Properties `"eo:cloud_cover"` and `"s2:product_type"` contain colons — access them with `JsonNode.get("eo:cloud_cover")`, not `.path()` (`.path()` handles dots, not colons, the same way but use `.get()` to be safe and check for null).

`bbox` uses `BigDecimal` arithmetic to avoid floating-point imprecision.
`datetime` uses `Instant.now().truncatedTo(ChronoUnit.SECONDS)` to omit nanoseconds.

- [ ] **Step 1: Write the failing test**

```java
// backend/src/test/java/io/fouracres/client/SentinelClientTest.java
package io.fouracres.client;

import io.fouracres.dto.SatelliteSceneData;
import io.fouracres.model.Patch;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class SentinelClientTest {

    private static final String SCENE_RESPONSE = """
        {
          "features": [{
            "properties": {
              "datetime": "2026-09-05T10:23:45.000Z",
              "eo:cloud_cover": 12.4,
              "s2:product_type": "S2MSI2A"
            },
            "links": [
              {"rel": "self", "href": "https://catalogue.example.com/item"},
              {"rel": "thumbnail", "href": "https://catalogue.example.com/quicklook.jpg"}
            ]
          }]
        }
        """;

    @SuppressWarnings("unchecked")
    @Test
    void fetch_parsesLatestScene() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        var response   = Mockito.mock(HttpResponse.class);
        when(response.body()).thenReturn(SCENE_RESPONSE);
        when(httpClient.send(any(), any())).thenReturn(response);

        var client = new SentinelClient(httpClient, "https://catalogue.dataspace.copernicus.eu");
        SatelliteSceneData result = client.fetch(mockPatch(20.2114, -87.4654));

        assertThat(result.hasRecentScene()).isTrue();
        assertThat(result.latestSceneDate()).isEqualTo("2026-09-05T10:23:45.000Z");
        assertThat(result.cloudCoverPercent()).isEqualTo(12.4);
        assertThat(result.productType()).isEqualTo("S2MSI2A");
        assertThat(result.thumbnailUrl()).isEqualTo("https://catalogue.example.com/quicklook.jpg");
    }

    @SuppressWarnings("unchecked")
    @Test
    void fetch_returnsNoScene_onEmptyFeatures() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        var response   = Mockito.mock(HttpResponse.class);
        when(response.body()).thenReturn("{\"features\":[]}");
        when(httpClient.send(any(), any())).thenReturn(response);

        var client = new SentinelClient(httpClient, "https://catalogue.dataspace.copernicus.eu");
        SatelliteSceneData result = client.fetch(mockPatch(0, 0));

        assertThat(result.hasRecentScene()).isFalse();
        assertThat(result.latestSceneDate()).isNull();
        assertThat(result.cloudCoverPercent()).isEqualTo(0.0);
    }

    @SuppressWarnings("unchecked")
    @Test
    void fetch_returnsNoScene_onHttpError() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        when(httpClient.send(any(), any())).thenThrow(new java.io.IOException("timeout"));

        var client = new SentinelClient(httpClient, "https://catalogue.dataspace.copernicus.eu");
        SatelliteSceneData result = client.fetch(mockPatch(0, 0));

        assertThat(result.hasRecentScene()).isFalse();
    }

    private Patch mockPatch(double lat, double lng) {
        var patch = Mockito.mock(Patch.class);
        when(patch.getCenterLat()).thenReturn(BigDecimal.valueOf(lat));
        when(patch.getCenterLng()).thenReturn(BigDecimal.valueOf(lng));
        return patch;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./mvnw test -pl . -Dtest=SentinelClientTest -q 2>&1 | tail -5
```

Expected: FAIL — `SentinelClient` does not exist.

- [ ] **Step 3: Implement SentinelClient**

```java
// backend/src/main/java/io/fouracres/client/SentinelClient.java
package io.fouracres.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.dto.SatelliteSceneData;
import io.fouracres.model.Patch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class SentinelClient {

    private static final SatelliteSceneData NO_SCENE =
        new SatelliteSceneData(false, null, 0.0, null, null);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public SentinelClient(HttpClient httpClient,
                           @Value("${app.sentinel.base-url}") String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
    }

    public SatelliteSceneData fetch(Patch patch) {
        BigDecimal lat    = patch.getCenterLat();
        BigDecimal lng    = patch.getCenterLng();
        BigDecimal offset = new BigDecimal("0.1");

        String bbox = "%s,%s,%s,%s".formatted(
            lng.subtract(offset).toPlainString(),
            lat.subtract(offset).toPlainString(),
            lng.add(offset).toPlainString(),
            lat.add(offset).toPlainString());

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String dateRange = now.minus(30, ChronoUnit.DAYS) + "/" + now;

        String url = "%s/stac/collections/SENTINEL-2/items?bbox=%s&datetime=%s&limit=5&sortby=-datetime"
            .formatted(baseUrl, bbox, dateRange);

        try {
            var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parse(mapper.readTree(response.body()));
        } catch (Exception e) {
            return NO_SCENE;
        }
    }

    private SatelliteSceneData parse(JsonNode root) {
        JsonNode features = root.path("features");
        if (!features.isArray() || features.isEmpty()) return NO_SCENE;

        JsonNode first = features.get(0);
        JsonNode props = first.path("properties");

        String date        = props.path("datetime").asText(null);
        JsonNode ccNode    = props.get("eo:cloud_cover");
        double cloudCover  = ccNode != null ? ccNode.asDouble(0) : 0.0;
        JsonNode ptNode    = props.get("s2:product_type");
        String productType = ptNode != null ? ptNode.asText(null) : null;

        String thumbnailUrl = null;
        for (JsonNode link : first.path("links")) {
            if ("thumbnail".equals(link.path("rel").asText(""))) {
                thumbnailUrl = link.path("href").asText(null);
                break;
            }
        }

        return new SatelliteSceneData(true, date, cloudCover, productType, thumbnailUrl);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd backend && ./mvnw test -pl . -Dtest=SentinelClientTest -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/fouracres/client/SentinelClient.java \
        backend/src/test/java/io/fouracres/client/SentinelClientTest.java
git commit -m "feat: SentinelClient — query Copernicus STAC API for latest Sentinel-2 scene metadata"
```

---

### Task 5: Extend InsightsService + Update InsightsServiceTest

**Files:**
- Modify: `backend/src/main/java/io/fouracres/service/InsightsService.java`
- Modify: `backend/src/test/java/io/fouracres/service/InsightsServiceTest.java`

**Interfaces:**
- Consumes: all 6 client classes from Tasks 2-4 and existing · all 6 DTO types from Task 1 · existing repos
- Produces: `InsightsService.getInsights(UUID) → PatchInsightsDto` with 6 fields — existing `PatchController` already calls this, no controller changes needed

The current `InsightsService` constructor takes 6 arguments. The new one takes 9. Spring Boot's `@Autowired` constructor injection works automatically when there is exactly one constructor — no annotation needed.

- [ ] **Step 1: Write the updated failing test**

Replace `InsightsServiceTest.java` entirely:

```java
// backend/src/test/java/io/fouracres/service/InsightsServiceTest.java
package io.fouracres.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.client.*;
import io.fouracres.dto.*;
import io.fouracres.model.Patch;
import io.fouracres.repository.InsightsCacheRepository;
import io.fouracres.repository.PatchRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InsightsServiceTest {

    private InsightsService buildService(PatchRepository patchRepo,
                                         InsightsCacheRepository cacheRepo,
                                         GbifClient gbif,
                                         SoilGridsClient soil,
                                         GfwClient gfw,
                                         MapboxGeocodingClient geocoding,
                                         WeatherApiClient weather,
                                         SentinelClient sentinel) {
        return new InsightsService(patchRepo, cacheRepo, gbif, soil, gfw,
                                    geocoding, weather, sentinel, new ObjectMapper());
    }

    @Test
    void getInsights_callsAllSixClientsAndCachesAllSixLayers() {
        var patchId = UUID.randomUUID();
        var patch   = Mockito.mock(Patch.class);

        var patchRepo  = Mockito.mock(PatchRepository.class);
        var cacheRepo  = Mockito.mock(InsightsCacheRepository.class);
        var gbif       = Mockito.mock(GbifClient.class);
        var soil       = Mockito.mock(SoilGridsClient.class);
        var gfw        = Mockito.mock(GfwClient.class);
        var geocoding  = Mockito.mock(MapboxGeocodingClient.class);
        var weather    = Mockito.mock(WeatherApiClient.class);
        var sentinel   = Mockito.mock(SentinelClient.class);

        when(patchRepo.findById(patchId)).thenReturn(Optional.of(patch));
        when(cacheRepo.findByPatchIdAndLayerAndExpiresAtAfter(eq(patchId), any(), any()))
            .thenReturn(Optional.empty());  // all misses

        when(gbif.fetch(patch)).thenReturn(new BiodiversityData(42, List.of(), 3));
        when(soil.fetch(patch)).thenReturn(new SoilData(21.5, 5.7, 32.4));
        when(gfw.fetch(patch)).thenReturn(new CarbonData(35.0, 210.5, 0.08));
        when(geocoding.fetch(patch)).thenReturn(
            new GeographicContextData("Tulum", null, "Tulum", "Quintana Roo", "Mexico", "Tulum, Mexico"));
        when(weather.fetch(patch)).thenReturn(
            new WeatherData(22.5, "Sunny", "https://cdn.example.com/sun.png", 10.0, 60, 5.0, List.of()));
        when(sentinel.fetch(patch)).thenReturn(
            new SatelliteSceneData(true, "2026-09-05T10:00:00Z", 12.4, "S2MSI2A", null));

        var service = buildService(patchRepo, cacheRepo, gbif, soil, gfw, geocoding, weather, sentinel);
        PatchInsightsDto result = service.getInsights(patchId);

        assertThat(result.biodiversity().speciesCount()).isEqualTo(42);
        assertThat(result.soil().ph()).isEqualTo(5.7);
        assertThat(result.carbon().carbonDensityMgHa()).isEqualTo(210.5);
        assertThat(result.geographicContext().country()).isEqualTo("Mexico");
        assertThat(result.weather().tempC()).isEqualTo(22.5);
        assertThat(result.satellite().cloudCoverPercent()).isEqualTo(12.4);

        // 6 cache saves, one per layer
        verify(cacheRepo, times(6)).save(any());
    }

    @Test
    void getInsights_returnsCachedData_onFullCacheHit() throws Exception {
        var patchId   = UUID.randomUUID();
        var patchRepo = Mockito.mock(PatchRepository.class);
        var cacheRepo = Mockito.mock(InsightsCacheRepository.class);
        var gbif      = Mockito.mock(GbifClient.class);
        var soil      = Mockito.mock(SoilGridsClient.class);
        var gfw       = Mockito.mock(GfwClient.class);
        var geocoding = Mockito.mock(MapboxGeocodingClient.class);
        var weather   = Mockito.mock(WeatherApiClient.class);
        var sentinel  = Mockito.mock(SentinelClient.class);

        var mapper = new ObjectMapper();

        var bioData  = new BiodiversityData(7, List.of(), 1);
        var soilData = new SoilData(10.0, 6.0, 20.0);
        var carbData = new CarbonData(50.0, 100.0, 0.0);
        var geoData  = new GeographicContextData("City", null, "City", "Region", "Country", "City, Region, Country");
        var wxData   = new WeatherData(18.0, "Cloudy", "", 5.0, 75, 2.0, List.of());
        var satData  = new SatelliteSceneData(false, null, 0.0, null, null);

        mockCacheHit(cacheRepo, patchId, "BIODIVERSITY", mapper.writeValueAsString(bioData));
        mockCacheHit(cacheRepo, patchId, "SOIL",         mapper.writeValueAsString(soilData));
        mockCacheHit(cacheRepo, patchId, "CARBON",       mapper.writeValueAsString(carbData));
        mockCacheHit(cacheRepo, patchId, "GEOCODING",    mapper.writeValueAsString(geoData));
        mockCacheHit(cacheRepo, patchId, "WEATHER",      mapper.writeValueAsString(wxData));
        mockCacheHit(cacheRepo, patchId, "SATELLITE",    mapper.writeValueAsString(satData));

        var service = buildService(patchRepo, cacheRepo, gbif, soil, gfw, geocoding, weather, sentinel);
        PatchInsightsDto result = service.getInsights(patchId);

        assertThat(result.biodiversity().speciesCount()).isEqualTo(7);
        assertThat(result.geographicContext().country()).isEqualTo("Country");
        assertThat(result.weather().tempC()).isEqualTo(18.0);
        assertThat(result.satellite().hasRecentScene()).isFalse();

        verifyNoInteractions(gbif, soil, gfw, geocoding, weather, sentinel);
    }

    private void mockCacheHit(InsightsCacheRepository cacheRepo, UUID patchId, String layer, String payload) {
        var cached = Mockito.mock(io.fouracres.model.InsightsCache.class);
        when(cached.getPayload()).thenReturn(payload);
        when(cacheRepo.findByPatchIdAndLayerAndExpiresAtAfter(eq(patchId), eq(layer), any()))
            .thenReturn(Optional.of(cached));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd backend && ./mvnw test -pl . -Dtest=InsightsServiceTest -q 2>&1 | tail -10
```

Expected: FAIL — `InsightsService` constructor doesn't accept 9 args yet.

- [ ] **Step 3: Replace InsightsService**

```java
// backend/src/main/java/io/fouracres/service/InsightsService.java
package io.fouracres.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.client.*;
import io.fouracres.dto.*;
import io.fouracres.model.InsightsCache;
import io.fouracres.model.Patch;
import io.fouracres.repository.InsightsCacheRepository;
import io.fouracres.repository.PatchRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class InsightsService {

    private final PatchRepository patchRepository;
    private final InsightsCacheRepository cacheRepository;
    private final GbifClient gbifClient;
    private final SoilGridsClient soilGridsClient;
    private final GfwClient gfwClient;
    private final MapboxGeocodingClient geocodingClient;
    private final WeatherApiClient weatherClient;
    private final SentinelClient sentinelClient;
    private final ObjectMapper mapper;

    public InsightsService(PatchRepository patchRepository,
                            InsightsCacheRepository cacheRepository,
                            GbifClient gbifClient,
                            SoilGridsClient soilGridsClient,
                            GfwClient gfwClient,
                            MapboxGeocodingClient geocodingClient,
                            WeatherApiClient weatherClient,
                            SentinelClient sentinelClient,
                            ObjectMapper mapper) {
        this.patchRepository  = patchRepository;
        this.cacheRepository  = cacheRepository;
        this.gbifClient       = gbifClient;
        this.soilGridsClient  = soilGridsClient;
        this.gfwClient        = gfwClient;
        this.geocodingClient  = geocodingClient;
        this.weatherClient    = weatherClient;
        this.sentinelClient   = sentinelClient;
        this.mapper           = mapper;
    }

    public PatchInsightsDto getInsights(UUID patchId) {
        var now = Instant.now();

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

        var bioFuture       = CompletableFuture.supplyAsync(() -> gbifClient.fetch(patch));
        var soilFuture      = CompletableFuture.supplyAsync(() -> soilGridsClient.fetch(patch));
        var carbonFuture    = CompletableFuture.supplyAsync(() -> gfwClient.fetch(patch));
        var geoFuture       = CompletableFuture.supplyAsync(() -> geocodingClient.fetch(patch));
        var weatherFuture   = CompletableFuture.supplyAsync(() -> weatherClient.fetch(patch));
        var satelliteFuture = CompletableFuture.supplyAsync(() -> sentinelClient.fetch(patch));

        CompletableFuture.allOf(bioFuture, soilFuture, carbonFuture,
                                 geoFuture, weatherFuture, satelliteFuture).join();

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

    private Instant expiresAt(String layer) {
        return switch (layer) {
            case "WEATHER"   -> Instant.now().plus(1,  ChronoUnit.HOURS);
            case "SATELLITE" -> Instant.now().plus(7,  ChronoUnit.DAYS);
            case "GEOCODING" -> Instant.now().plus(30, ChronoUnit.DAYS);
            default          -> Instant.now().plus(24, ChronoUnit.HOURS);
        };
    }

    private void saveCache(UUID patchId, String layer, Object data, Instant expires) {
        try {
            var cache = new InsightsCache(patchId, layer, mapper.writeValueAsString(data), expires);
            cacheRepository.save(cache);
        } catch (Exception ignored) {}
    }

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
}
```

- [ ] **Step 4: Run all backend tests**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS. All existing tests still pass. `InsightsServiceTest` now passes with 2 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/fouracres/service/InsightsService.java \
        backend/src/test/java/io/fouracres/service/InsightsServiceTest.java
git commit -m "feat: extend InsightsService to 6 parallel clients with per-layer TTL cache"
```

---

### Task 6: Configuration

**Files:**
- Create: `backend/src/main/resources/application.properties`
- Modify: `.env.example`

**Interfaces:**
- Consumes: nothing from other tasks
- Produces: Spring resolves `@Value("${app.mapbox.access-token}")`, `@Value("${app.weatherapi.api-key}")`, `@Value("${app.sentinel.base-url}")` at startup — Tasks 2, 3, 4 clients fail to start without this

Note: The `api` docker-compose service uses `env_file: .env`, so ALL variables in `.env` are passed to the Spring Boot container as OS environment variables. Spring Boot automatically resolves `${MAPBOX_TOKEN}` from the OS environment.

- [ ] **Step 1: Check if application.properties already exists**

```bash
ls backend/src/main/resources/application.properties 2>/dev/null && echo EXISTS || echo MISSING
```

- [ ] **Step 2: Create or overwrite application.properties with all properties**

Whether the file exists or not, ensure it contains exactly this content (it is safe to overwrite — if any previous content existed it would have been from an uncommitted local state):

```properties
# backend/src/main/resources/application.properties

# GBIF — public API, no auth
app.gbif.base-url=https://api.gbif.org/v1

# SoilGrids — public API, no auth
app.soilgrids.base-url=https://rest.isric.org

# Global Forest Watch — API key from .env GFW_API_KEY
app.gfw.base-url=https://data.api.resourcewatch.org
app.gfw.api-key=${GFW_API_KEY}

# Mapbox — token from .env MAPBOX_TOKEN (shared with frontend)
app.mapbox.base-url=https://api.mapbox.com
app.mapbox.access-token=${MAPBOX_TOKEN}

# WeatherAPI.com — key from .env WEATHERAPI_KEY
app.weatherapi.base-url=https://api.weatherapi.com
app.weatherapi.api-key=${WEATHERAPI_KEY}

# Copernicus Data Space — no auth for STAC catalog
app.sentinel.base-url=https://catalogue.dataspace.copernicus.eu
```

- [ ] **Step 3: Update .env.example**

Add `WEATHERAPI_KEY` after `MAPBOX_TOKEN`:

Current `.env.example`:
```
MAPBOX_TOKEN=pk.eyJ1...your_mapbox_public_token_here
GFW_API_KEY=your_gfw_api_key_here
```

New `.env.example`:
```
MAPBOX_TOKEN=pk.eyJ1...your_mapbox_public_token_here
GFW_API_KEY=your_gfw_api_key_here
WEATHERAPI_KEY=your_weatherapi_key_here
```

To get a `WEATHERAPI_KEY`: register free at https://www.weatherapi.com/ → Dashboard → API Key. The free tier allows 1M calls/month.

- [ ] **Step 4: Verify Spring starts with the properties**

```bash
cd backend && ./mvnw spring-boot:run -q &
sleep 8 && curl -s http://localhost:8080/actuator/health 2>/dev/null | grep -q UP && echo "APP HEALTHY" || echo "CHECK LOGS"
kill %1 2>/dev/null
```

If the app fails to start with `Could not resolve placeholder 'WEATHERAPI_KEY'`, add a real `WEATHERAPI_KEY` to your local `.env` file (copy `.env.example`, fill in values). The app will not start without all `@Value` placeholders resolved.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/application.properties .env.example
git commit -m "config: add application.properties with all API base URLs and env-var key references"
```

---

### Task 7: Frontend Types + GeographicContextCard

**Files:**
- Modify: `frontend/app/lib/types.ts`
- Create: `frontend/app/components/GeographicContextCard.tsx`

**Interfaces:**
- Consumes: `PatchInsights` interface (existing, now extended)
- Produces: `GeographicContextCard` component — used by Task 8

The existing `PatchInsights` interface in `types.ts` has 3 fields. It must be extended to 6. The `useInsights` hook in `queries.ts` and the `api.ts` fetch function do not need changes — they return whatever JSON the backend sends.

- [ ] **Step 1: Update types.ts**

Open `frontend/app/lib/types.ts`. Replace the entire file:

```typescript
// frontend/app/lib/types.ts

export interface SpeciesEntry {
  name: string
  kingdom: string
}

export interface BiodiversityData {
  speciesCount: number
  topSpecies: SpeciesEntry[]
  threatenedCount: number
}

export interface SoilData {
  organicCarbonGKg: number
  ph: number
  clayPercent: number
}

export interface CarbonData {
  treeCoverPercent: number
  carbonDensityMgHa: number
  coverLossHa: number
}

export interface GeographicContextData {
  placeName: string
  neighborhood: string | null
  city: string
  region: string
  country: string
  fullAddress: string
}

export interface ForecastDay {
  date: string
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

export interface PatchInsights {
  biodiversity: BiodiversityData
  soil: SoilData
  carbon: CarbonData
  geographicContext: GeographicContextData | null
  weather: WeatherData | null
  satellite: SatelliteSceneData | null
}

export interface BoundaryGeoJson {
  type: 'Polygon'
  coordinates: number[][][]
}

export type PatchStatus = 'AVAILABLE' | 'CLAIMED'

export interface Patch {
  id: string
  name: string
  ecosystemType: string
  country: string
  centerLat: number
  centerLng: number
  boundaryGeoJson: BoundaryGeoJson
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

export interface GeoJsonPolygon {
  type: 'Polygon'
  coordinates: number[][][]
}

export interface PatchRegistrationRequest {
  name: string
  description?: string
  ownerName: string
  country: string
  ecosystemType: string
  boundary: GeoJsonPolygon
}

export interface ClaimRequest {
  stewardName: string
  stewardEmail: string
}
```

- [ ] **Step 2: Run TypeScript check**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

Expected: 0 errors. If errors appear about `PatchInsights` fields that no longer exist, check `PatchInfoCard.tsx` — it accesses `insights?.biodiversity`, `insights?.soil`, `insights?.carbon` which are still present.

- [ ] **Step 3: Write the failing test for GeographicContextCard**

There is no test framework for React components in this project — TypeScript strict mode is the verification layer. Create the component and confirm `tsc --noEmit` passes.

- [ ] **Step 4: Create GeographicContextCard**

```tsx
// frontend/app/components/GeographicContextCard.tsx
'use client'

import type { PatchInsights } from '../lib/types'

const GLASS = 'bg-black/60 backdrop-blur-md border border-white/10 rounded-xl p-4 text-white'

interface Props {
  insights: PatchInsights | undefined
  className?: string
}

export function GeographicContextCard({ insights, className }: Props) {
  const geo = insights?.geographicContext

  return (
    <div className={`${GLASS} ${className ?? ''}`}>
      <p className="text-xs font-semibold text-violet-400 mb-2">📍 Location</p>
      {geo ? (
        <>
          <p className="text-sm font-medium leading-tight mb-1">
            {geo.neighborhood ? `${geo.neighborhood}, ` : ''}{geo.city}
          </p>
          <p className="text-slate-400 text-xs">{geo.region}</p>
          <p className="text-slate-500 text-xs">{geo.country}</p>
        </>
      ) : (
        <div className="h-4 bg-white/10 rounded animate-pulse" />
      )}
    </div>
  )
}
```

- [ ] **Step 5: TypeScript check**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

Expected: 0 errors.

- [ ] **Step 6: Commit**

```bash
git add frontend/app/lib/types.ts frontend/app/components/GeographicContextCard.tsx
git commit -m "feat: extend PatchInsights types to 6 fields; add GeographicContextCard"
```

---

### Task 8: WeatherCard + SatelliteCard + Wire into Page

**Files:**
- Create: `frontend/app/components/WeatherCard.tsx`
- Create: `frontend/app/components/SatelliteCard.tsx`
- Modify: `frontend/app/patch/[id]/page.tsx`

**Interfaces:**
- Consumes: `PatchInsights`, `WeatherData`, `SatelliteSceneData` from Task 7 · `useInsights` hook (existing)
- Produces: working `/patch/[id]` page with 6 insight cards in two rows

- [ ] **Step 1: Create WeatherCard**

```tsx
// frontend/app/components/WeatherCard.tsx
'use client'

import type { PatchInsights } from '../lib/types'

const GLASS = 'bg-black/60 backdrop-blur-md border border-white/10 rounded-xl p-4 text-white'

interface Props {
  insights: PatchInsights | undefined
  className?: string
}

export function WeatherCard({ insights, className }: Props) {
  const weather = insights?.weather

  return (
    <div className={`${GLASS} ${className ?? ''}`}>
      <p className="text-xs font-semibold text-sky-400 mb-2">☁️ Weather</p>
      {weather && weather.condition !== 'Unavailable' ? (
        <>
          <div className="flex items-center gap-2 mb-2">
            {weather.conditionIconUrl && (
              <img
                src={weather.conditionIconUrl}
                alt={weather.condition}
                className="w-8 h-8 flex-shrink-0"
              />
            )}
            <div>
              <p className="text-xl font-bold leading-none">{weather.tempC.toFixed(1)}°C</p>
              <p className="text-slate-400 text-xs mt-0.5">{weather.condition}</p>
            </div>
          </div>
          <div className="flex gap-3 text-slate-500 text-xs mb-3">
            <span>💨 {weather.windKph.toFixed(0)} km/h</span>
            <span>💧 {weather.humidity}%</span>
            <span>UV {weather.uvIndex.toFixed(0)}</span>
          </div>
          <div className="flex gap-2 overflow-x-auto pb-0.5">
            {weather.forecast.map(day => {
              const label = new Date(day.date + 'T12:00:00')
                .toLocaleDateString('en', { weekday: 'short' })
              return (
                <div key={day.date} className="text-center flex-shrink-0 min-w-[28px]">
                  <p className="text-slate-500 text-xs">{label}</p>
                  <p className="text-xs font-medium">{day.maxTempC.toFixed(0)}°</p>
                  <p className="text-slate-500 text-xs">{day.minTempC.toFixed(0)}°</p>
                </div>
              )
            })}
          </div>
        </>
      ) : (
        <div className="h-4 bg-white/10 rounded animate-pulse" />
      )}
    </div>
  )
}
```

- [ ] **Step 2: Create SatelliteCard**

Cloud cover badge: green (`text-emerald-300 bg-emerald-500/20`) if < 20%, amber if 20–60%, red if > 60%.

```tsx
// frontend/app/components/SatelliteCard.tsx
'use client'

import { useState } from 'react'
import type { PatchInsights } from '../lib/types'

const GLASS = 'bg-black/60 backdrop-blur-md border border-white/10 rounded-xl p-4 text-white'

interface Props {
  insights: PatchInsights | undefined
  className?: string
}

function cloudBadgeClass(pct: number): string {
  if (pct < 20)  return 'bg-emerald-500/20 text-emerald-300'
  if (pct < 60)  return 'bg-amber-500/20 text-amber-300'
  return 'bg-red-500/20 text-red-300'
}

export function SatelliteCard({ insights, className }: Props) {
  const sat = insights?.satellite
  const [thumbError, setThumbError] = useState(false)

  return (
    <div className={`${GLASS} ${className ?? ''}`}>
      <p className="text-xs font-semibold text-teal-400 mb-2">🛰️ Sentinel-2</p>
      {!sat ? (
        <div className="h-4 bg-white/10 rounded animate-pulse" />
      ) : !sat.hasRecentScene ? (
        <p className="text-slate-500 text-xs">No scene in last 30 days</p>
      ) : (
        <>
          <p className="text-sm font-medium mb-1">
            {new Date(sat.latestSceneDate!).toLocaleDateString('en', {
              day: 'numeric', month: 'short', year: 'numeric',
            })}
          </p>
          <div className="flex gap-1.5 flex-wrap mb-2">
            <span className={`text-xs px-1.5 py-0.5 rounded ${cloudBadgeClass(sat.cloudCoverPercent)}`}>
              {sat.cloudCoverPercent.toFixed(0)}% cloud
            </span>
            <span className="text-xs px-1.5 py-0.5 rounded bg-white/5 text-slate-300">
              {sat.productType === 'S2MSI2A' ? 'Analysis-Ready' : 'Raw (L1C)'}
            </span>
          </div>
          {sat.thumbnailUrl && !thumbError && (
            <img
              src={sat.thumbnailUrl}
              alt="Satellite quicklook"
              className="w-full max-h-16 object-cover rounded"
              onError={() => setThumbError(true)}
            />
          )}
        </>
      )}
    </div>
  )
}
```

- [ ] **Step 3: Update /patch/[id]/page.tsx**

Replace the `{/* Metrics strip — bottom */}` section with two rows. The full updated file:

```tsx
// frontend/app/patch/[id]/page.tsx
'use client'

import dynamic from 'next/dynamic'
import { useParams } from 'next/navigation'
import { usePatch, useInsights, useClaim } from '../../lib/queries'
import { PatchInfoCard } from '../../components/PatchInfoCard'
import { GeographicContextCard } from '../../components/GeographicContextCard'
import { WeatherCard } from '../../components/WeatherCard'
import { SatelliteCard } from '../../components/SatelliteCard'

const MyPatchMap = dynamic(
  () => import('../../components/MyPatchMap').then(m => ({ default: m.MyPatchMap })),
  { ssr: false }
)

export default function PatchPage() {
  const { id } = useParams<{ id: string }>()
  const { data: patch, isLoading } = usePatch(id)
  const { data: insights } = useInsights(id)
  const { data: claim } = useClaim(id)

  if (isLoading) {
    return (
      <div className="h-screen flex items-center justify-center bg-[#0a1628]">
        <div className="text-slate-400 animate-pulse">Loading your patch...</div>
      </div>
    )
  }

  if (!patch) {
    return (
      <div className="h-screen flex items-center justify-center bg-[#0a1628]">
        <div className="text-center">
          <p className="text-slate-400 mb-4">Patch not found</p>
          <a href="/explore" className="text-emerald-400 hover:text-emerald-300 text-sm transition-colors">
            ← Back to explore
          </a>
        </div>
      </div>
    )
  }

  return (
    <div className="relative h-screen overflow-hidden">
      <MyPatchMap patch={patch} />

      {/* Top-right nav */}
      <div className="absolute top-4 right-4 z-10">
        <a
          href="/explore"
          className="text-white/70 hover:text-white text-sm bg-black/40 backdrop-blur-sm px-3 py-1.5 rounded-full transition-colors"
        >
          ← 4Acres Earth
        </a>
      </div>

      {/* Identity card — top-left */}
      <div className="absolute top-4 left-4 z-10 w-64">
        <PatchInfoCard type="identity" patch={patch} claim={claim} />
      </div>

      {/* New integrations row — above existing strip */}
      <div className="absolute bottom-36 left-4 right-4 z-10 flex gap-3">
        <GeographicContextCard insights={insights} className="flex-1" />
        <WeatherCard insights={insights} className="flex-1" />
        <SatelliteCard insights={insights} className="flex-1" />
      </div>

      {/* Existing metrics row — bottom */}
      <div className="absolute bottom-6 left-4 right-4 z-10 flex gap-3">
        <PatchInfoCard type="biodiversity" insights={insights} className="flex-1" />
        <PatchInfoCard type="soil" insights={insights} className="flex-1" />
        <PatchInfoCard type="carbon" insights={insights} className="flex-1" />
      </div>
    </div>
  )
}
```

- [ ] **Step 4: TypeScript check**

```bash
cd frontend && npx tsc --noEmit 2>&1 | head -20
```

Expected: 0 errors.

- [ ] **Step 5: Run the full stack and open the patch page**

Ensure your `.env` file has real values for `MAPBOX_TOKEN`, `GFW_API_KEY`, and `WEATHERAPI_KEY`.

```bash
docker compose up --build -d
```

Wait ~30 seconds, then open http://localhost:3000/explore. Click a patch → the digital twin page should load. After 5–10 seconds (parallel API fetches), six cards appear: Location, Weather, Sentinel-2 in the upper row; Biodiversity, Soil, Carbon in the lower row.

Verify:
- Location card shows a real place name, region, country for the patch centroid
- Weather card shows current temperature, condition icon, and a 7-day forecast strip
- Sentinel-2 card shows a scene date and cloud cover badge (or "No scene in last 30 days" if none found)
- Existing three cards (Biodiversity, Soil, Carbon) still show correctly

- [ ] **Step 6: Run all backend tests one final time**

```bash
cd backend && ./mvnw test -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 7: Commit**

```bash
git add frontend/app/components/WeatherCard.tsx \
        frontend/app/components/SatelliteCard.tsx \
        frontend/app/patch/\[id\]/page.tsx
git commit -m "feat: WeatherCard, SatelliteCard; wire 6-card layout on /patch/[id] page"
```

---

## Self-Review

**Spec coverage check:**

| Spec section | Covered by task |
|---|---|
| GeographicContextData DTO | T1 |
| WeatherData + ForecastDay DTO | T1 |
| SatelliteSceneData DTO | T1 |
| Extended PatchInsightsDto (6 fields) | T1 |
| MapboxGeocodingClient (lng,lat order, context[] parsing, fallback) | T2 |
| WeatherApiClient (icon prefix, 7-day forecast, fallback) | T3 |
| SentinelClient (bbox arithmetic, datetime range, eo:cloud_cover parsing, fallback) | T4 |
| InsightsService 6-way parallel, per-layer TTL, 6-layer cache check | T5 |
| Configuration: application.properties + .env.example | T6 |
| Frontend types extended | T7 |
| GeographicContextCard | T7 |
| WeatherCard (icon, forecast strip, stats row) | T8 |
| SatelliteCard (cloud badge, product type, thumbnail with error handler) | T8 |
| Two-row layout on /patch/[id] page | T8 |

All spec requirements covered. No placeholders. Type names are consistent across all tasks.
