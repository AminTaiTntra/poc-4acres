import pytest
from gee_client import derive_metrics


def test_improving_trend():
    yearly = [
        {"year": 2022, "ndvi": 0.61},
        {"year": 2023, "ndvi": 0.64},
        {"year": 2024, "ndvi": 0.66},
        {"year": 2025, "ndvi": 0.69},
        {"year": 2026, "ndvi": 0.72},
    ]
    result = derive_metrics(yearly)
    assert result["trend"] == "improving"
    assert abs(result["baseline_ndvi"] - (0.61 + 0.64 + 0.66) / 3) < 0.01
    assert result["current_ndvi"] == 0.72
    assert result["condition"] == "good"
    assert result["change_pct"] > 0


def test_degrading_trend():
    yearly = [
        {"year": 2022, "ndvi": 0.72},
        {"year": 2023, "ndvi": 0.68},
        {"year": 2024, "ndvi": 0.63},
    ]
    result = derive_metrics(yearly)
    assert result["trend"] == "degrading"


def test_null_year_excluded_from_trend():
    yearly = [
        {"year": 2022, "ndvi": 0.61},
        None,
        {"year": 2024, "ndvi": 0.64},
    ]
    result = derive_metrics(yearly)
    assert result["current_ndvi"] == 0.64
    assert result["trend"] in ("improving", "stable", "degrading")


def test_condition_fair():
    yearly = [{"year": 2022, "ndvi": 0.45}, {"year": 2023, "ndvi": 0.48}]
    assert derive_metrics(yearly)["condition"] == "fair"


def test_condition_poor():
    yearly = [{"year": 2022, "ndvi": 0.20}, {"year": 2023, "ndvi": 0.22}]
    assert derive_metrics(yearly)["condition"] == "poor"


def test_all_null_years_returns_safe_defaults():
    result = derive_metrics([None, None, None])
    assert result["trend"] == "stable"
    assert result["condition"] == "poor"
