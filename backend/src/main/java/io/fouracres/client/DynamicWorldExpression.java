package io.fouracres.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds serialized Earth Engine {@code value:compute} expression graphs for the
 * Land Cover insight, using only read-only compute (no {@code maps.create} / no EE writes).
 *
 * <p>Two graphs:</p>
 * <ul>
 *   <li>{@link #yearComposition} — for one year: the mean Dynamic World class-probability
 *       composition (9 classes, sums to ~1) over the polygon, plus the mean number of
 *       cloud-free observations per pixel and the share of pixels whose top mean class
 *       probability is ≥ the recommended confident-classification threshold.</li>
 *   <li>{@link #transition} — between two years: {@code frequencyHistogram} of the code
 *       {@code fromLabel*10 + toLabel} — a class-to-class change matrix.</li>
 * </ul>
 *
 * <p>Function identifiers are Earth Engine Cloud API algorithm names, verified against the
 * live {@code /v1/projects/*}/algorithms} registry. The Cloud API has no
 * {@code filterDate}/{@code filterBounds}: date scoping is {@code Collection.filter} +
 * {@code Filter.calendarRange}; spatial scoping is the {@code reduceRegion} geometry.</p>
 */
final class DynamicWorldExpression {

    static final String ASSET_ID = "GOOGLE/DYNAMICWORLD/V1";

    /** Dynamic World classes in canonical order — index == {@code label} band value 0..8. */
    static final List<String> CLASSES = List.of(
        "water", "trees", "grass", "flooded_vegetation", "crops",
        "shrub_and_scrub", "built", "bare", "snow_and_ice");

    static final String CONFIDENT_FRACTION = "confident_fraction";
    static final String OBSERVATIONS = "observations";

    private final Map<String, Object> values = new LinkedHashMap<>();
    private int seq = 0;

    private DynamicWorldExpression() {}

    // ── mean class-probability composition for one year ───────────────────────

    /**
     * Result: {@code {<class>: meanProbability, "observations": meanObsPerPixel,
     * "confident_fraction": 0..1}}.
     */
    static Map<String, Object> yearComposition(List<List<Double>> ring, int year, int scale, double threshold) {
        DynamicWorldExpression e = new DynamicWorldExpression();
        String geom = e.polygon(ring);
        String col = e.yearCollection(year);

        String mean = e.add(fn("reduce.mean", Map.of("collection", ref(col))));
        String probs = e.add(fn("Image.select", Map.of(
            "input", ref(mean), "bandSelectors", constant(CLASSES))));

        String top = e.add(fn("Image.reduce", Map.of(
            "image", ref(probs), "reducer", fn("Reducer.max", Map.of()))));
        String cut = e.add(fn("Image.constant", Map.of("value", constant(threshold))));
        String confident = e.add(fn("Image.select", Map.of(
            "input", fn("Image.gte", Map.of("image1", ref(top), "image2", ref(cut))),
            "bandSelectors", constant(List.of("max")),
            "newNames", constant(List.of(CONFIDENT_FRACTION)))));

        String obs = e.add(fn("Image.select", Map.of(
            "input", fn("reduce.count", Map.of("collection", ref(col))),
            "bandSelectors", constant(List.of("label")),
            "newNames", constant(List.of(OBSERVATIONS)))));

        String stacked = e.add(fn("Image.addBands", Map.of(
            "dstImg", fn("Image.addBands", Map.of("dstImg", ref(probs), "srcImg", ref(confident))),
            "srcImg", ref(obs))));

        String out = e.add(fn("Image.reduceRegion", Map.of(
            "image", ref(stacked),
            "reducer", fn("Reducer.mean", Map.of()),
            "geometry", ref(geom),
            "scale", constant(scale),
            "maxPixels", constant(10_000_000_000L),
            "bestEffort", constant(true))));

        return e.wrap(out);
    }

    // ── class-to-class transition matrix between two years ────────────────────

    /** Result: {@code {"label": {"<from*10+to>": pixelCount, ...}}}. */
    static Map<String, Object> transition(List<List<Double>> ring, int fromYear, int toYear, int scale) {
        DynamicWorldExpression e = new DynamicWorldExpression();
        String geom = e.polygon(ring);
        String colA = e.yearCollection(fromYear);
        String colB = e.yearCollection(toYear);

        String la = e.add(fn("Image.select", Map.of(
            "input", fn("reduce.mode", Map.of("collection", ref(colA))),
            "bandSelectors", constant(List.of("label")))));
        String lb = e.add(fn("Image.select", Map.of(
            "input", fn("reduce.mode", Map.of("collection", ref(colB))),
            "bandSelectors", constant(List.of("label")))));

        String code = e.add(fn("Image.add", Map.of(
            "image1", fn("Image.multiply", Map.of(
                "image1", ref(la),
                "image2", fn("Image.constant", Map.of("value", constant(10))))),
            "image2", ref(lb))));

        String out = e.add(fn("Image.reduceRegion", Map.of(
            "image", ref(code),
            "reducer", fn("Reducer.frequencyHistogram", Map.of()),
            "geometry", ref(geom),
            "scale", constant(scale),
            "maxPixels", constant(10_000_000_000L),
            "bestEffort", constant(true))));

        return e.wrap(out);
    }

    // ── shared graph fragments ───────────────────────────────────────────────

    private String polygon(List<List<Double>> ring) {
        return add(fn("GeometryConstructors.Polygon", Map.of(
            "coordinates", constant(List.of(ring)),
            "evenOdd", constant(true))));
    }

    private String yearCollection(int year) {
        String loaded = add(fn("ImageCollection.load", Map.of("id", constant(ASSET_ID))));
        String yearFilter = add(fn("Filter.calendarRange", Map.of(
            "start", constant(year),
            "end", constant(year),
            "field", constant("year"))));
        return add(fn("Collection.filter", Map.of(
            "collection", ref(loaded),
            "filter", ref(yearFilter))));
    }

    private Map<String, Object> wrap(String resultKey) {
        Map<String, Object> expression = new LinkedHashMap<>();
        expression.put("values", values);
        expression.put("result", resultKey);
        return Map.of("expression", expression);
    }

    private String add(Map<String, Object> node) {
        String key = String.valueOf(seq++);
        values.put(key, node);
        return key;
    }

    private static Map<String, Object> fn(String name, Map<String, Object> args) {
        Map<String, Object> inv = new LinkedHashMap<>();
        inv.put("functionName", name);
        inv.put("arguments", args);
        return Map.of("functionInvocationValue", inv);
    }

    private static Map<String, Object> ref(String key) {
        return Map.of("valueReference", key);
    }

    private static Map<String, Object> constant(Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("constantValue", value);
        return m;
    }
}
