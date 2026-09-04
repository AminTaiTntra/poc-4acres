package io.fouracres.client;

import io.fouracres.dto.BiodiversityData;
import io.fouracres.model.Patch;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class GbifClientTest {

    private static final String GBIF_RESPONSE = """
        {
          "results": [
            {"species": "Panthera onca", "kingdom": "Animalia", "iucnRedListCategory": "VU"},
            {"species": "Panthera onca", "kingdom": "Animalia", "iucnRedListCategory": "VU"},
            {"species": "Ara macao", "kingdom": "Animalia", "iucnRedListCategory": null},
            {"species": "Heliconia bihai", "kingdom": "Plantae", "iucnRedListCategory": null},
            {"species": "Tapirus terrestris", "kingdom": "Animalia", "iucnRedListCategory": "VU"},
            {"species": "Morpho menelaus", "kingdom": "Animalia", "iucnRedListCategory": null},
            {"species": "Cedrela odorata", "kingdom": "Plantae", "iucnRedListCategory": "VU"}
          ]
        }
        """;

    @Test
    void fetch_parsesSpeciesCountAndThreatenedCount() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        var response = Mockito.mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(GBIF_RESPONSE);
        when(httpClient.send(any(), any())).thenReturn(response);

        var client = new GbifClient(httpClient, "https://api.gbif.org/v1");
        var patch = mockPatch(-3.4653, -62.2159);

        BiodiversityData result = client.fetch(patch);

        assertThat(result.speciesCount()).isEqualTo(6); // 6 distinct species
        assertThat(result.threatenedCount()).isEqualTo(4); // VU records
        assertThat(result.topSpecies()).hasSize(5);
        assertThat(result.topSpecies().get(0).name()).isEqualTo("Panthera onca"); // highest count
    }

    private Patch mockPatch(double lat, double lng) {
        var patch = Mockito.mock(Patch.class);
        when(patch.getCenterLat()).thenReturn(BigDecimal.valueOf(lat));
        when(patch.getCenterLng()).thenReturn(BigDecimal.valueOf(lng));
        return patch;
    }
}
