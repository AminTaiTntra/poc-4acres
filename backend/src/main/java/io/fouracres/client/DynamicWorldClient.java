package io.fouracres.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.dto.LandCoverData;
import io.fouracres.model.Patch;
import org.locationtech.jts.geom.Coordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Land cover + historical change from Google <b>Dynamic World V1</b>
 * ({@code GOOGLE/DYNAMICWORLD/V1}) via the Earth Engine REST API — read-only
 * {@code value:compute} only (no {@code maps.create}, no EE writes).
 *
 * <p>Per configured year the patch polygon is reduced to the mean Dynamic World
 * class-probability composition (9 classes, normalised to 100%), plus the mean
 * cloud-free observation count per pixel and the share of pixels classified with
 * top probability ≥ the recommended threshold. A baseline→latest class-transition
 * matrix is also computed. Composition, per-class areas (acres/hectares), the year
 * history, per-class change, transitions and a plain-language trend are derived from
 * those reductions.</p>
 *
 * <p>Degrades to {@link LandCoverData#unavailable} when Earth Engine credentials are
 * absent or every call fails — the rest of the insights payload is unaffected.</p>
 */
@Component
public class DynamicWorldClient {

    private static final Logger log = LoggerFactory.getLogger(DynamicWorldClient.class);

    private static final int SCALE_METRES = 10;
    private static final double ACRES_PER_HECTARE = 2.4710538;
    private static final double DEFAULT_PARCEL_ACRES = 4.0;
    private static final int MAX_PARALLEL_YEARS = 6;

    /** Classes counted as vegetation for the trend narrative. */
    private static final Set<String> VEGETATION = Set.of(
        "trees", "grass", "shrub_and_scrub", "flooded_vegetation", "crops");

    private static final Map<String, String> DISPLAY_NAMES = Map.of(
        "water", "Water",
        "trees", "Trees",
        "grass", "Grass",
        "flooded_vegetation", "Flooded vegetation",
        "crops", "Crops",
        "shrub_and_scrub", "Shrub & scrub",
        "built", "Built area",
        "bare", "Bare ground",
        "snow_and_ice", "Snow & ice");

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final GoogleEarthEngineAuth auth;
    private final String baseUrl;
    private final String projectResource;
    private final double probabilityThreshold;
    private final List<Integer> historyYears;

    public DynamicWorldClient(HttpClient httpClient,
                              ObjectMapper mapper,
                              GoogleEarthEngineAuth auth,
                              @Value("${app.dynamicworld.base-url:https://earthengine.googleapis.com}") String baseUrl,
                              @Value("${app.dynamicworld.gee-project:}") String geeProject,
                              @Value("${app.dynamicworld.probability-threshold:0.5}") double probabilityThreshold,
                              @Value("${app.dynamicworld.history-years:2016,2017,2018,2019,2020,2021,2022,2023,2024,2025,2026}") String historyYears) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.auth = auth;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.projectResource = normalizeProject(geeProject);
        this.probabilityThreshold = probabilityThreshold;
        this.historyYears = parseYears(historyYears);
    }

    public LandCoverData fetch(Patch patch) {
        if (projectResource == null || historyYears.isEmpty()) {
            return LandCoverData.unavailable(probabilityThreshold);
        }
        Optional<String> token = auth.accessToken();
        if (token.isEmpty()) {
            return LandCoverData.unavailable(probabilityThreshold);
        }
        List<List<Double>> ring = exteriorRing(patch);
        if (ring.size() < 4) {
            return LandCoverData.unavailable(probabilityThreshold);
        }
        double parcelAcres = parcelAcres(patch);
        String bearer = token.get();

        var perYear = new TreeMap<Integer, YearStat>();
        ExecutorService pool = Executors.newFixedThreadPool(
            Math.min(MAX_PARALLEL_YEARS, Math.max(1, historyYears.size())));
        try {
            var futures = new LinkedHashMap<Integer, Future<YearStat>>();
            for (int year : historyYears) {
                futures.put(year, pool.submit(() -> computeYear(ring, year, bearer)));
            }
            for (var entry : futures.entrySet()) {
                YearStat stat = entry.getValue().get();
                if (stat != null) perYear.put(entry.getKey(), stat);
            }
        } catch (Exception e) {
            log.warn("Dynamic World composition fan-out failed: {}", e.toString());
        } finally {
            pool.shutdownNow();
        }
        if (perYear.isEmpty()) {
            return LandCoverData.unavailable(probabilityThreshold);
        }

        int firstYear = perYear.firstKey();
        int lastYear = perYear.lastKey();
        List<LandCoverData.Transition> transitions = firstYear != lastYear
            ? transitions(computeTransition(ring, firstYear, lastYear, bearer), parcelAcres)
            : List.of();

        return assemble(perYear, parcelAcres, transitions);
    }

    // ── Earth Engine calls ───────────────────────────────────────────────────

    private YearStat computeYear(List<List<Double>> ring, int year, String token) {
        return parseYearResult(post(
            DynamicWorldExpression.yearComposition(ring, year, SCALE_METRES, probabilityThreshold),
            "composition " + year, token));
    }

    private Map<Integer, Double> computeTransition(List<List<Double>> ring, int fromYear, int toYear, String token) {
        return parseTransitionResult(post(
            DynamicWorldExpression.transition(ring, fromYear, toYear, SCALE_METRES),
            "transition " + fromYear + "->" + toYear, token));
    }

    // ── response parsing (package-private for tests) ──────────────────────────

    /** Reads {@code {<class>: meanProb, "observations": n, "confident_fraction": f}} into a normalised stat. */
    YearStat parseYearResult(JsonNode result) {
        if (result == null) return null;
        Map<String, Double> raw = new LinkedHashMap<>();
        double total = 0;
        for (String cls : DynamicWorldExpression.CLASSES) {
            JsonNode v = result.get(cls);
            if (v == null || !v.isNumber()) continue;
            double p = Math.max(0, v.asDouble());
            raw.put(cls, p);
            total += p;
        }
        if (raw.isEmpty() || total <= 0) return null;

        Map<String, Double> percent = new LinkedHashMap<>();
        double t = total;
        raw.forEach((cls, p) -> percent.put(cls, p / t * 100.0));

        double obs = Math.max(0, result.path(DynamicWorldExpression.OBSERVATIONS).asDouble(0));
        double conf = result.path(DynamicWorldExpression.CONFIDENT_FRACTION).asDouble(Double.NaN);
        double confident = Double.isNaN(conf) ? 0 : Math.max(0, Math.min(1, conf));
        return new YearStat(percent, obs, confident);
    }

    /** Reads {@code {"label": {"<from*10+to>": pixelCount}}} into code → count. */
    Map<Integer, Double> parseTransitionResult(JsonNode result) {
        if (result == null) return Map.of();
        JsonNode hist = result.path("label");
        if (!hist.isObject()) return Map.of();

        Map<Integer, Double> codes = new LinkedHashMap<>();
        var it = hist.fields();
        while (it.hasNext()) {
            var field = it.next();
            int code = parseIntKey(field.getKey());
            if (code < 0) continue;
            codes.merge(code, Math.max(0, field.getValue().asDouble()), Double::sum);
        }
        return codes;
    }

    private JsonNode post(Map<String, Object> expression, String label, String token) {
        try {
            String url = baseUrl + "/v1/" + projectResource + "/value:compute";
            var request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(expression)))
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Dynamic World {} failed: HTTP {} {}", label, response.statusCode(), response.body());
                return null;
            }
            JsonNode root = mapper.readTree(response.body());
            return root.has("result") ? root.path("result") : root;
        } catch (Exception e) {
            log.warn("Dynamic World {} errored: {}", label, e.toString());
            return null;
        }
    }

    // ── Assembly ─────────────────────────────────────────────────────────────

    private LandCoverData assemble(TreeMap<Integer, YearStat> perYear,
                                   double parcelAcres,
                                   List<LandCoverData.Transition> transitions) {
        int firstYear = perYear.firstKey();
        int lastYear = perYear.lastKey();
        Map<String, Double> latest = perYear.get(lastYear).percentByClass();
        Map<String, Double> earliest = perYear.get(firstYear).percentByClass();

        List<LandCoverData.ClassShare> composition = shares(latest, parcelAcres);

        List<LandCoverData.YearlyComposition> history = new ArrayList<>();
        perYear.forEach((year, stat) -> history.add(new LandCoverData.YearlyComposition(
            String.valueOf(year), shares(stat.percentByClass(), parcelAcres))));

        List<LandCoverData.ClassChange> changes = new ArrayList<>();
        for (String cls : DynamicWorldExpression.CLASSES) {
            double start = earliest.getOrDefault(cls, 0.0);
            double end = latest.getOrDefault(cls, 0.0);
            if (start < 0.5 && end < 0.5) continue;
            changes.add(new LandCoverData.ClassChange(display(cls), round1(start), round1(end), round1(end - start)));
        }
        changes.sort(Comparator.comparingDouble(
            (LandCoverData.ClassChange c) -> Math.abs(c.deltaPercent())).reversed());

        String trend = describeTrend(earliest, latest, firstYear, lastYear, transitions);
        double confidentPercent = round1(perYear.get(lastYear).confidentFraction * 100);
        double observationCount = round1(perYear.get(lastYear).obsCount);

        return new LandCoverData(
            true, DynamicWorldExpression.ASSET_ID, probabilityThreshold, confidentPercent,
            round1(parcelAcres), observationCount, String.valueOf(lastYear),
            composition, history, changes, transitions, trend);
    }

    private List<LandCoverData.ClassShare> shares(Map<String, Double> percentByClass, double parcelAcres) {
        List<LandCoverData.ClassShare> list = new ArrayList<>();
        for (String cls : DynamicWorldExpression.CLASSES) {
            double pct = percentByClass.getOrDefault(cls, 0.0);
            if (pct <= 0) continue;
            double acres = pct / 100.0 * parcelAcres;
            list.add(new LandCoverData.ClassShare(
                display(cls), round1(pct), round2(acres), round2(acres / ACRES_PER_HECTARE)));
        }
        list.sort(Comparator.comparingDouble(LandCoverData.ClassShare::percent).reversed());
        return list;
    }

    private List<LandCoverData.Transition> transitions(Map<Integer, Double> codes, double parcelAcres) {
        double total = codes.values().stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) return List.of();
        int n = DynamicWorldExpression.CLASSES.size();

        List<LandCoverData.Transition> list = new ArrayList<>();
        for (var entry : codes.entrySet()) {
            int from = entry.getKey() / 10;
            int to = entry.getKey() % 10;
            if (from == to || from < 0 || from >= n || to < 0 || to >= n) continue;
            double pct = entry.getValue() / total * 100.0;
            if (pct < 0.5) continue;
            list.add(new LandCoverData.Transition(
                display(DynamicWorldExpression.CLASSES.get(from)),
                display(DynamicWorldExpression.CLASSES.get(to)),
                round2(pct / 100.0 * parcelAcres), round1(pct)));
        }
        list.sort(Comparator.comparingDouble(LandCoverData.Transition::percent).reversed());
        return list;
    }

    private String describeTrend(Map<String, Double> start, Map<String, Double> end,
                                 int firstYear, int lastYear, List<LandCoverData.Transition> transitions) {
        double vegStart = VEGETATION.stream().mapToDouble(c -> start.getOrDefault(c, 0.0)).sum();
        double vegEnd = VEGETATION.stream().mapToDouble(c -> end.getOrDefault(c, 0.0)).sum();
        double vegDelta = vegEnd - vegStart;
        double bareDelta = end.getOrDefault("bare", 0.0) - start.getOrDefault("bare", 0.0);
        double builtDelta = end.getOrDefault("built", 0.0) - start.getOrDefault("built", 0.0);
        double waterDelta = end.getOrDefault("water", 0.0) - start.getOrDefault("water", 0.0);

        List<String> parts = new ArrayList<>();
        if (vegDelta >= 2) parts.add("becoming more vegetated");
        else if (vegDelta <= -2) parts.add("losing vegetation cover");
        else parts.add("vegetation cover is broadly stable");
        if (bareDelta <= -1) parts.add("less bare ground");
        else if (bareDelta >= 1) parts.add("more bare ground");
        if (builtDelta >= 1) parts.add("expanding built area");
        if (Math.abs(waterDelta) >= 1) parts.add(waterDelta > 0 ? "more standing water" : "less standing water");

        String dominantStart = dominant(start);
        String dominantEnd = dominant(end);
        String lead = dominantStart.equals(dominantEnd)
            ? "%s remains the dominant cover".formatted(display(dominantEnd))
            : "dominant cover shifted from %s to %s".formatted(display(dominantStart), display(dominantEnd));

        String tail = "";
        if (!transitions.isEmpty()) {
            var top = transitions.get(0);
            tail = " Largest shift: %s → %s (%.1f%%).".formatted(top.fromClass(), top.toClass(), top.percent());
        }
        return "%d→%d: %s; %s (%+.1f pts vegetation).%s".formatted(
            firstYear, lastYear, lead, String.join(", ", parts), round1(vegDelta), tail);
    }

    private static String dominant(Map<String, Double> comp) {
        return DynamicWorldExpression.CLASSES.stream()
            .max(Comparator.comparingDouble(c -> comp.getOrDefault(c, 0.0)))
            .orElse("trees");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Per-year normalised class composition (%), mean observations/pixel, confident-classification share (0..1). */
    record YearStat(Map<String, Double> percentByClass, double obsCount, double confidentFraction) {}

    private static String display(String cls) {
        return DISPLAY_NAMES.getOrDefault(cls, cls);
    }

    private static int parseIntKey(String key) {
        try {
            return (int) Math.round(Double.parseDouble(key.trim()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double parcelAcres(Patch patch) {
        BigDecimal a = patch.getAreaAcres();
        return a != null && a.doubleValue() > 0 ? a.doubleValue() : DEFAULT_PARCEL_ACRES;
    }

    private static List<List<Double>> exteriorRing(Patch patch) {
        if (patch.getBoundary() == null) return List.of();
        List<List<Double>> ring = new ArrayList<>();
        for (Coordinate c : patch.getBoundary().getExteriorRing().getCoordinates()) {
            ring.add(List.of(c.x, c.y)); // [lng, lat]
        }
        return ring;
    }

    private static String trimTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String normalizeProject(String geeProject) {
        if (geeProject == null || geeProject.isBlank()) return null;
        String p = geeProject.trim();
        return p.startsWith("projects/") ? p : "projects/" + p;
    }

    private static List<Integer> parseYears(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<Integer> years = new ArrayList<>();
        for (String part : csv.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            try {
                years.add(Integer.parseInt(t));
            } catch (NumberFormatException ignored) {
                // skip malformed entries
            }
        }
        years.sort(Comparator.naturalOrder());
        return years;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
