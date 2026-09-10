package io.fouracres.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.client.NdviClient;
import io.fouracres.dto.VegetationData;
import io.fouracres.model.InsightsCache;
import io.fouracres.model.Patch;
import io.fouracres.repository.InsightsCacheRepository;
import io.fouracres.repository.PatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.Coordinate;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VegetationServiceTest {

    @Mock PatchRepository patchRepository;
    @Mock InsightsCacheRepository cacheRepository;
    @Mock NdviClient ndviClient;

    VegetationService service;

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    @BeforeEach
    void setUp() {
        service = new VegetationService(patchRepository, cacheRepository, ndviClient, new ObjectMapper());
    }

    private Patch mockPatch(UUID id) {
        Patch patch = new Patch();
        patch.setId(id);
        var coords = new Coordinate[]{
            new Coordinate(77.0, 20.0), new Coordinate(77.1, 20.0),
            new Coordinate(77.1, 20.1), new Coordinate(77.0, 20.1),
            new Coordinate(77.0, 20.0)
        };
        patch.setBoundary(GF.createPolygon(coords));
        return patch;
    }

    @Test
    void getVegetation_returnsCachedData_withoutCallingNdviClient() throws Exception {
        UUID id = UUID.randomUUID();
        var vegData = new VegetationData(List.of(), 0.72, 0.64, 12.5, "improving", "good",
                "2026-10-15", 10, "Sentinel-2");
        String payload = new ObjectMapper().writeValueAsString(vegData);

        var cached = new InsightsCache(id, "VEGETATION", payload, Instant.now().plusSeconds(3600));
        when(cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(eq(id), eq("VEGETATION"), any()))
                .thenReturn(Optional.of(cached));

        VegetationData result = service.getVegetation(id);

        assertThat(result).isNotNull();
        assertThat(result.currentNdvi()).isEqualTo(0.72);
        verifyNoInteractions(ndviClient);
    }

    @Test
    void getVegetation_callsNdviClient_onCacheMiss() {
        UUID id = UUID.randomUUID();
        Patch patch = mockPatch(id);
        var vegData = new VegetationData(List.of(), 0.61, 0.61, 0.0, "stable", "good",
                "2022-10-15", 10, "Sentinel-2");

        when(cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(eq(id), eq("VEGETATION"), any()))
                .thenReturn(Optional.empty());
        when(patchRepository.findById(id)).thenReturn(Optional.of(patch));
        when(ndviClient.fetch(patch)).thenReturn(vegData);

        VegetationData result = service.getVegetation(id);

        assertThat(result).isNotNull();
        assertThat(result.trend()).isEqualTo("stable");
        verify(cacheRepository).save(any(InsightsCache.class));
    }

    @Test
    void getVegetation_throwsNoSuchElement_whenPatchNotFound() {
        UUID id = UUID.randomUUID();
        when(cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(patchRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getVegetation(id))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }
}
