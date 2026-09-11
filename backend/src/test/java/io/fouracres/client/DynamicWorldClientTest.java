package io.fouracres.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.dto.LandCoverData;
import io.fouracres.model.Patch;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class DynamicWorldClientTest {

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);
    private static final String BASE = "https://earthengine.googleapis.com";

    /**
     * One canned value:compute result that satisfies both parsers:
     *  - year composition: mean class probs (trees .60 / shrub .14 / grass .08 / bare .06
     *    / crops .04 / water .02 / built .02 / flooded .02 / snow .02 → normalised),
     *    observations 25, confident_fraction 0.9
     *  - transition: label {11:150 (trees→trees), 71:30 (bare→trees), 77:20 (bare→bare)}
     */
    private static final String COMBINED = """
        { "result": {
            "trees": 0.60, "shrub_and_scrub": 0.14, "grass": 0.08, "bare": 0.06,
            "crops": 0.04, "water": 0.02, "built": 0.02, "flooded_vegetation": 0.02,
            "snow_and_ice": 0.02,
            "observations": 25.0,
            "confident_fraction": 0.9,
            "label": { "11": 150, "71": 30, "77": 20 }
        } }
        """;

    private Patch squarePatch(double acres) {
        Coordinate[] coords = {
            new Coordinate(-62.0000, -3.0000), new Coordinate(-61.9990, -3.0000),
            new Coordinate(-61.9990, -2.9990), new Coordinate(-62.0000, -2.9990),
            new Coordinate(-62.0000, -3.0000),
        };
        Polygon polygon = GF.createPolygon(GF.createLinearRing(coords), null);
        var patch = Mockito.mock(Patch.class);
        when(patch.getBoundary()).thenReturn(polygon);
        when(patch.getAreaAcres()).thenReturn(BigDecimal.valueOf(acres));
        return patch;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private HttpResponse ok(String body) {
        HttpResponse r = Mockito.mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(200);
        when(r.body()).thenReturn(body);
        return r;
    }

    private GoogleEarthEngineAuth authWithToken() {
        var auth = Mockito.mock(GoogleEarthEngineAuth.class);
        when(auth.accessToken()).thenReturn(Optional.of("test-token"));
        return auth;
    }

    private DynamicWorldClient client(HttpClient http, GoogleEarthEngineAuth auth, String years) {
        return new DynamicWorldClient(http, new ObjectMapper(), auth, BASE, "my-ee-project", 0.5, years);
    }

    // ── happy path ───────────────────────────────────────────────────────────

    @Test
    void fetch_buildsCompositionHistoryChangeAndTransitions() throws Exception {
        var http = Mockito.mock(HttpClient.class);
        var resp = ok(COMBINED);
        when(http.send(any(), any())).thenReturn(resp);

        LandCoverData d = client(http, authWithToken(), "2016,2020,2026").fetch(squarePatch(4.0));

        assertThat(d.hasData()).isTrue();
        assertThat(d.source()).isEqualTo("GOOGLE/DYNAMICWORLD/V1");
        assertThat(d.latestPeriod()).isEqualTo("2026");
        assertThat(d.parcelAcres()).isEqualTo(4.0);

        // composition — mean class-probability mix normalised to 100%, desc
        assertThat(d.composition().get(0).className()).isEqualTo("Trees");
        assertThat(d.composition().get(0).percent()).isCloseTo(60.0, within(0.5));   // 0.60 / 1.00
        assertThat(d.composition().get(1).className()).isEqualTo("Shrub & scrub");
        assertThat(d.composition().get(1).percent()).isCloseTo(14.0, within(0.5));
        assertThat(d.composition().stream().mapToDouble(LandCoverData.ClassShare::percent).sum())
            .isCloseTo(100.0, within(0.5));
        assertThat(d.composition().get(0).areaAcres()).isCloseTo(2.4, within(0.1));  // 60% of 4

        // history — one entry per requested year, ascending
        assertThat(d.history()).hasSize(3);
        assertThat(d.history().get(0).year()).isEqualTo("2016");
        assertThat(d.history().get(2).year()).isEqualTo("2026");
        assertThat(d.history().get(0).classes().get(0).className()).isEqualTo("Trees");

        // data-quality signals
        assertThat(d.confidentPercent()).isCloseTo(90.0, within(0.1));
        assertThat(d.observationCount()).isCloseTo(25.0, within(0.1));

        // transitions — 71 = Bare ground → Trees, 30 of 200 = 15%
        assertThat(d.transitions()).hasSize(1);
        assertThat(d.transitions().get(0).fromClass()).isEqualTo("Bare ground");
        assertThat(d.transitions().get(0).toClass()).isEqualTo("Trees");
        assertThat(d.transitions().get(0).percent()).isCloseTo(15.0, within(0.1));

        assertThat(d.trend()).contains("2016").contains("2026").contains("Bare ground → Trees");
    }

    @Test
    void fetch_scalesClassAreaToParcelSize() throws Exception {
        var http = Mockito.mock(HttpClient.class);
        var resp = ok(COMBINED);
        when(http.send(any(), any())).thenReturn(resp);

        LandCoverData d = client(http, authWithToken(), "2026").fetch(squarePatch(10.0));

        assertThat(d.parcelAcres()).isEqualTo(10.0);
        assertThat(d.composition().get(0).areaAcres()).isCloseTo(6.0, within(0.2));  // 60% of 10
        assertThat(d.transitions()).isEmpty();                                      // single year
    }

    // ── degradation ──────────────────────────────────────────────────────────

    @Test
    void fetch_returnsUnavailable_whenNotAuthenticated() {
        var auth = Mockito.mock(GoogleEarthEngineAuth.class);
        when(auth.accessToken()).thenReturn(Optional.empty());

        LandCoverData d = client(Mockito.mock(HttpClient.class), auth, "2026").fetch(squarePatch(4.0));

        assertThat(d.hasData()).isFalse();
        assertThat(d.source()).isEqualTo("UNAVAILABLE");
        assertThat(d.composition()).isEmpty();
        assertThat(d.transitions()).isEmpty();
    }

    @Test
    void fetch_returnsUnavailable_whenProjectBlank() {
        var client = new DynamicWorldClient(Mockito.mock(HttpClient.class), new ObjectMapper(),
            authWithToken(), BASE, "", 0.5, "2026");
        assertThat(client.fetch(squarePatch(4.0)).hasData()).isFalse();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void fetch_returnsUnavailable_whenEveryYearFails() throws Exception {
        var http = Mockito.mock(HttpClient.class);
        HttpResponse err = Mockito.mock(HttpResponse.class);
        when(err.statusCode()).thenReturn(400);
        when(err.body()).thenReturn("{\"error\":{\"code\":400,\"message\":\"bad expression\"}}");
        when(http.send(any(), any())).thenReturn(err);

        assertThat(client(http, authWithToken(), "2016,2026").fetch(squarePatch(4.0)).hasData()).isFalse();
    }

    // ── parser units ─────────────────────────────────────────────────────────

    private DynamicWorldClient parserClient() {
        return client(Mockito.mock(HttpClient.class), authWithToken(), "2026");
    }

    private JsonNode node(String json) throws Exception {
        return new ObjectMapper().readTree(json).path("result");
    }

    @Test
    void parseYearResult_normalisesProbabilitiesAndReadsQualitySignals() throws Exception {
        var stat = parserClient().parseYearResult(node(COMBINED));
        assertThat(stat).isNotNull();
        assertThat(stat.percentByClass().get("trees")).isCloseTo(60.0, within(0.5));
        assertThat(stat.percentByClass().values().stream().mapToDouble(Double::doubleValue).sum())
            .isCloseTo(100.0, within(0.5));
        assertThat(stat.obsCount()).isEqualTo(25.0);
        assertThat(stat.confidentFraction()).isCloseTo(0.9, within(0.001));
    }

    @Test
    void parseYearResult_nullWhenNoClassProbabilities() throws Exception {
        assertThat(parserClient().parseYearResult(node("{\"result\":{\"observations\":10}}"))).isNull();
    }

    @Test
    void parseTransitionResult_readsCrosstabCodes() throws Exception {
        var codes = parserClient().parseTransitionResult(node(COMBINED));
        assertThat(codes).containsEntry(11, 150.0).containsEntry(71, 30.0).containsEntry(77, 20.0);
    }

    // ── expression shape ─────────────────────────────────────────────────────

    @Test
    void yearCompositionExpression_targetsDynamicWorldWithYearFilterAndThreshold() {
        String json = serialize(DynamicWorldExpression.yearComposition(ring(), 2026, 10, 0.5));
        assertThat(json).contains("GOOGLE/DYNAMICWORLD/V1");
        assertThat(json).contains("Collection.filter").contains("Filter.calendarRange");
        assertThat(json).contains("\"start\":{\"constantValue\":2026}");
        assertThat(json).contains("reduce.mean").contains("reduce.count");
        assertThat(json).contains("confident_fraction").contains("observations");
        assertThat(json).contains("\"value\":{\"constantValue\":0.5}");
        assertThat(json).contains("Image.reduceRegion");
    }

    @Test
    void transitionExpression_encodesBothYears() {
        String json = serialize(DynamicWorldExpression.transition(ring(), 2016, 2026, 10));
        assertThat(json).contains("\"start\":{\"constantValue\":2016}");
        assertThat(json).contains("\"start\":{\"constantValue\":2026}");
        assertThat(json).contains("Image.multiply").contains("Image.add");
        assertThat(json).contains("Reducer.frequencyHistogram");
    }

    private static java.util.List<java.util.List<Double>> ring() {
        return java.util.List.of(
            java.util.List.of(-62.0, -3.0), java.util.List.of(-61.999, -3.0),
            java.util.List.of(-61.999, -2.999), java.util.List.of(-62.0, -2.999),
            java.util.List.of(-62.0, -3.0));
    }

    private static String serialize(Object graph) {
        try {
            return new ObjectMapper().writeValueAsString(graph);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
