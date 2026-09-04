package io.fouracres.dto;

import java.util.List;

public record BiodiversityData(
    int speciesCount,
    List<SpeciesEntry> topSpecies,
    int threatenedCount
) {
    public record SpeciesEntry(String name, String kingdom) {}
}
