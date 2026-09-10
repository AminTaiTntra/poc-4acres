import pytest
from fastapi.testclient import TestClient
from unittest.mock import patch


@pytest.fixture
def client(no_gee_init):
    """Provide a test client with GEE init patched."""
    from main import app
    return TestClient(app)


def test_health_returns_ok(client):
    """Health endpoint always returns 200."""
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


def test_ndvi_endpoint_returns_503_when_gee_not_ready(client):
    """When GEE is not initialized, /ndvi returns 503."""
    payload = {
        "polygon": {"type": "Polygon", "coordinates": [[[77.0, 20.0], [77.1, 20.0], [77.1, 20.1], [77.0, 20.1], [77.0, 20.0]]]},
        "season_start_month": 10,
        "season_end_month": 11,
        "year_start": 2022,
        "year_end": 2024,
    }
    resp = client.post("/ndvi", json=payload)
    assert resp.status_code == 503
    assert "not initialized" in resp.json()["detail"].lower()


def test_ndvi_endpoint_returns_501_when_gee_ready_but_not_implemented(client):
    """When GEE is ready but compute_ndvi not implemented, /ndvi returns 501."""
    payload = {
        "polygon": {"type": "Polygon", "coordinates": [[[77.0, 20.0], [77.1, 20.0], [77.1, 20.1], [77.0, 20.1], [77.0, 20.0]]]},
        "season_start_month": 10,
        "season_end_month": 11,
        "year_start": 2022,
        "year_end": 2024,
    }

    # Patch GEE_READY to True so we pass the guard, then hit NotImplementedError
    with patch("gee_client.GEE_READY", True):
        resp = client.post("/ndvi", json=payload)
        assert resp.status_code == 501
        assert "not yet implemented" in resp.json()["detail"].lower()
