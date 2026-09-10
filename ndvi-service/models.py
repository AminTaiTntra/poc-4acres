from pydantic import BaseModel
from typing import Optional


class NdviRequest(BaseModel):
    polygon: dict
    season_start_month: int
    season_end_month: int
    year_start: int = 2022
    year_end: int


class NdviYearlyPoint(BaseModel):
    year: int
    ndvi: Optional[float] = None
    valid_pixel_pct: Optional[float] = None
    observation_date: str


class NdviResponse(BaseModel):
    yearly: list[Optional[NdviYearlyPoint]]
    current_ndvi: float
    baseline_ndvi: float
    change_pct: float
    trend: str
    condition: str
    last_observation: str
    resolution_m: int
    source: str
