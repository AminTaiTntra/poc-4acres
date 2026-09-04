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
}
