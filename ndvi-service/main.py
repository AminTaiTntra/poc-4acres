from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
import gee_client
from models import NdviRequest, NdviResponse


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Startup: initialize GEE (with graceful degradation on failure)."""
    gee_client.init_gee()
    yield


app = FastAPI(lifespan=lifespan)


@app.get("/health")
def health():
    """Health check endpoint — always returns 200."""
    return {"status": "ok"}


@app.post("/ndvi", response_model=NdviResponse)
def compute_ndvi_endpoint(req: NdviRequest):
    """Compute NDVI for the given polygon.

    Returns:
    - 503 if GEE is not initialized
    - 501 if compute_ndvi raises NotImplementedError
    - 500 for other errors
    """
    # Check if GEE is ready
    if not gee_client.GEE_READY:
        raise HTTPException(status_code=503, detail="GEE not initialized")

    try:
        result = gee_client.compute_ndvi(
            req.polygon,
            req.season_start_month,
            req.season_end_month,
            req.year_start,
            req.year_end,
        )
        return result
    except NotImplementedError:
        raise HTTPException(status_code=501, detail="GEE computation not yet implemented")
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
