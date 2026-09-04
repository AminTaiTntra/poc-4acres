package io.fouracres.dto;

import io.fouracres.model.Patch;
import org.locationtech.jts.geom.Coordinate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public record PatchDto(
    UUID id,
    String name,
    String ecosystemType,
    String country,
    double centerLat,
    double centerLng,
    Object boundaryGeoJson
) {
    public static PatchDto from(Patch p) {
        var coords = Arrays.stream(p.getBoundary().getCoordinates())
            .map(c -> List.of(c.x, c.y))
            .toList();
        var geojson = java.util.Map.of(
            "type", "Polygon",
            "coordinates", List.of(coords)
        );
        return new PatchDto(
            p.getId(),
            p.getName(),
            p.getEcosystemType().name(),
            p.getCountry(),
            p.getCenterLat().doubleValue(),
            p.getCenterLng().doubleValue(),
            geojson
        );
    }
}
