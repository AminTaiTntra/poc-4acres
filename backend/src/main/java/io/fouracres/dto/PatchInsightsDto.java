package io.fouracres.dto;

public record PatchInsightsDto(
    BiodiversityData biodiversity,
    SoilData soil,
    CarbonData carbon
) {}
