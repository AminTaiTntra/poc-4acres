package io.fouracres.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.dto.VegetationData;
import io.fouracres.model.Patch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Year;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Component
public class NdviClient {

    private final HttpClient httpClient;
    private final String serviceUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public NdviClient(HttpClient httpClient,
                      @Value("${app.ndvi.service-url}") String serviceUrl) {
        this.httpClient = httpClient;
        this.serviceUrl = serviceUrl;
    }

    public VegetationData fetch(Patch patch) {
        try {
            List<List<Double>> ring = Arrays.stream(patch.getBoundary().getCoordinates())
                    .map(c -> List.of(c.x, c.y))
                    .toList();
            Map<String, Object> polygon = Map.of(
                    "type", "Polygon",
                    "coordinates", List.of(ring)
            );
            Map<String, Object> body = Map.of(
                    "polygon", polygon,
                    "season_start_month", patch.getSeasonStartMonth(),
                    "season_end_month", patch.getSeasonEndMonth(),
                    "year_start", 2022,
                    "year_end", Year.now().getValue()
            );

            var request = HttpRequest.newBuilder(URI.create(serviceUrl + "/ndvi"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return mapper.readValue(response.body(), VegetationData.class);
        } catch (Exception e) {
            return null;
        }
    }
}
