# Water & Terrain: The Java Code, Explained Simply

This doc walks through the Java backend code for the Water and Terrain cards in plain
language — no assumed expert knowledge — and explains exactly which outside data
sources we pull from, and why those specific ones and not something else.

---

## 1. The big picture, in one paragraph

When you open a patch, the browser asks the Java backend: *"give me all the insights
for this patch."* The Java backend doesn't know anything about water or elevation
itself — it just asks a bunch of specialist "clients" to go fetch that information,
waits for all of them to come back, glues the answers together into one response,
and (to avoid asking again and again) remembers the answer for a while. Water and
Terrain are two of those specialists. Here's the flow:

```
Browser
  │  "give me insights for this patch"
  ▼
PatchController  (the front door — receives the HTTP request)
  ▼
InsightsService  (the coordinator — asks every specialist at once, in parallel)
  ▼
GeoEngineClient  (the specialist for water + terrain)
  │  "hey Next.js, what's the water/terrain like at this lat/lng?"
  ▼
Next.js (a different part of this project, in JavaScript)
  │  actually talks to Google Earth Engine
  ▼
Google Earth Engine  (the real data — satellite imagery, processed)
```

Why does Java ask a *JavaScript* server instead of just calling Google directly?
Because Google Earth Engine only officially supports Python and JavaScript — there's
no Java toolkit for it. So the Java backend treats "ask the Next.js server for
water/terrain data" exactly like it treats "ask GBIF for species data" or "ask
WeatherAPI for weather" — just another web address it sends a request to and gets
JSON back from. From Java's point of view, it doesn't even know Earth Engine exists;
it just knows there's a URL that answers water/terrain questions.

---

## 2. The Java files, one at a time

### `WaterData.java` and `TerrainData.java` — the "shape" of an answer

```java
public record WaterData(
    double surfaceWaterHa,
    String occurrenceClass,
    double recurrencePercent,
    String period,
    double currentWaterPercent,
    String latestSceneDate,
    int currentWindowDays
) {}
```

A Java `record` is just a fixed, named bundle of values — think of it like a labeled
box with compartments. This one has 7 compartments: how much water was detected
(in hectares), whether it's "None"/"Seasonal"/"Permanent", how often it recurs, and
so on. Once one of these boxes is built, nothing inside it can change — which is
exactly what you want for "the answer we got back from an external API a moment
ago." `TerrainData` is the same idea, just for elevation and slope.

### `GeoEngineClient.java` — the specialist that fetches water & terrain

This is the heart of it. Two public methods: `fetchWater(patch)` and
`fetchTerrain(patch)`. They're near-identical, so let's just walk through
`fetchWater`:

```java
public WaterData fetchWater(Patch patch) {
    String url = "%s/water?lat=%s&lng=%s&radiusM=%s"
        .formatted(baseUrl, patch.getCenterLat(), patch.getCenterLng(), PATCH_RADIUS_M);
    try {
        HttpResponse<String> response = sendWithRetry(url);
        if (response.statusCode() != 200) {
            log.warn("Water fetch failed for patch {}: HTTP {} — {}", ...);
            return DEFAULT_WATER;
        }
        JsonNode body = mapper.readTree(response.body());
        return new WaterData(
            body.path("surfaceWaterHa").asDouble(0),
            body.path("occurrenceClass").asText("None"),
            ...
        );
    } catch (Exception e) {
        log.warn("Water fetch failed for patch {}: {}", patch.getId(), e.toString());
        return DEFAULT_WATER;
    }
}
```

In plain steps:

1. **Build a URL** to the Next.js server, e.g. `http://frontend:3000/api/water?lat=...&lng=...&radiusM=...`.
2. **Send the request and wait for a reply** (`sendWithRetry` — explained below).
3. **If something went wrong** (bad response, network error, timeout — anything),
   don't crash the whole page. Log a warning so we can debug it later, and hand
   back `DEFAULT_WATER` — a "nothing detected" placeholder — so the rest of the
   page still renders normally.
4. **If it worked**, read the JSON reply and pull out each field, with a safe
   fallback value if a field happens to be missing (`.asDouble(0)` means "give me
   this number, or 0 if it's not there").

**Why the fixed radius instead of the patch's real shape?** Every patch in this
app is a fixed 4 acres. `PATCH_RADIUS_M` converts "4 acres" into "a circle of this
many meters across" — about 72m. So instead of sending Google a complicated polygon
shape, we just say "look at a 72m circle around this center point," which is
simpler and answers the same question for a fixed-size patch.

**Why `DEFAULT_WATER` instead of throwing an error?** Because one failed API call
(say, Earth Engine is briefly slow) shouldn't take down the whole insights page.
The rest of the cards (biodiversity, soil, carbon...) should still show up fine.
This is the same safety pattern every other client in this codebase uses.

**`sendWithRetry` — why retry at all?**

```java
private HttpResponse<String> sendWithRetry(String url) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
    try {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (java.io.IOException e) {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
```

Water and terrain are fetched *at the same time* (see `InsightsService` below), and
they're the only two requests in this whole app that both go to the *same* address
(the Next.js server) at the exact same instant. Java's HTTP client tries to reuse
connections for efficiency, and occasionally that reuse loses a race — the
connection gets closed right as it's about to be reused, and the request never
actually arrives anywhere. It's a one-off glitch, not a real failure, so we just
try again once before giving up.

### `HttpClientConfig.java` — one shared setting for every outbound call

```java
@Bean
public HttpClient httpClient() {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .build();
}
```

This creates one shared "phone line" (`HttpClient`) that every client in the app
(GBIF, SoilGrids, GFW, Mapbox, WeatherAPI, Sentinel, and our `GeoEngineClient`) uses
to make outbound web requests. We explicitly tell it to speak the older, simpler
HTTP/1.1 "dialect" rather than the newer HTTP/2. That's because our own Next.js
server (which only water/terrain talk to) doesn't understand the fancier upgrade
Java tries by default, and would otherwise just silently drop the connection. All
the other (HTTPS) APIs don't care either way, so this is a safe, global setting.

### `InsightsService.java` — the coordinator

This is the piece that calls `GeoEngineClient`, alongside all the other specialists,
and glues everything together:

```java
var waterFuture   = CompletableFuture.supplyAsync(() -> geoEngineClient.fetchWater(patch));
var terrainFuture = CompletableFuture.supplyAsync(() -> geoEngineClient.fetchTerrain(patch));

CompletableFuture.allOf(bioFuture, soilFuture, carbonFuture,
                         geoFuture, weatherFuture, satelliteFuture,
                         waterFuture, terrainFuture).join();
```

`CompletableFuture` just means "go do this in the background, I'll come back for
the result later." By kicking off all 8 fetches at once instead of one after
another, the whole page loads in "however long the *slowest* one takes," not
"the sum of all of them." Once every future finishes, we cache each result and
bundle everything into one `PatchInsightsDto` to send back to the browser.

**Why cache water/terrain for 30 days, when weather is cached for only 1 hour?**

```java
case "WATER", "TERRAIN" -> Instant.now().plus(30, ChronoUnit.DAYS);
```

Weather changes hour to hour, so a 1-hour cache makes sense. But elevation
literally never changes, and the historical water record only updates once a
year or so at most. Re-fetching either of them daily would just waste Earth
Engine's request quota for an answer that hasn't changed. So they get a much
longer shelf life.

---

## 3. Which external data sources we use, and why

### For Terrain: **SRTM (Shuttle Radar Topography Mission)** via Google Earth Engine

- **What it is:** A near-complete, 30-meter-resolution elevation map of the Earth's
  surface, built from a 2000 space shuttle radar survey and still the standard
  reference elevation dataset in wide use today.
- **Why this one:** It's free, globally complete (virtually no gaps between ~60°N
  and ~56°S, which covers essentially every patch in this app), simple to reason
  about (one number per pixel: height above sea level), and directly available
  through Earth Engine with no extra licensing. Elevation doesn't change over
  human timescales, so a dataset from 2000 is just as valid today as the day it
  was published — there was no reason to look for something "more current."
- **What we compute from it:** min/max elevation in the patch, and slope (steepness)
  derived mathematically from the elevation grid — both are standard Earth Engine
  operations (`ee.Reducer.minMax()`, `ee.Terrain.slope()`).

### For Water — two different datasets, because they answer two different questions

**(a) JRC Global Surface Water — "how has this location behaved historically?"**

- **What it is:** A European Commission (Joint Research Centre) product built by
  analyzing every Landsat satellite image of Earth from 1984 to 2021 — 38 years —
  to work out, pixel by pixel, how often each spot on the planet has shown open
  water.
- **Why this one:** It's the standard, most-cited dataset for exactly this
  question — "is this a river, is it seasonal, is it permanent" — and it comes
  with a long enough time baseline (38 years) to distinguish "this floods every
  monsoon" from "this was flooded once by accident."
- **Its real limitation:** Google/JRC haven't published a newer edition past 2021
  — reprocessing a global 38-year satellite archive takes years, so there's simply
  no "JRC Global Surface Water 2024" to switch to yet. That's a limitation of the
  source data itself, not something fixable in our code.

**(b) Dynamic World — "what does this location look like right now?"**

- **What it is:** A joint Google/World Resources Institute product that classifies
  *every new* Sentinel-2 satellite image (there's a new one every few days) into
  land-cover types — trees, crops, built-up area, water, etc. — usually within a
  day or two of the image being taken.
- **Why we added this one:** Once we realized JRC's dataset is permanently frozen
  at 2021 and can't be made "current," Dynamic World was the natural complement —
  it answers "as of a few weeks ago" instead of "as of 2021." It also happens to
  be a finer 10-meter resolution (versus JRC's 30m), which matters a lot for a
  small 4-acre patch: a 10m grid gives roughly 10× more sample pixels over the
  same small area, so it can detect a water signal that a coarser 30m grid might
  simply not have enough pixels to catch.
- **Why not use Dynamic World for everything and drop JRC?** Because they measure
  different things — Dynamic World tells you "does this look wet right now,"
  not "how many decades has this been a stable wetland." Both are useful, and
  together they cover the "then vs. now" story better than either alone.

### Why not a completely different provider (e.g. a paid water/elevation API)?

Both JRC Global Surface Water and Dynamic World are free, globally complete,
peer-reviewed/well-documented, and already accessible through Google Earth Engine
— which this project already had credentials for (a `dynamic-world-reader` service
account was already provisioned before this feature was built, which is itself a
strong hint these were the intended datasets). There was no need to introduce a
paid or less-proven alternative when Earth Engine already hosts the standard,
free option for both questions.

---

## 4. Quick summary table

| Question | Dataset | Resolution | Time range | Why |
|---|---|---|---|---|
| How high / how steep is this land? | SRTM | 30m | Fixed (2000) | Standard, free, complete, doesn't go stale |
| How persistent has water been here historically? | JRC Global Surface Water | 30m | 1984–2021 | Standard 38-year reference; can't be made more current |
| Does this look like water right now? | Dynamic World | 10m | Rolling, updated every few days | Fills the gap JRC leaves; finer resolution helps small patches |

---

## 5. The one-sentence version

Java doesn't talk to Google directly — it asks our own Next.js server (which
*can* talk to Earth Engine) a simple question over HTTP, gets back plain JSON,
and if anything goes wrong anywhere in that chain, it quietly falls back to
"nothing detected" rather than breaking the page; for water specifically, we
combine a 38-year historical dataset (JRC) with a near-real-time one (Dynamic
World) because neither alone can answer both "how persistent" and "how current."
