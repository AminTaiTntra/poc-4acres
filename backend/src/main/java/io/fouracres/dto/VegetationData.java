package io.fouracres.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;

public record VegetationData(
        List<NdviYearlyPoint> yearly,
        @JsonAlias("current_ndvi")    double currentNdvi,
        @JsonAlias("baseline_ndvi")   double baselineNdvi,
        @JsonAlias("change_pct")      double changePct,
        String trend,
        String condition,
        @JsonAlias("last_observation") String lastObservation,
        @JsonAlias("resolution_m")    int resolutionM,
        String source
) {}
