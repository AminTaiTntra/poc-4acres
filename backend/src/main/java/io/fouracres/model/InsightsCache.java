package io.fouracres.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "insights_cache")
@IdClass(InsightsCacheId.class)
public class InsightsCache {
    @Id
    @Column(name = "patch_id")
    private UUID patchId;

    @Id
    @Column(name = "layer", length = 20)
    private String layer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public InsightsCache() {}
    public InsightsCache(UUID patchId, String layer, String payload, Instant expiresAt) {
        this.patchId = patchId;
        this.layer = layer;
        this.payload = payload;
        this.expiresAt = expiresAt;
    }

    public String getPayload() { return payload; }
    public Instant getExpiresAt() { return expiresAt; }
}
