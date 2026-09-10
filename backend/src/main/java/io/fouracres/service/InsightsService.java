// backend/src/main/java/io/fouracres/service/InsightsService.java
package io.fouracres.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.client.*;
import io.fouracres.dto.*;
import io.fouracres.model.InsightsCache;
import io.fouracres.model.Patch;
import io.fouracres.repository.InsightsCacheRepository;
import io.fouracres.repository.PatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@Transactional
public class InsightsService {

    private final PatchRepository patchRepository;
    private final InsightsCacheRepository cacheRepository;
    private final GbifClient gbifClient;
    private final SoilGridsClient soilGridsClient;
    private final GfwClient gfwClient;
    private final MapboxGeocodingClient geocodingClient;
    private final WeatherApiClient weatherClient;
    private final SentinelClient sentinelClient;
    private final GeoEngineClient geoEngineClient;
    private final ObjectMapper mapper;

    public InsightsService(PatchRepository patchRepository,
                            InsightsCacheRepository cacheRepository,
                            GbifClient gbifClient,
                            SoilGridsClient soilGridsClient,
                            GfwClient gfwClient,
                            MapboxGeocodingClient geocodingClient,
                            WeatherApiClient weatherClient,
                            SentinelClient sentinelClient,
                            GeoEngineClient geoEngineClient,
                            ObjectMapper mapper) {
        this.patchRepository  = patchRepository;
        this.cacheRepository  = cacheRepository;
        this.gbifClient       = gbifClient;
        this.soilGridsClient  = soilGridsClient;
        this.gfwClient        = gfwClient;
        this.geocodingClient  = geocodingClient;
        this.weatherClient    = weatherClient;
        this.sentinelClient   = sentinelClient;
        this.geoEngineClient  = geoEngineClient;
        this.mapper           = mapper;
    }

    public PatchInsightsDto getInsights(UUID patchId) {
        var now = Instant.now();

        Optional<InsightsCache> bioCached       = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "BIODIVERSITY", now);
        Optional<InsightsCache> soilCached      = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "SOIL",         now);
        Optional<InsightsCache> carbonCached    = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "CARBON",       now);
        Optional<InsightsCache> geoCached       = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "GEOCODING",    now);
        Optional<InsightsCache> weatherCached   = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "WEATHER",      now);
        Optional<InsightsCache> satelliteCached = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "SATELLITE",    now);
        Optional<InsightsCache> waterCached     = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "WATER",        now);
        Optional<InsightsCache> terrainCached   = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "TERRAIN",      now);

        if (bioCached.isPresent() && soilCached.isPresent() && carbonCached.isPresent()
                && geoCached.isPresent() && weatherCached.isPresent() && satelliteCached.isPresent()
                && waterCached.isPresent() && terrainCached.isPresent()) {
            return deserialize(bioCached.get(), soilCached.get(), carbonCached.get(),
                               geoCached.get(), weatherCached.get(), satelliteCached.get(),
                               waterCached.get(), terrainCached.get());
        }

        Patch patch = patchRepository.findById(patchId)
            .orElseThrow(() -> new NoSuchElementException("Patch not found: " + patchId));

        var bioFuture       = CompletableFuture.supplyAsync(() -> gbifClient.fetch(patch));
        var soilFuture      = CompletableFuture.supplyAsync(() -> soilGridsClient.fetch(patch));
        var carbonFuture    = CompletableFuture.supplyAsync(() -> gfwClient.fetch(patch));
        var geoFuture       = CompletableFuture.supplyAsync(() -> geocodingClient.fetch(patch));
        var weatherFuture   = CompletableFuture.supplyAsync(() -> weatherClient.fetch(patch));
        var satelliteFuture = CompletableFuture.supplyAsync(() -> sentinelClient.fetch(patch));
        var waterFuture     = CompletableFuture.supplyAsync(() -> geoEngineClient.fetchWater(patch));
        var terrainFuture   = CompletableFuture.supplyAsync(() -> geoEngineClient.fetchTerrain(patch));

        CompletableFuture.allOf(bioFuture, soilFuture, carbonFuture,
                                 geoFuture, weatherFuture, satelliteFuture,
                                 waterFuture, terrainFuture).join();

        var bio       = bioFuture.join();
        var soil      = soilFuture.join();
        var carbon    = carbonFuture.join();
        var geo       = geoFuture.join();
        var weather   = weatherFuture.join();
        var satellite = satelliteFuture.join();
        var water     = waterFuture.join();
        var terrain   = terrainFuture.join();

        saveCache(patchId, "BIODIVERSITY", bio,       expiresAt("BIODIVERSITY"));
        saveCache(patchId, "SOIL",         soil,      expiresAt("SOIL"));
        saveCache(patchId, "CARBON",       carbon,    expiresAt("CARBON"));
        saveCache(patchId, "GEOCODING",    geo,       expiresAt("GEOCODING"));
        saveCache(patchId, "WEATHER",      weather,   expiresAt("WEATHER"));
        saveCache(patchId, "SATELLITE",    satellite, expiresAt("SATELLITE"));
        saveCache(patchId, "WATER",        water,     expiresAt("WATER"));
        saveCache(patchId, "TERRAIN",      terrain,   expiresAt("TERRAIN"));

        return new PatchInsightsDto(bio, soil, carbon, geo, weather, satellite, water, terrain);
    }

    private Instant expiresAt(String layer) {
        return switch (layer) {
            case "WEATHER"            -> Instant.now().plus(1,  ChronoUnit.HOURS);
            case "SATELLITE"          -> Instant.now().plus(7,  ChronoUnit.DAYS);
            case "GEOCODING"          -> Instant.now().plus(30, ChronoUnit.DAYS);
            // Water history (1984-2021) and elevation are static datasets — refetching
            // them daily would just burn Earth Engine quota for identical answers.
            case "WATER", "TERRAIN"   -> Instant.now().plus(30, ChronoUnit.DAYS);
            default                   -> Instant.now().plus(24, ChronoUnit.HOURS);
        };
    }

    private void saveCache(UUID patchId, String layer, Object data, Instant expires) {
        try {
            var cache = new InsightsCache(patchId, layer, mapper.writeValueAsString(data), expires);
            cacheRepository.save(cache);
        } catch (Exception ignored) {}
    }

    private PatchInsightsDto deserialize(InsightsCache bio, InsightsCache soil, InsightsCache carbon,
                                          InsightsCache geo, InsightsCache weather, InsightsCache satellite,
                                          InsightsCache water, InsightsCache terrain) {
        try {
            return new PatchInsightsDto(
                mapper.readValue(bio.getPayload(),       BiodiversityData.class),
                mapper.readValue(soil.getPayload(),      SoilData.class),
                mapper.readValue(carbon.getPayload(),    CarbonData.class),
                mapper.readValue(geo.getPayload(),       GeographicContextData.class),
                mapper.readValue(weather.getPayload(),   WeatherData.class),
                mapper.readValue(satellite.getPayload(), SatelliteSceneData.class),
                mapper.readValue(water.getPayload(),     WaterData.class),
                mapper.readValue(terrain.getPayload(),   TerrainData.class)
            );
        } catch (Exception e) {
            throw new RuntimeException("Cache deserialization failed", e);
        }
    }
}
