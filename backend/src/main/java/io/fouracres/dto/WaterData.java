package io.fouracres.dto;

public record WaterData(
    double surfaceWaterHa,
    String occurrenceClass,
    double recurrencePercent,
    String period
) {}
