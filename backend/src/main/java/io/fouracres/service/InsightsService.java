package io.fouracres.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.client.GbifClient;
import io.fouracres.client.GfwClient;
import io.fouracres.client.SoilGridsClient;
import io.fouracres.dto.*;
import io.fouracres.model.InsightsCache;
import io.fouracres.model.Patch;
import io.fouracres.repository.InsightsCacheRepository;
import io.fouracres.repository.PatchRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class InsightsService {
    private final PatchRepository patchRepository;
    private final InsightsCacheRepository cacheRepository;
    private final GbifClient gbifClient;
    private final SoilGridsClient soilGridsClient;
    private final GfwClient gfwClient;
    private final ObjectMapper mapper;

    public InsightsService(PatchRepository patchRepository,
                           InsightsCacheRepository cacheRepository,
                           GbifClient gbifClient,
                           SoilGridsClient soilGridsClient,
                           GfwClient gfwClient,
                           ObjectMapper mapper) {
        this.patchRepository = patchRepository;
        this.cacheRepository = cacheRepository;
        this.gbifClient = gbifClient;
        this.soilGridsClient = soilGridsClient;
        this.gfwClient = gfwClient;
        this.mapper = mapper;
    }

    public PatchInsightsDto getInsights(UUID patchId) {
        var now = Instant.now();

        Optional<InsightsCache> bioCached   = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "BIODIVERSITY", now);
        Optional<InsightsCache> soilCached  = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "SOIL", now);
        Optional<InsightsCache> carbonCached = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "CARBON", now);

        if (bioCached.isPresent() && soilCached.isPresent() && carbonCached.isPresent()) {
            return deserialize(bioCached.get(), soilCached.get(), carbonCached.get());
        }

        Patch patch = patchRepository.findById(patchId)
            .orElseThrow(() -> new NoSuchElementException("Patch not found: " + patchId));

        var bioFuture    = CompletableFuture.supplyAsync(() -> gbifClient.fetch(patch));
        var soilFuture   = CompletableFuture.supplyAsync(() -> soilGridsClient.fetch(patch));
        var carbonFuture = CompletableFuture.supplyAsync(() -> gfwClient.fetch(patch));

        CompletableFuture.allOf(bioFuture, soilFuture, carbonFuture).join();

        var bio    = bioFuture.join();
        var soil   = soilFuture.join();
        var carbon = carbonFuture.join();

        var expires = now.plus(Duration.ofHours(24));
        saveCache(patchId, "BIODIVERSITY", bio, expires);
        saveCache(patchId, "SOIL", soil, expires);
        saveCache(patchId, "CARBON", carbon, expires);

        return new PatchInsightsDto(bio, soil, carbon, null, null, null);
    }

    private void saveCache(UUID patchId, String layer, Object data, Instant expires) {
        try {
            var cache = new InsightsCache(patchId, layer, mapper.writeValueAsString(data), expires);
            cacheRepository.save(cache);
        } catch (Exception ignored) {}
    }

    private PatchInsightsDto deserialize(InsightsCache bio, InsightsCache soil, InsightsCache carbon) {
        try {
            return new PatchInsightsDto(
                mapper.readValue(bio.getPayload(), BiodiversityData.class),
                mapper.readValue(soil.getPayload(), SoilData.class),
                mapper.readValue(carbon.getPayload(), CarbonData.class),
                null,
                null,
                null
            );
        } catch (Exception e) {
            throw new RuntimeException("Cache deserialization failed", e);
        }
    }
}
