package io.fouracres.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.dto.WeatherData;
import io.fouracres.model.Patch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

@Component
public class WeatherApiClient {

    private static final WeatherData DEFAULT =
        new WeatherData(0, "Unavailable", "", 0, 0, 0, List.of());

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public WeatherApiClient(HttpClient httpClient,
                             @Value("${app.weatherapi.base-url}") String baseUrl,
                             @Value("${app.weatherapi.api-key}") String apiKey) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public WeatherData fetch(Patch patch) {
        String url = "%s/v1/forecast.json?key=%s&q=%s,%s&days=7&aqi=no&alerts=no"
            .formatted(baseUrl, apiKey,
                       patch.getCenterLat().toPlainString(),
                       patch.getCenterLng().toPlainString());
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parse(mapper.readTree(response.body()));
        } catch (Exception e) {
            return DEFAULT;
        }
    }

    private WeatherData parse(JsonNode root) {
        JsonNode current = root.path("current");
        double tempC    = current.path("temp_c").asDouble(0);
        String cond     = current.path("condition").path("text").asText("Unknown");
        String icon     = current.path("condition").path("icon").asText("");
        if (icon.startsWith("//")) icon = "https:" + icon;
        double wind     = current.path("wind_kph").asDouble(0);
        int    humidity = current.path("humidity").asInt(0);
        double uv       = current.path("uv").asDouble(0);

        List<WeatherData.ForecastDay> forecast = new ArrayList<>();
        for (JsonNode day : root.path("forecast").path("forecastday")) {
            String date   = day.path("date").asText("");
            double maxC   = day.path("day").path("maxtemp_c").asDouble(0);
            double minC   = day.path("day").path("mintemp_c").asDouble(0);
            String dayC   = day.path("day").path("condition").path("text").asText("");
            forecast.add(new WeatherData.ForecastDay(date, maxC, minC, dayC));
        }

        return new WeatherData(tempC, cond, icon, wind, humidity, uv, forecast);
    }
}
