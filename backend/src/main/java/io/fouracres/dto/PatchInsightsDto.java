package io.fouracres.dto;

public record PatchInsightsDto(
    BiodiversityData       biodiversity,
    SoilData               soil,
    CarbonData             carbon,
    GeographicContextData  geographicContext,
    WeatherData            weather,
    SatelliteSceneData     satellite,
    WaterData              water,
    TerrainData            terrain
) {}
