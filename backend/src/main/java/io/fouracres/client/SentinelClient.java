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
