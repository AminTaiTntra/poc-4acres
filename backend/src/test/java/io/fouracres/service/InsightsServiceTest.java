// backend/src/test/java/io/fouracres/service/InsightsServiceTest.java
package io.fouracres.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.client.*;
import io.fouracres.dto.*;
import io.fouracres.model.Patch;
import io.fouracres.repository.InsightsCacheRepository;
import io.fouracres.repository.PatchRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InsightsServiceTest {

    private InsightsService buildService(PatchRepository patchRepo,
                                         InsightsCacheRepository cacheRepo,
                                         GbifClient gbif,
                                         SoilGridsClient soil,
                                         GfwClient gfw,
                                         MapboxGeocodingClient geocoding,
                                         WeatherApiClient weather,
                                         SentinelClient sentinel) {
        return new InsightsService(patchRepo, cacheRepo, gbif, soil, gfw,
                                    geocoding, weather, sentinel, new ObjectMapper());
    }

    @Test
    void getInsights_callsAllSixClientsAndCachesAllSixLayers() {
        var patchId = UUID.randomUUID();
        var patch   = Mockito.mock(Patch.class);

        var patchRepo  = Mockito.mock(PatchRepository.class);
        var cacheRepo  = Mockito.mock(InsightsCacheRepository.class);
        var gbif       = Mockito.mock(GbifClient.class);
        var soil       = Mockito.mock(SoilGridsClient.class);
        var gfw        = Mockito.mock(GfwClient.class);
        var geocoding  = Mockito.mock(MapboxGeocodingClient.class);
        var weather    = Mockito.mock(WeatherApiClient.class);
        var sentinel   = Mockito.mock(SentinelClient.class);

        when(patchRepo.findById(patchId)).thenReturn(Optional.of(patch));
        when(cacheRepo.findByPatchIdAndLayerAndExpiresAtAfter(eq(patchId), any(), any()))
            .thenReturn(Optional.empty());  // all misses

        when(gbif.fetch(patch)).thenReturn(new BiodiversityData(42, List.of(), 3));
        when(soil.fetch(patch)).thenReturn(new SoilData(21.5, 5.7, 32.4));
        when(gfw.fetch(patch)).thenReturn(new CarbonData(35.0, 210.5, 0.08));
        when(geocoding.fetch(patch)).thenReturn(
            new GeographicContextData("Tulum", null, "Tulum", "Quintana Roo", "Mexico", "Tulum, Mexico"));
        when(weather.fetch(patch)).thenReturn(
            new WeatherData(22.5, "Sunny", "https://cdn.example.com/sun.png", 10.0, 60, 5.0, List.of()));
        when(sentinel.fetch(patch)).thenReturn(
            new SatelliteSceneData(true, "2026-09-05T10:00:00Z", 12.4, "S2MSI2A", null));

        var service = buildService(patchRepo, cacheRepo, gbif, soil, gfw, geocoding, weather, sentinel);
        PatchInsightsDto result = service.getInsights(patchId);

        assertThat(result.biodiversity().speciesCount()).isEqualTo(42);
        assertThat(result.soil().ph()).isEqualTo(5.7);
        assertThat(result.carbon().carbonDensityMgHa()).isEqualTo(210.5);
        assertThat(result.geographicContext().country()).isEqualTo("Mexico");
        assertThat(result.weather().tempC()).isEqualTo(22.5);
        assertThat(result.satellite().cloudCoverPercent()).isEqualTo(12.4);

        // 6 cache saves, one per layer
        verify(cacheRepo, times(6)).save(any());
    }

    @Test
    void getInsights_returnsCachedData_onFullCacheHit() throws Exception {
        var patchId   = UUID.randomUUID();
        var patchRepo = Mockito.mock(PatchRepository.class);
        var cacheRepo = Mockito.mock(InsightsCacheRepository.class);
        var gbif      = Mockito.mock(GbifClient.class);
        var soil      = Mockito.mock(SoilGridsClient.class);
        var gfw       = Mockito.mock(GfwClient.class);
        var geocoding = Mockito.mock(MapboxGeocodingClient.class);
        var weather   = Mockito.mock(WeatherApiClient.class);
        var sentinel  = Mockito.mock(SentinelClient.class);

        var mapper = new ObjectMapper();

        var bioData  = new BiodiversityData(7, List.of(), 1);
        var soilData = new SoilData(10.0, 6.0, 20.0);
        var carbData = new CarbonData(50.0, 100.0, 0.0);
        var geoData  = new GeographicContextData("City", null, "City", "Region", "Country", "City, Region, Country");
        var wxData   = new WeatherData(18.0, "Cloudy", "", 5.0, 75, 2.0, List.of());
        var satData  = new SatelliteSceneData(false, null, 0.0, null, null);

        mockCacheHit(cacheRepo, patchId, "BIODIVERSITY", mapper.writeValueAsString(bioData));
        mockCacheHit(cacheRepo, patchId, "SOIL",         mapper.writeValueAsString(soilData));
        mockCacheHit(cacheRepo, patchId, "CARBON",       mapper.writeValueAsString(carbData));
        mockCacheHit(cacheRepo, patchId, "GEOCODING",    mapper.writeValueAsString(geoData));
        mockCacheHit(cacheRepo, patchId, "WEATHER",      mapper.writeValueAsString(wxData));
        mockCacheHit(cacheRepo, patchId, "SATELLITE",    mapper.writeValueAsString(satData));

        var service = buildService(patchRepo, cacheRepo, gbif, soil, gfw, geocoding, weather, sentinel);
        PatchInsightsDto result = service.getInsights(patchId);

        assertThat(result.biodiversity().speciesCount()).isEqualTo(7);
        assertThat(result.geographicContext().country()).isEqualTo("Country");
        assertThat(result.weather().tempC()).isEqualTo(18.0);
        assertThat(result.satellite().hasRecentScene()).isFalse();

        verifyNoInteractions(gbif, soil, gfw, geocoding, weather, sentinel);
    }

    private void mockCacheHit(InsightsCacheRepository cacheRepo, UUID patchId, String layer, String payload) {
        var cached = Mockito.mock(io.fouracres.model.InsightsCache.class);
        when(cached.getPayload()).thenReturn(payload);
        when(cacheRepo.findByPatchIdAndLayerAndExpiresAtAfter(eq(patchId), eq(layer), any()))
            .thenReturn(Optional.of(cached));
    }
}
