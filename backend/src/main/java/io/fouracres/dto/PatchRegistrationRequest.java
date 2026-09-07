package io.fouracres.dto;

public class PatchRegistrationRequest {
    private String name;
    private String description;
    private String ownerName;
    private String country;
    private String ecosystemType;
    private GeoJsonPolygon boundary;

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getOwnerName() { return ownerName; }
    public String getCountry() { return country; }
    public String getEcosystemType() { return ecosystemType; }
    public GeoJsonPolygon getBoundary() { return boundary; }

    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public void setCountry(String country) { this.country = country; }
    public void setEcosystemType(String ecosystemType) { this.ecosystemType = ecosystemType; }
    public void setBoundary(GeoJsonPolygon boundary) { this.boundary = boundary; }
}
