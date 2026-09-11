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
                                         SentinelClient sentinel,
                                         DynamicWorldClient dynamicWorld) {
        return new InsightsService(patchRepo, cacheRepo, gbif, soil, gfw,
                                    geocoding, weather, sentinel, dynamicWorld, new ObjectMapper());
    }

    @Test
    void getInsights_callsAllSevenClientsAndCachesAllSevenLayers() {
        var patchId = UUID.randomUUID();
        var patch   = Mockito.mock(Patch.class);

        var patchRepo     = Mockito.mock(PatchRepository.class);
        var cacheRepo     = Mockito.mock(InsightsCacheRepository.class);
        var gbif          = Mockito.mock(GbifClient.class);
        var soil          = Mockito.mock(SoilGridsClient.class);
        var gfw           = Mockito.mock(GfwClient.class);
        var geocoding     = Mockito.mock(MapboxGeocodingClient.class);
        var weather       = Mockito.mock(WeatherApiClient.class);
        var sentinel      = Mockito.mock(SentinelClient.class);
        var dynamicWorld  = Mockito.mock(DynamicWorldClient.class);

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
        when(dynamicWorld.fetch(patch)).thenReturn(
            new LandCoverData(true, "GOOGLE/DYNAMICWORLD/V1", 0.5, 88.0, 4.0, 25.0, "2026",
                List.of(new LandCoverData.ClassShare("Trees", 62.0, 2.48, 1.0)),
                List.of(new LandCoverData.YearlyComposition("2026",
                    List.of(new LandCoverData.ClassShare("Trees", 62.0, 2.48, 1.0)))),
                List.of(new LandCoverData.ClassChange("Trees", 58.0, 62.0, 4.0)),
                List.of(new LandCoverData.Transition("Bare ground", "Trees", 0.4, 10.0)),
                "2016→2026: Trees remains the dominant cover; becoming more vegetated (+4.0 pts vegetation)."));

        var service = buildService(patchRepo, cacheRepo, gbif, soil, gfw, geocoding, weather, sentinel, dynamicWorld);
        PatchInsightsDto result = service.getInsights(patchId);

        assertThat(result.biodiversity().speciesCount()).isEqualTo(42);
        assertThat(result.soil().ph()).isEqualTo(5.7);
        assertThat(result.carbon().carbonDensityMgHa()).isEqualTo(210.5);
        assertThat(result.geographicContext().country()).isEqualTo("Mexico");
        assertThat(result.weather().tempC()).isEqualTo(22.5);
        assertThat(result.satellite().cloudCoverPercent()).isEqualTo(12.4);
        assertThat(result.landCover().hasData()).isTrue();
        assertThat(result.landCover().composition().get(0).className()).isEqualTo("Trees");

        // 7 cache saves, one per layer
        verify(cacheRepo, times(7)).save(any());
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
        var dynamicWorld = Mockito.mock(DynamicWorldClient.class);

        var mapper = new ObjectMapper();

        var bioData  = new BiodiversityData(7, List.of(), 1);
        var soilData = new SoilData(10.0, 6.0, 20.0);
        var carbData = new CarbonData(50.0, 100.0, 0.0);
        var geoData  = new GeographicContextData("City", null, "City", "Region", "Country", "City, Region, Country");
        var wxData   = new WeatherData(18.0, "Cloudy", "", 5.0, 75, 2.0, List.of());
        var satData  = new SatelliteSceneData(false, null, 0.0, null, null);
        var lcData   = LandCoverData.unavailable(0.5);

        mockCacheHit(cacheRepo, patchId, "BIODIVERSITY", mapper.writeValueAsString(bioData));
        mockCacheHit(cacheRepo, patchId, "SOIL",         mapper.writeValueAsString(soilData));
        mockCacheHit(cacheRepo, patchId, "CARBON",       mapper.writeValueAsString(carbData));
        mockCacheHit(cacheRepo, patchId, "GEOCODING",    mapper.writeValueAsString(geoData));
        mockCacheHit(cacheRepo, patchId, "WEATHER",      mapper.writeValueAsString(wxData));
        mockCacheHit(cacheRepo, patchId, "SATELLITE",    mapper.writeValueAsString(satData));
        mockCacheHit(cacheRepo, patchId, "LANDCOVER",    mapper.writeValueAsString(lcData));

        var service = buildService(patchRepo, cacheRepo, gbif, soil, gfw, geocoding, weather, sentinel, dynamicWorld);
        PatchInsightsDto result = service.getInsights(patchId);

        assertThat(result.biodiversity().speciesCount()).isEqualTo(7);
        assertThat(result.geographicContext().country()).isEqualTo("Country");
        assertThat(result.weather().tempC()).isEqualTo(18.0);
        assertThat(result.satellite().hasRecentScene()).isFalse();
        assertThat(result.landCover().hasData()).isFalse();

        verifyNoInteractions(gbif, soil, gfw, geocoding, weather, sentinel, dynamicWorld);
    }

    private void mockCacheHit(InsightsCacheRepository cacheRepo, UUID patchId, String layer, String payload) {
        var cached = Mockito.mock(io.fouracres.model.InsightsCache.class);
        when(cached.getPayload()).thenReturn(payload);
        when(cacheRepo.findByPatchIdAndLayerAndExpiresAtAfter(eq(patchId), eq(layer), any()))
            .thenReturn(Optional.of(cached));
    }
}
