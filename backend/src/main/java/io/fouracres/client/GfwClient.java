package io.fouracres.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.dto.CarbonData;
import io.fouracres.model.Patch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Component
public class GfwClient {
    private static final double ACRES_TO_HA = 0.404686;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public GfwClient(HttpClient httpClient,
                     @Value("${app.gfw.base-url}") String baseUrl,
                     @Value("${app.gfw.api-key}") String apiKey) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public CarbonData fetch(Patch patch) {
        if (patch.getGfwGeostoreId() == null) return new CarbonData(0, 0, 0);
        String url = "%s/dataset/umd_tree_cover_density_2020/latest/query?geostore_id=%s"
            .formatted(baseUrl, patch.getGfwGeostoreId());
        try {
            var request = HttpRequest.newBuilder(URI.create(url))
                .header("x-api-key", apiKey)
                .GET()
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parse(response.body());
        } catch (Exception e) {
            return new CarbonData(0, 0, 0);
        }
    }

    private CarbonData parse(String body) throws Exception {
        JsonNode data = mapper.readTree(body).path("data");
        if (!data.isArray() || data.isEmpty()) return new CarbonData(0, 0, 0);
        JsonNode row = data.get(0);
        double coverHa = row.path("umd_tree_cover_density_2020__ha").asDouble(0);
        double patchHa = 4 * ACRES_TO_HA;
        double treeCoverPercent = patchHa > 0 ? Math.min(100, (coverHa / patchHa) * 100) : 0;
        double carbonDensity = row.path("gfw_aboveground_carbon_stocks_2000__Mg_C_ha-1").asDouble(0);
        double coverLoss = row.path("umd_tree_cover_loss__ha").asDouble(0);
        return new CarbonData(treeCoverPercent, carbonDensity, coverLoss);
    }
}
