package io.fouracres.dto;

public record SatelliteSceneData(
    boolean hasRecentScene,
    String  latestSceneDate,    // null when hasRecentScene=false
    double  cloudCoverPercent,
    String  productType,        // null when hasRecentScene=false
    String  thumbnailUrl        // nullable even when hasRecentScene=true
) {}
