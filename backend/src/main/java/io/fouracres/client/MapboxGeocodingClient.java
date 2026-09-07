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
