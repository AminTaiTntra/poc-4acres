package io.fouracres.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.dto.VegetationData;
import io.fouracres.model.Patch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NdviClientTest {

    @Mock HttpClient httpClient;
    @Mock HttpResponse<String> httpResponse;

    NdviClient client;

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    @BeforeEach
    void setUp() {
        client = new NdviClient(httpClient, "http://localhost:8001");
    }

    private Patch patchWithBoundary() {
        Coordinate[] coords = {
            new Coordinate(77.0, 20.0), new Coordinate(77.1, 20.0),
            new Coordinate(77.1, 20.1), new Coordinate(77.0, 20.1),
            new Coordinate(77.0, 20.0)
        };
        Polygon polygon = GF.createPolygon(coords);
        Patch patch = new Patch();
        patch.setBoundary(polygon);
        return patch;
    }

    @Test
    void fetch_returnsVegetationData_onSuccess() throws Exception {
        String json = """
            {"yearly":[{"year":2022,"ndvi":0.61,"valid_pixel_pct":78.3,"observation_date":"2022-10-15"}],
             "current_ndvi":0.61,"baseline_ndvi":0.61,"change_pct":0.0,"trend":"stable",
             "condition":"good","last_observation":"2022-10-15","resolution_m":10,"source":"Sentinel-2"}
            """;
        when(httpClient.<String>send(any(), any())).thenReturn(httpResponse);
        when(httpResponse.body()).thenReturn(json);

        VegetationData result = client.fetch(patchWithBoundary());

        assertThat(result).isNotNull();
        assertThat(result.currentNdvi()).isEqualTo(0.61);
        assertThat(result.trend()).isEqualTo("stable");
        assertThat(result.yearly()).hasSize(1);
    }

    @Test
    void fetch_returnsNull_onIOException() throws Exception {
        when(httpClient.send(any(), any())).thenThrow(new IOException("Connection refused"));

        VegetationData result = client.fetch(patchWithBoundary());

        assertThat(result).isNull();
    }
}
