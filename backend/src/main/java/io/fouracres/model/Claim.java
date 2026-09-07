package io.fouracres.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "claims")
public class Claim {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne
    @JoinColumn(name = "patch_id", nullable = false, unique = true)
    private Patch patch;

    @Column(name = "steward_name", nullable = false)
    private String stewardName;

    @Column(name = "steward_email", nullable = false)
    private String stewardEmail;

    @Column(name = "claimed_at", nullable = false)
    private Instant claimedAt = Instant.now();

    public UUID getId() { return id; }
    public Patch getPatch() { return patch; }
    public String getStewardName() { return stewardName; }
    public String getStewardEmail() { return stewardEmail; }
    public Instant getClaimedAt() { return claimedAt; }

    public void setId(UUID id) { this.id = id; }
    public void setPatch(Patch patch) { this.patch = patch; }
    public void setStewardName(String stewardName) { this.stewardName = stewardName; }
    public void setStewardEmail(String stewardEmail) { this.stewardEmail = stewardEmail; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
}
