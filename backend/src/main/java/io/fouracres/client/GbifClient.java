package io.fouracres.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.dto.BiodiversityData;
import io.fouracres.model.Patch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
public class GbifClient {
    private static final Set<String> THREATENED = Set.of("VU", "EN", "CR");
    private final HttpClient httpClient;
    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public GbifClient(HttpClient httpClient,
                      @Value("${app.gbif.base-url}") String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
    }

    public BiodiversityData fetch(Patch patch) {
        String url = "%s/occurrence/search?decimalLatitude=%s&decimalLongitude=%s&radius=120&limit=300&hasCoordinate=true"
            .formatted(baseUrl, patch.getCenterLat(), patch.getCenterLng());
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parse(response.body());
        } catch (Exception e) {
            return new BiodiversityData(0, List.of(), 0);
        }
    }

    private BiodiversityData parse(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        JsonNode results = root.path("results");

        Map<String, String> speciesKingdom = new LinkedHashMap<>();
        Map<String, Long> speciesCount = new LinkedHashMap<>();
        int threatened = 0;

        for (JsonNode r : results) {
            String species = r.path("species").asText(null);
            if (species == null || species.isBlank()) continue;
            String kingdom = r.path("kingdom").asText("Unknown");
            speciesKingdom.putIfAbsent(species, kingdom);
            speciesCount.merge(species, 1L, Long::sum);
            String iucn = r.path("iucnRedListCategory").asText("");
            if (THREATENED.contains(iucn)) threatened++;
        }

        var top5 = speciesCount.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(5)
            .map(e -> new BiodiversityData.SpeciesEntry(e.getKey(), speciesKingdom.getOrDefault(e.getKey(), "Unknown")))
            .toList();

        return new BiodiversityData(speciesKingdom.size(), top5, threatened);
    }
}
