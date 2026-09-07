package io.fouracres.client;

import io.fouracres.dto.SatelliteSceneData;
import io.fouracres.model.Patch;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class SentinelClientTest {

    private static final String SCENE_RESPONSE = """
        {
          "features": [{
            "properties": {
              "datetime": "2026-09-05T10:23:45.000Z",
              "eo:cloud_cover": 12.4,
              "s2:product_type": "S2MSI2A"
            },
            "links": [
              {"rel": "self", "href": "https://catalogue.example.com/item"},
              {"rel": "thumbnail", "href": "https://catalogue.example.com/quicklook.jpg"}
            ]
          }]
        }
        """;

    @SuppressWarnings("unchecked")
    @Test
    void fetch_parsesLatestScene() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        var response   = Mockito.mock(HttpResponse.class);
        when(response.body()).thenReturn(SCENE_RESPONSE);
        when(httpClient.send(any(), any())).thenReturn(response);

        var client = new SentinelClient(httpClient, "https://catalogue.dataspace.copernicus.eu");
        SatelliteSceneData result = client.fetch(mockPatch(20.2114, -87.4654));

        assertThat(result.hasRecentScene()).isTrue();
        assertThat(result.latestSceneDate()).isEqualTo("2026-09-05T10:23:45.000Z");
        assertThat(result.cloudCoverPercent()).isEqualTo(12.4);
        assertThat(result.productType()).isEqualTo("S2MSI2A");
        assertThat(result.thumbnailUrl()).isEqualTo("https://catalogue.example.com/quicklook.jpg");
    }

    @SuppressWarnings("unchecked")
    @Test
    void fetch_returnsNoScene_onEmptyFeatures() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        var response   = Mockito.mock(HttpResponse.class);
        when(response.body()).thenReturn("{\"features\":[]}");
        when(httpClient.send(any(), any())).thenReturn(response);

        var client = new SentinelClient(httpClient, "https://catalogue.dataspace.copernicus.eu");
        SatelliteSceneData result = client.fetch(mockPatch(0, 0));

        assertThat(result.hasRecentScene()).isFalse();
        assertThat(result.latestSceneDate()).isNull();
        assertThat(result.cloudCoverPercent()).isEqualTo(0.0);
    }

    @SuppressWarnings("unchecked")
    @Test
    void fetch_returnsNoScene_onHttpError() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        when(httpClient.send(any(), any())).thenThrow(new java.io.IOException("timeout"));

        var client = new SentinelClient(httpClient, "https://catalogue.dataspace.copernicus.eu");
        SatelliteSceneData result = client.fetch(mockPatch(0, 0));

        assertThat(result.hasRecentScene()).isFalse();
    }

    private Patch mockPatch(double lat, double lng) {
        var patch = Mockito.mock(Patch.class);
        when(patch.getCenterLat()).thenReturn(BigDecimal.valueOf(lat));
        when(patch.getCenterLng()).thenReturn(BigDecimal.valueOf(lng));
        return patch;
    }
}
