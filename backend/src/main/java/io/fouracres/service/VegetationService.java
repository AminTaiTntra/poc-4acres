package io.fouracres.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.client.NdviClient;
import io.fouracres.dto.VegetationData;
import io.fouracres.model.InsightsCache;
import io.fouracres.repository.InsightsCacheRepository;
import io.fouracres.repository.PatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@Transactional
public class VegetationService {

    private final PatchRepository patchRepository;
    private final InsightsCacheRepository cacheRepository;
    private final NdviClient ndviClient;
    private final ObjectMapper mapper;

    public VegetationService(PatchRepository patchRepository,
                              InsightsCacheRepository cacheRepository,
                              NdviClient ndviClient,
                              ObjectMapper mapper) {
        this.patchRepository = patchRepository;
        this.cacheRepository = cacheRepository;
        this.ndviClient = ndviClient;
        this.mapper = mapper;
    }

    public VegetationData getVegetation(UUID patchId) {
        var now = Instant.now();
        var cached = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "VEGETATION", now);
        if (cached.isPresent()) {
            try {
                return mapper.readValue(cached.get().getPayload(), VegetationData.class);
            } catch (Exception ignored) {}
        }

        var patch = patchRepository.findById(patchId)
                .orElseThrow(() -> new NoSuchElementException("Patch not found: " + patchId));

        VegetationData data = ndviClient.fetch(patch);
        if (data == null) {
            return null;
        }

        try {
            var entry = new InsightsCache(
                    patchId, "VEGETATION",
                    mapper.writeValueAsString(data),
                    now.plus(24, ChronoUnit.HOURS)
            );
            cacheRepository.save(entry);
        } catch (Exception ignored) {}

        return data;
    }
}
