package io.fouracres.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NdviYearlyPoint(
        int year,
        Double ndvi,
        @JsonProperty("valid_pixel_pct") Double validPixelPct,
        @JsonProperty("observation_date") String observationDate
) {}
