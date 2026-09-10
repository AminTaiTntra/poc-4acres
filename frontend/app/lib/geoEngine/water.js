import { ee, initEarthEngine } from './earthEngine'
import { evaluate } from './util'

const DATASET = 'JRC/GSW1_4/GlobalSurfaceWater'
const PERIOD = '1984-2021'
const SCALE_M = 30

/**
 * FA-15: surface water context for a patch, from JRC Global Surface Water.
 *
 * `occurrence` (0-100) is how often a pixel was ever seen as water over the
 * whole 1984-2021 record — we mask to occurrence > 0 to isolate "any water
 * ever detected" pixels, then measure their real area, plus the average
 * `seasonality` (months/year with water in the most recent year) and
 * `recurrence` (how consistently, year over year, the record shows water)
 * across just those pixels.
 *
 * At 30m resolution a 4-acre patch is only a handful of pixels, so this is
 * necessarily a coarse signal — the classification below is deliberately
 * simple, and callers should present it as "detected from available
 * satellite observations," not a definitive survey.
 */
async function getWaterData(lat, lng, radiusM) {
  await initEarthEngine()

  const geometry = ee.Geometry.Point([lng, lat]).buffer(radiusM)
  const gsw = ee.Image(DATASET)
  const waterMask = gsw.select('occurrence').gt(0)

  const areaStats = ee.Image.pixelArea()
    .updateMask(waterMask)
    .reduceRegion({ reducer: ee.Reducer.sum(), geometry, scale: SCALE_M, maxPixels: 1e9 })

  const seasonalityStats = gsw.select('seasonality')
    .updateMask(waterMask)
    .reduceRegion({ reducer: ee.Reducer.mean(), geometry, scale: SCALE_M, maxPixels: 1e9 })

  const recurrenceStats = gsw.select('recurrence')
    .updateMask(waterMask)
    .reduceRegion({ reducer: ee.Reducer.mean(), geometry, scale: SCALE_M, maxPixels: 1e9 })

  const [area, seasonality, recurrence] = await Promise.all([
    evaluate(areaStats),
    evaluate(seasonalityStats),
    evaluate(recurrenceStats),
  ])

  const surfaceWaterHa = (area.area || 0) / 10000
  const avgSeasonalityMonths = seasonality.seasonality || 0
  const recurrencePercent = recurrence.recurrence || 0

  return {
    surfaceWaterHa,
    occurrenceClass: classifyOccurrence(surfaceWaterHa, avgSeasonalityMonths),
    recurrencePercent,
    period: PERIOD,
  }
}

function classifyOccurrence(surfaceWaterHa, avgSeasonalityMonths) {
  if (surfaceWaterHa <= 0) return 'None'
  return avgSeasonalityMonths >= 10 ? 'Permanent' : 'Seasonal'
}

export { getWaterData }
