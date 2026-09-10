package io.fouracres.model;

import jakarta.persistence.*;
import org.locationtech.jts.geom.Polygon;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "patches")
public class Patch {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "ecosystem_type", nullable = false, length = 50)
    private EcosystemType ecosystemType;

    @Column(nullable = false, length = 100)
    private String country;

    private String description;

    @Column(name = "center_lat", nullable = false, precision = 10, scale = 7)
    private BigDecimal centerLat;

    @Column(name = "center_lng", nullable = false, precision = 10, scale = 7)
    private BigDecimal centerLng;

    @Column(nullable = false, columnDefinition = "geometry(Polygon,4326)")
    private Polygon boundary;

    @Column(name = "area_acres", nullable = false, precision = 5, scale = 2)
    private BigDecimal areaAcres = BigDecimal.valueOf(4.0);

    @Column(name = "gfw_geostore_id", length = 100)
    private String gfwGeostoreId;

    @Column(name = "season_start_month", nullable = false)
    private int seasonStartMonth = 10;

    @Column(name = "season_end_month", nullable = false)
    private int seasonEndMonth = 11;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PatchStatus status = PatchStatus.AVAILABLE;

    @Column(name = "owner_name", length = 255)
    private String ownerName;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    // Getters
    public UUID getId() { return id; }
    public String getName() { return name; }
    public EcosystemType getEcosystemType() { return ecosystemType; }
    public String getCountry() { return country; }
    public String getDescription() { return description; }
    public BigDecimal getCenterLat() { return centerLat; }
    public BigDecimal getCenterLng() { return centerLng; }
    public Polygon getBoundary() { return boundary; }
    public BigDecimal getAreaAcres() { return areaAcres; }
    public String getGfwGeostoreId() { return gfwGeostoreId; }
    public int getSeasonStartMonth() { return seasonStartMonth; }
    public int getSeasonEndMonth()   { return seasonEndMonth; }
    public PatchStatus getStatus() { return status; }
    public String getOwnerName() { return ownerName; }

    // Setters
    public void setId(UUID id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setEcosystemType(EcosystemType ecosystemType) { this.ecosystemType = ecosystemType; }
    public void setCountry(String country) { this.country = country; }
    public void setDescription(String description) { this.description = description; }
    public void setCenterLat(BigDecimal centerLat) { this.centerLat = centerLat; }
    public void setCenterLng(BigDecimal centerLng) { this.centerLng = centerLng; }
    public void setBoundary(Polygon boundary) { this.boundary = boundary; }
    public void setAreaAcres(BigDecimal areaAcres) { this.areaAcres = areaAcres; }
    public void setGfwGeostoreId(String gfwGeostoreId) { this.gfwGeostoreId = gfwGeostoreId; }
    public void setStatus(PatchStatus status) { this.status = status; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
}
