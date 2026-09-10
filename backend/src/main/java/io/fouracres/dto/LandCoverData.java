package io.fouracres.dto;

import java.util.List;

/**
 * Land cover composition + historical change for a patch, from Google Dynamic World V1
 * (10 m near-real-time LULC, 9 classes, class probabilities, 2015→present).
 *
 * <p>Composition is the mean Dynamic World class-probability mix within the parcel,
 * normalised to 100%. {@link #confidentPercent} is the share of pixels whose top class
 * probability is at least {@link #probabilityThreshold} — Dynamic World recommends
 * thresholding the top-class probability when selecting confident classifications.
 * {@link #observationCount} is the mean number of cloud-free Dynamic World observations
 * per pixel in the latest year.</p>
 */
public record LandCoverData(
    boolean hasData,
    String source,                       // "GOOGLE/DYNAMICWORLD/V1" or "UNAVAILABLE"
    double probabilityThreshold,         // top-class probability cut, e.g. 0.5
    double confidentPercent,             // % of latest-period pixels at/above the threshold
    double parcelAcres,                  // parcel area the class areas are computed against
    double observationCount,             // mean Dynamic World observations / pixel, latest year
    String latestPeriod,                 // most-recent year, e.g. "2026" — null when no data
    List<ClassShare> composition,        // latest-period class composition, desc by percent
    List<YearlyComposition> history,     // one entry per year, ascending
    List<ClassChange> changes,           // per-class delta, first → last year, desc by |delta|
    List<Transition> transitions,        // class-to-class conversions, first → last year, desc by area
    String trend                         // plain-language summary for the Steward
) {
    public record ClassShare(String className, double percent, double areaAcres, double areaHectares) {}

    public record YearlyComposition(String year, List<ClassShare> classes) {}

    public record ClassChange(
        String className,
        double startPercent,
        double endPercent,
        double deltaPercent
    ) {}

    /** A parcel area that was class {@code fromClass} in the baseline year and {@code toClass} in the latest year. */
    public record Transition(String fromClass, String toClass, double areaAcres, double percent) {}

    public static LandCoverData unavailable(double threshold) {
        return new LandCoverData(
            false, "UNAVAILABLE", threshold, 0.0, 0.0, 0.0, null,
            List.of(), List.of(), List.of(), List.of(),
            "Dynamic World land-cover data is not available for this patch."
        );
    }
}
