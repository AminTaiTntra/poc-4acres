import { ee, initEarthEngine } from './earthEngine'
import { evaluate } from './util'

const DEM_DATASET = 'USGS/SRTMGL1_003'
const SCALE_M = 30

/**
 * FA-15: terrain context for a patch — elevation range and slope, derived
 * from the SRTM 30m DEM. Slope is computed once via ee.Terrain.slope and
 * reduced with mean+max in a single combined reducer (one server round trip
 * instead of two).
 */
async function getTerrainData(lat, lng, radiusM) {
  await initEarthEngine()

  const geometry = ee.Geometry.Point([lng, lat]).buffer(radiusM)
  const dem = ee.Image(DEM_DATASET).select('elevation')
  const slope = ee.Terrain.slope(dem)

  const elevationStats = dem.reduceRegion({
    reducer: ee.Reducer.minMax(),
    geometry,
    scale: SCALE_M,
    maxPixels: 1e9,
  })

  const slopeStats = slope.reduceRegion({
    reducer: ee.Reducer.mean().combine({ reducer2: ee.Reducer.max(), sharedInputs: true }),
    geometry,
    scale: SCALE_M,
    maxPixels: 1e9,
  })

  const [elevation, slopeResult] = await Promise.all([
    evaluate(elevationStats),
    evaluate(slopeStats),
  ])

  const avgSlopeDeg = slopeResult.slope_mean || 0
  const maxSlopeDeg = slopeResult.slope_max || 0

  return {
    elevationMinM: elevation.elevation_min || 0,
    elevationMaxM: elevation.elevation_max || 0,
    avgSlopeDeg,
    maxSlopeDeg,
    terrainClass: classifyTerrain(avgSlopeDeg),
  }
}

// Thresholds chosen to match the ticket's own worked example (17deg avg -> "Hilly").
function classifyTerrain(avgSlopeDeg) {
  if (avgSlopeDeg < 5) return 'Flat'
  if (avgSlopeDeg < 15) return 'Rolling'
  if (avgSlopeDeg < 25) return 'Hilly'
  return 'Steep'
}

export { getTerrainData }
