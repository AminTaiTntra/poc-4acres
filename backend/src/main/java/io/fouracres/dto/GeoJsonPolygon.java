package io.fouracres.dto;

import java.util.List;

public class GeoJsonPolygon {
    private String type;
    private List<List<List<Double>>> coordinates;

    public String getType() { return type; }
    public List<List<List<Double>>> getCoordinates() { return coordinates; }
    public void setType(String type) { this.type = type; }
    public void setCoordinates(List<List<List<Double>>> coordinates) { this.coordinates = coordinates; }
}
