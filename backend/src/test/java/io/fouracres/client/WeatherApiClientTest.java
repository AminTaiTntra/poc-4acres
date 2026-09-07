package io.fouracres.client;

import io.fouracres.dto.WeatherData;
import io.fouracres.model.Patch;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class WeatherApiClientTest {

    private static final String WEATHER_RESPONSE = """
        {
          "current": {
            "temp_c": 22.5,
            "condition": {"text": "Partly cloudy", "icon": "//cdn.weatherapi.com/weather/64x64/day/116.png"},
            "wind_kph": 15.3,
            "humidity": 68,
            "uv": 6.0
          },
          "forecast": {
            "forecastday": [
              {"date": "2026-09-07", "day": {"maxtemp_c": 26.0, "mintemp_c": 18.0, "condition": {"text": "Sunny"}}},
              {"date": "2026-09-08", "day": {"maxtemp_c": 24.0, "mintemp_c": 17.0, "condition": {"text": "Cloudy"}}},
              {"date": "2026-09-09", "day": {"maxtemp_c": 22.0, "mintemp_c": 16.0, "condition": {"text": "Rain"}}},
              {"date": "2026-09-10", "day": {"maxtemp_c": 23.0, "mintemp_c": 17.0, "condition": {"text": "Sunny"}}},
              {"date": "2026-09-11", "day": {"maxtemp_c": 25.0, "mintemp_c": 18.0, "condition": {"text": "Partly cloudy"}}},
              {"date": "2026-09-12", "day": {"maxtemp_c": 27.0, "mintemp_c": 19.0, "condition": {"text": "Sunny"}}},
              {"date": "2026-09-13", "day": {"maxtemp_c": 26.0, "mintemp_c": 18.0, "condition": {"text": "Partly cloudy"}}}
            ]
          }
        }
        """;

    @SuppressWarnings("unchecked")
    @Test
    void fetch_parsesCurrentWeatherAndForecast() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        var response   = Mockito.mock(HttpResponse.class);
        when(response.body()).thenReturn(WEATHER_RESPONSE);
        when(httpClient.send(any(), any())).thenReturn(response);

        var client = new WeatherApiClient(httpClient, "https://api.weatherapi.com", "test-key");
        WeatherData result = client.fetch(mockPatch(-3.4653, -62.2159));

        assertThat(result.tempC()).isEqualTo(22.5);
        assertThat(result.condition()).isEqualTo("Partly cloudy");
        assertThat(result.windKph()).isEqualTo(15.3);
        assertThat(result.humidity()).isEqualTo(68);
        assertThat(result.forecast()).hasSize(7);
        assertThat(result.forecast().get(0).date()).isEqualTo("2026-09-07");
        assertThat(result.forecast().get(0).maxTempC()).isEqualTo(26.0);
        assertThat(result.forecast().get(0).minTempC()).isEqualTo(18.0);
    }

    @SuppressWarnings("unchecked")
    @Test
    void fetch_prefixesIconUrlWithHttps() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        var response   = Mockito.mock(HttpResponse.class);
        when(response.body()).thenReturn(WEATHER_RESPONSE);
        when(httpClient.send(any(), any())).thenReturn(response);

        var client = new WeatherApiClient(httpClient, "https://api.weatherapi.com", "test-key");
        WeatherData result = client.fetch(mockPatch(0, 0));

        assertThat(result.conditionIconUrl()).startsWith("https://cdn.weatherapi.com");
    }

    @SuppressWarnings("unchecked")
    @Test
    void fetch_returnsDefault_onHttpError() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        when(httpClient.send(any(), any())).thenThrow(new IOException("connection refused"));

        var client = new WeatherApiClient(httpClient, "https://api.weatherapi.com", "test-key");
        WeatherData result = client.fetch(mockPatch(0, 0));

        assertThat(result.condition()).isEqualTo("Unavailable");
        assertThat(result.forecast()).isEmpty();
    }

    private Patch mockPatch(double lat, double lng) {
        var patch = Mockito.mock(Patch.class);
        when(patch.getCenterLat()).thenReturn(BigDecimal.valueOf(lat));
        when(patch.getCenterLng()).thenReturn(BigDecimal.valueOf(lng));
        return patch;
    }
}
