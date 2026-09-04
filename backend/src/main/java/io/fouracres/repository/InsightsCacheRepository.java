package io.fouracres.repository;

import io.fouracres.model.InsightsCache;
import io.fouracres.model.InsightsCacheId;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface InsightsCacheRepository extends JpaRepository<InsightsCache, InsightsCacheId> {
    Optional<InsightsCache> findByPatchIdAndLayerAndExpiresAtAfter(
        UUID patchId, String layer, Instant now);
}
