package io.fouracres.client;

import io.fouracres.dto.SoilData;
import io.fouracres.model.Patch;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class SoilGridsClientTest {

    // SoilGrids stores: soc as g/kg×10, phh2o as pH×10, clay as %×10
    private static final String SOILGRIDS_RESPONSE = """
        {
          "properties": {
            "layers": [
              {
                "name": "soc",
                "depths": [{"label":"0-5cm","values":{"mean":215}}]
              },
              {
                "name": "phh2o",
                "depths": [{"label":"0-5cm","values":{"mean":57}}]
              },
              {
                "name": "clay",
                "depths": [{"label":"0-5cm","values":{"mean":324}}]
              }
            ]
          }
        }
        """;

    @Test
    void fetch_dividesByTenAndMapsFields() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        var response = Mockito.mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(SOILGRIDS_RESPONSE);
        when(httpClient.send(any(), any())).thenReturn(response);

        var client = new SoilGridsClient(httpClient, "https://rest.soilgrids.org");
        var patch = Mockito.mock(Patch.class);
        when(patch.getCenterLat()).thenReturn(BigDecimal.valueOf(-3.4653));
        when(patch.getCenterLng()).thenReturn(BigDecimal.valueOf(-62.2159));

        SoilData result = client.fetch(patch);

        assertThat(result.organicCarbonGKg()).isCloseTo(21.5, within(0.01));
        assertThat(result.ph()).isCloseTo(5.7, within(0.01));
        assertThat(result.clayPercent()).isCloseTo(32.4, within(0.01));
    }
}
