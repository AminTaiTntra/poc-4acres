package io.fouracres.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.client.GbifClient;
import io.fouracres.client.GfwClient;
import io.fouracres.client.SoilGridsClient;
import io.fouracres.dto.*;
import io.fouracres.model.Patch;
import io.fouracres.repository.InsightsCacheRepository;
import io.fouracres.repository.PatchRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InsightsServiceTest {

    @Test
    void getInsights_callsAllClientsAndCaches() {
        var patchId = UUID.randomUUID();
        var patch = Mockito.mock(Patch.class);

        var patchRepo = Mockito.mock(PatchRepository.class);
        var cacheRepo = Mockito.mock(InsightsCacheRepository.class);
        var gbif = Mockito.mock(GbifClient.class);
        var soil = Mockito.mock(SoilGridsClient.class);
        var gfw = Mockito.mock(GfwClient.class);

        when(patchRepo.findById(patchId)).thenReturn(Optional.of(patch));
        when(cacheRepo.findByPatchIdAndLayerAndExpiresAtAfter(eq(patchId), any(), any()))
            .thenReturn(Optional.empty()); // cache miss

        var bioData = new BiodiversityData(42, List.of(), 3);
        var soilData = new SoilData(21.5, 5.7, 32.4);
        var carbonData = new CarbonData(35.0, 210.5, 0.08);

        when(gbif.fetch(patch)).thenReturn(bioData);
        when(soil.fetch(patch)).thenReturn(soilData);
        when(gfw.fetch(patch)).thenReturn(carbonData);

        var service = new InsightsService(patchRepo, cacheRepo, gbif, soil, gfw, new ObjectMapper());
        PatchInsightsDto result = service.getInsights(patchId);

        assertThat(result.biodiversity().speciesCount()).isEqualTo(42);
        assertThat(result.soil().ph()).isEqualTo(5.7);
        assertThat(result.carbon().carbonDensityMgHa()).isEqualTo(210.5);

        // verify cache was written for all 3 layers
        verify(cacheRepo, times(3)).save(any());
    }

    @Test
    void getInsights_returnsCachedData_onCacheHit() throws Exception {
        var patchId = UUID.randomUUID();
        var patchRepo = Mockito.mock(PatchRepository.class);
        var cacheRepo = Mockito.mock(InsightsCacheRepository.class);
        var gbif = Mockito.mock(GbifClient.class);
        var soil = Mockito.mock(SoilGridsClient.class);
        var gfw = Mockito.mock(GfwClient.class);

        var mapper = new ObjectMapper();
        var bioData = new BiodiversityData(7, List.of(), 1);

        var cachedBio = Mockito.mock(io.fouracres.model.InsightsCache.class);
        var cachedSoil = Mockito.mock(io.fouracres.model.InsightsCache.class);
        var cachedCarbon = Mockito.mock(io.fouracres.model.InsightsCache.class);

        when(cachedBio.getPayload()).thenReturn(mapper.writeValueAsString(bioData));
        when(cachedSoil.getPayload()).thenReturn(mapper.writeValueAsString(new SoilData(10.0, 6.0, 20.0)));
        when(cachedCarbon.getPayload()).thenReturn(mapper.writeValueAsString(new CarbonData(50.0, 100.0, 0.0)));

        when(cacheRepo.findByPatchIdAndLayerAndExpiresAtAfter(eq(patchId), eq("BIODIVERSITY"), any()))
            .thenReturn(Optional.of(cachedBio));
        when(cacheRepo.findByPatchIdAndLayerAndExpiresAtAfter(eq(patchId), eq("SOIL"), any()))
            .thenReturn(Optional.of(cachedSoil));
        when(cacheRepo.findByPatchIdAndLayerAndExpiresAtAfter(eq(patchId), eq("CARBON"), any()))
            .thenReturn(Optional.of(cachedCarbon));

        var service = new InsightsService(patchRepo, cacheRepo, gbif, soil, gfw, mapper);
        PatchInsightsDto result = service.getInsights(patchId);

        assertThat(result.biodiversity().speciesCount()).isEqualTo(7);
        verifyNoInteractions(gbif, soil, gfw); // clients never called
    }
}
