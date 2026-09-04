package io.fouracres.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.dto.SoilData;
import io.fouracres.model.Patch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

@Component
public class SoilGridsClient {
    private final HttpClient httpClient;
    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public SoilGridsClient(HttpClient httpClient,
                           @Value("${app.soilgrids.base-url}") String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
    }

    public SoilData fetch(Patch patch) {
        String url = "%s/soilgrids/v2.0/properties/query?lon=%s&lat=%s&property=soc,phh2o,clay&depth=0-5cm&value=mean"
            .formatted(baseUrl, patch.getCenterLng(), patch.getCenterLat());
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parse(response.body());
        } catch (Exception e) {
            return new SoilData(0, 0, 0);
        }
    }

    private SoilData parse(String body) throws Exception {
        JsonNode layers = mapper.readTree(body).path("properties").path("layers");
        Map<String, Double> values = new HashMap<>();
        for (JsonNode layer : layers) {
            String name = layer.path("name").asText();
            JsonNode mean = layer.path("depths").get(0).path("values").path("mean");
            if (!mean.isMissingNode()) values.put(name, mean.asDouble());
        }
        double soc = values.getOrDefault("soc", 0.0) / 10.0;
        double ph  = values.getOrDefault("phh2o", 0.0) / 10.0;
        double clay = values.getOrDefault("clay", 0.0) / 10.0;
        return new SoilData(soc, ph, clay);
    }
}
