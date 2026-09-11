package io.fouracres.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public record NdviYearlyPoint(
        int year,
        Double ndvi,
        @JsonAlias("valid_pixel_pct") Double validPixelPct,
        @JsonAlias("observation_date") String observationDate
) {}
