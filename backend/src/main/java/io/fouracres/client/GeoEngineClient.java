package io.fouracres.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.dto.TerrainData;
import io.fouracres.dto.WaterData;
import io.fouracres.model.Patch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Component
public class GeoEngineClient {
    private static final Logger log = LoggerFactory.getLogger(GeoEngineClient.class);

    private static final double ACRES_TO_M2 = 4046.86;
    private static final double PATCH_RADIUS_M = Math.sqrt((4 * ACRES_TO_M2) / Math.PI);

    private static final WaterData DEFAULT_WATER =
        new WaterData(0, "None", 0, "1984-2021", 0, null, 180);
    private static final TerrainData DEFAULT_TERRAIN =
        new TerrainData(0, 0, 0, 0, "Unknown");

    private final HttpClient httpClient;
    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public GeoEngineClient(HttpClient httpClient,
                            @Value("${app.geoengine.base-url}") String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
    }

    public WaterData fetchWater(Patch patch) {
        String url = "%s/water?lat=%s&lng=%s&radiusM=%s"
            .formatted(baseUrl, patch.getCenterLat(), patch.getCenterLng(), PATCH_RADIUS_M);
        try {
            HttpResponse<String> response = sendWithRetry(url);
            if (response.statusCode() != 200) {
                log.warn("Water fetch failed for patch {}: HTTP {} — {}",
                    patch.getId(), response.statusCode(), response.body());
                return DEFAULT_WATER;
            }
            JsonNode body = mapper.readTree(response.body());
            JsonNode latestSceneNode = body.path("latestSceneDate");
            String latestSceneDate = (latestSceneNode.isMissingNode() || latestSceneNode.isNull())
                ? null : latestSceneNode.asText();
            return new WaterData(
                body.path("surfaceWaterHa").asDouble(0),
                body.path("occurrenceClass").asText("None"),
                body.path("recurrencePercent").asDouble(0),
                body.path("period").asText("1984-2021"),
                body.path("currentWaterPercent").asDouble(0),
                latestSceneDate,
                body.path("currentWindowDays").asInt(180)
            );
        } catch (Exception e) {
            log.warn("Water fetch failed for patch {}: {}", patch.getId(), e.toString());
            return DEFAULT_WATER;
        }
    }

    public TerrainData fetchTerrain(Patch patch) {
        String url = "%s/terrain?lat=%s&lng=%s&radiusM=%s"
            .formatted(baseUrl, patch.getCenterLat(), patch.getCenterLng(), PATCH_RADIUS_M);
        try {
            HttpResponse<String> response = sendWithRetry(url);
            if (response.statusCode() != 200) {
                log.warn("Terrain fetch failed for patch {}: HTTP {} — {}",
                    patch.getId(), response.statusCode(), response.body());
                return DEFAULT_TERRAIN;
            }
            JsonNode body = mapper.readTree(response.body());
            return new TerrainData(
                body.path("elevationMinM").asDouble(0),
                body.path("elevationMaxM").asDouble(0),
                body.path("avgSlopeDeg").asDouble(0),
                body.path("maxSlopeDeg").asDouble(0),
                body.path("terrainClass").asText("Unknown")
            );
        } catch (Exception e) {
            log.warn("Terrain fetch failed for patch {}: {}", patch.getId(), e.toString());
            return DEFAULT_TERRAIN;
        }
    }

    /**
     * Water and terrain are the only two of this app's outbound calls that hit the
     * same host (the frontend's Next.js server) at the same instant — InsightsService
     * fires both in parallel on the same shared HttpClient. That triggers a known
     * java.net.http.HttpClient race: a pooled HTTP/1.1 connection can be handed out
     * for reuse just as the server closes it, producing an IOException ("header
     * parser received no bytes") for a request that never actually reached the
     * server. It's a benign, transient race rather than a real failure, so retry
     * once on a fresh connection before giving up.
     */
    private HttpResponse<String> sendWithRetry(String url) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
