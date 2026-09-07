package io.fouracres.client;

import io.fouracres.dto.GeographicContextData;
import io.fouracres.model.Patch;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class MapboxGeocodingClientTest {

    private static final String FULL_RESPONSE = """
        {
          "features": [{
            "place_name": "Tulum, Quintana Roo, Mexico",
            "place_type": ["place"],
            "text": "Tulum",
            "context": [
              {"id": "region.123", "text": "Quintana Roo"},
              {"id": "country.456", "text": "Mexico"}
            ]
          }]
        }
        """;

    private static final String NEIGHBORHOOD_RESPONSE = """
        {
          "features": [{
            "place_name": "Condesa, Mexico City, Mexico City, Mexico",
            "place_type": ["neighborhood"],
            "text": "Condesa",
            "context": [
              {"id": "place.789", "text": "Mexico City"},
              {"id": "region.101", "text": "Mexico City"},
              {"id": "country.202", "text": "Mexico"}
            ]
          }]
        }
        """;

    @SuppressWarnings("unchecked")
    @Test
    void fetch_parsesPlaceWithRegionAndCountry() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        var response = Mockito.mock(HttpResponse.class);
        when(response.body()).thenReturn(FULL_RESPONSE);
        when(httpClient.send(any(), any())).thenReturn(response);

        var client = new MapboxGeocodingClient(httpClient, "https://api.mapbox.com", "test-token");
        GeographicContextData result = client.fetch(mockPatch(20.2114, -87.4654));

        assertThat(result.fullAddress()).isEqualTo("Tulum, Quintana Roo, Mexico");
        assertThat(result.placeName()).isEqualTo("Tulum");
        assertThat(result.city()).isEqualTo("Tulum");   // place_type is "place" → city = placeName
        assertThat(result.region()).isEqualTo("Quintana Roo");
        assertThat(result.country()).isEqualTo("Mexico");
        assertThat(result.neighborhood()).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void fetch_parsesNeighborhoodWithCity() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        var response = Mockito.mock(HttpResponse.class);
        when(response.body()).thenReturn(NEIGHBORHOOD_RESPONSE);
        when(httpClient.send(any(), any())).thenReturn(response);

        var client = new MapboxGeocodingClient(httpClient, "https://api.mapbox.com", "test-token");
        GeographicContextData result = client.fetch(mockPatch(19.4, -99.2));

        assertThat(result.neighborhood()).isEqualTo("Condesa");
        assertThat(result.city()).isEqualTo("Mexico City");
        assertThat(result.region()).isEqualTo("Mexico City");
        assertThat(result.country()).isEqualTo("Mexico");
    }

    @SuppressWarnings("unchecked")
    @Test
    void fetch_returnsDefault_onEmptyFeatures() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        var response = Mockito.mock(HttpResponse.class);
        when(response.body()).thenReturn("{\"features\":[]}");
        when(httpClient.send(any(), any())).thenReturn(response);

        var client = new MapboxGeocodingClient(httpClient, "https://api.mapbox.com", "test-token");
        GeographicContextData result = client.fetch(mockPatch(0, 0));

        assertThat(result.fullAddress()).isEqualTo("Unknown");
        assertThat(result.neighborhood()).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void fetch_returnsDefault_onHttpError() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        when(httpClient.send(any(), any())).thenThrow(new java.io.IOException("timeout"));

        var client = new MapboxGeocodingClient(httpClient, "https://api.mapbox.com", "test-token");
        GeographicContextData result = client.fetch(mockPatch(0, 0));

        assertThat(result.fullAddress()).isEqualTo("Unknown");
    }

    private Patch mockPatch(double lat, double lng) {
        var patch = Mockito.mock(Patch.class);
        when(patch.getCenterLat()).thenReturn(BigDecimal.valueOf(lat));
        when(patch.getCenterLng()).thenReturn(BigDecimal.valueOf(lng));
        return patch;
    }
}
