import pytest
from unittest.mock import patch


@pytest.fixture(autouse=True)
def no_gee_init():
    """Prevent actual GEE initialization in all tests."""
    with patch("gee_client.init_gee"):
        yield
