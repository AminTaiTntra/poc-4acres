package io.fouracres.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record VegetationData(
        List<NdviYearlyPoint> yearly,
        @JsonProperty("current_ndvi")    double currentNdvi,
        @JsonProperty("baseline_ndvi")   double baselineNdvi,
        @JsonProperty("change_pct")      double changePct,
        String trend,
        String condition,
        @JsonProperty("last_observation") String lastObservation,
        @JsonProperty("resolution_m")    int resolutionM,
        String source
) {}
