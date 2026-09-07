package io.fouracres.dto;

import io.fouracres.model.Claim;
import java.time.Instant;
import java.util.UUID;

public record ClaimDto(
    UUID id,
    UUID patchId,
    String stewardName,
    String stewardEmail,
    Instant claimedAt
) {
    public static ClaimDto from(Claim c) {
        return new ClaimDto(
            c.getId(),
            c.getPatch().getId(),
            c.getStewardName(),
            c.getStewardEmail(),
            c.getClaimedAt()
        );
    }
}
