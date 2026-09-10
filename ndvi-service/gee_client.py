import json
import os


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


def compute_ndvi(
    polygon_geojson: dict,
    season_start_month: int,
    season_end_month: int,
    year_start: int,
    year_end: int,
) -> dict:
    """Compute NDVI for a given polygon and time period.

    Implemented in Task 2.
    """
    raise NotImplementedError
