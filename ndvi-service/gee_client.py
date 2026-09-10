import json
import os
import calendar
from typing import Optional


# Module-level flag: tracks whether GEE is ready for use
GEE_READY = False


def init_gee():
    """Initialize Google Earth Engine with service account credentials.

    On failure (missing key file, invalid credentials, etc.), sets GEE_READY=False
    and logs a warning. Prevents crashes on missing keys during startup.
    """
    global GEE_READY
    try:
        import ee  # lazy import — earthengine-api not required at module load time
        key_path = os.environ.get("GEE_SERVICE_ACCOUNT_KEY", "/secrets/gee-sa-key.json")
        with open(key_path) as f:
            key_data = json.load(f)
        credentials = ee.ServiceAccountCredentials(
            email=key_data["client_email"],
            key_data=json.dumps(key_data)
        )
        ee.Initialize(credentials)
        GEE_READY = True
    except Exception as e:
        GEE_READY = False
        print(f"WARNING: GEE initialization failed: {e}")


def _mask_clouds(image):
    """Apply cloud mask using QA60 band (bits 10 and 11)."""
    import ee  # lazy import
    qa = image.select("QA60")
    mask = (
        qa.bitwiseAnd(1 << 10).eq(0)
        .And(qa.bitwiseAnd(1 << 11).eq(0))
    )
    return image.updateMask(mask)


def _add_ndvi(image):
    """Add NDVI band using B8 (NIR) and B4 (Red)."""
    return image.addBands(
        image.normalizedDifference(["B8", "B4"]).rename("NDVI")
    )


def _compute_year(
    polygon_coords: list,
    year: int,
    start_month: int,
    end_month: int,
) -> Optional[dict]:
    """Compute NDVI metrics for a single year using Google Earth Engine.

    Returns a dict with year, ndvi, valid_pixel_pct, observation_date,
    or None if no valid imagery is available.
    """
    import ee  # lazy import — GEE must be initialized before calling this

    geometry = ee.Geometry.Polygon(polygon_coords)
    last_day = calendar.monthrange(year, end_month)[1]
    start = f"{year}-{start_month:02d}-01"
    end = f"{year}-{end_month:02d}-{last_day:02d}"

    collection = (
        ee.ImageCollection("COPERNICUS/S2_SR_HARMONIZED")
        .filterBounds(geometry)
        .filterDate(start, end)
        .map(_mask_clouds)
        .map(_add_ndvi)
    )

    if collection.size().getInfo() == 0:
        return None

    composite = collection.median()

    mean_stats = composite.select("NDVI").reduceRegion(
        reducer=ee.Reducer.mean(),
        geometry=geometry,
        scale=10,
        maxPixels=1e9,
    ).getInfo()

    ndvi_val = mean_stats.get("NDVI")
    if ndvi_val is None:
        return None

    coverage = composite.select("NDVI").mask().reduceRegion(
        reducer=ee.Reducer.mean(),
        geometry=geometry,
        scale=10,
        maxPixels=1e9,
    ).getInfo()
    valid_pct = (coverage.get("NDVI") or 0.0) * 100

    mid_month = (start_month + end_month) // 2
    observation_date = f"{year}-{mid_month:02d}-15"

    return {
        "year": year,
        "ndvi": round(ndvi_val, 4),
        "valid_pixel_pct": round(valid_pct, 1),
        "observation_date": observation_date,
    }


def derive_metrics(yearly: list) -> dict:
    """Compute vegetation health metrics from a list of yearly NDVI values.

    Pure function — no GEE needed. Input list may contain None for years
    with no valid imagery.

    Args:
        yearly: List of dicts with keys (year, ndvi, ...) or None entries.

    Returns:
        Dict with current_ndvi, baseline_ndvi, change_pct, trend, condition.
    """
    valid = [y for y in yearly if y is not None and y.get("ndvi") is not None]

    if not valid:
        return {
            "current_ndvi": 0.0,
            "baseline_ndvi": 0.0,
            "change_pct": 0.0,
            "trend": "stable",
            "condition": "poor",
        }

    # Baseline: mean of first 3 valid years
    baseline_pool = valid[:3]
    baseline = sum(y["ndvi"] for y in baseline_pool) / len(baseline_pool)

    # Current: last valid year's NDVI
    current = valid[-1]["ndvi"]

    # Change percentage relative to baseline
    change_pct = ((current - baseline) / baseline * 100) if baseline else 0.0

    # Trend: linear regression slope over all valid points
    n = len(valid)
    xs = list(range(n))
    ys = [y["ndvi"] for y in valid]
    x_mean = sum(xs) / n
    y_mean = sum(ys) / n
    numer = sum((x - x_mean) * (y - y_mean) for x, y in zip(xs, ys))
    denom = sum((x - x_mean) ** 2 for x in xs)
    slope = numer / denom if denom else 0.0

    if slope > 0.01:
        trend = "improving"
    elif slope < -0.01:
        trend = "degrading"
    else:
        trend = "stable"

    # Condition based on current NDVI value
    if current >= 0.6:
        condition = "good"
    elif current >= 0.3:
        condition = "fair"
    else:
        condition = "poor"

    return {
        "current_ndvi": round(current, 4),
        "baseline_ndvi": round(baseline, 4),
        "change_pct": round(change_pct, 2),
        "trend": trend,
        "condition": condition,
    }


def compute_ndvi(
    polygon_geojson: dict,
    season_start_month: int,
    season_end_month: int,
    year_start: int,
    year_end: int,
) -> dict:
    """Compute NDVI for a given polygon across a range of years.

    Args:
        polygon_geojson: GeoJSON dict with a "coordinates" key (Polygon geometry).
        season_start_month: First month of the growing season (1-12).
        season_end_month: Last month of the growing season (1-12).
        year_start: First year of the analysis range (inclusive).
        year_end: Last year of the analysis range (inclusive).

    Returns:
        Dict with yearly results, aggregated metrics, and metadata.
    """
    coords = polygon_geojson["coordinates"]
    yearly_raw = [
        _compute_year(coords, year, season_start_month, season_end_month)
        for year in range(year_start, year_end + 1)
    ]

    metrics = derive_metrics(yearly_raw)
    valid_points = [y for y in yearly_raw if y is not None]
    last_obs = (
        valid_points[-1]["observation_date"]
        if valid_points
        else f"{year_end}-10-15"
    )

    return {
        "yearly": yearly_raw,
        **metrics,
        "last_observation": last_obs,
        "resolution_m": 10,
        "source": "Sentinel-2",
    }
