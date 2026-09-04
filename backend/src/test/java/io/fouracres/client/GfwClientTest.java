package io.fouracres.client;

import io.fouracres.dto.CarbonData;
import io.fouracres.model.Patch;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class GfwClientTest {

    private static final String GFW_RESPONSE = """
        {
          "data": [{
            "umd_tree_cover_density_2020__threshold": 30,
            "umd_tree_cover_density_2020__ha": 1.4,
            "gfw_aboveground_carbon_stocks_2000__Mg_C_ha-1": 210.5,
            "umd_tree_cover_loss__ha": 0.08
          }]
        }
        """;

    @Test
    void fetch_mapsFieldsCorrectly() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        var response = Mockito.mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(GFW_RESPONSE);
        when(httpClient.send(any(), any())).thenReturn(response);

        var patch = Mockito.mock(Patch.class);
        when(patch.getGfwGeostoreId()).thenReturn("abc123");

        var client = new GfwClient(httpClient, "https://data-api.globalforestwatch.org", "test-key");
        CarbonData result = client.fetch(patch);

        assertThat(result.treeCoverPercent()).isCloseTo(86.5, within(2.0));
        assertThat(result.carbonDensityMgHa()).isCloseTo(210.5, within(0.1));
        assertThat(result.coverLossHa()).isCloseTo(0.08, within(0.001));
    }

    @Test
    void fetch_returnsZeroedData_whenGeostoreIdNull() {
        var httpClient = Mockito.mock(HttpClient.class);
        var patch = Mockito.mock(Patch.class);
        when(patch.getGfwGeostoreId()).thenReturn(null);

        var client = new GfwClient(httpClient, "https://data-api.globalforestwatch.org", "test-key");
        CarbonData result = client.fetch(patch);

        assertThat(result.treeCoverPercent()).isZero();
        assertThat(result.carbonDensityMgHa()).isZero();
        assertThat(result.coverLossHa()).isZero();
    }
}
