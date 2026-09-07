package io.fouracres.dto;

public record GeographicContextData(
    String placeName,
    String neighborhood,  // null if not present in Mapbox context
    String city,
    String region,
    String country,
    String fullAddress
) {}
