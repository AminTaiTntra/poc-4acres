package io.fouracres.dto;

public record TerrainData(
    double elevationMinM,
    double elevationMaxM,
    double avgSlopeDeg,
    double maxSlopeDeg,
    String terrainClass
) {}
