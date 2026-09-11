import { ee, initEarthEngine } from './earthEngine'
import { evaluate } from './util'

const JRC_DATASET = 'JRC/GSW1_4/GlobalSurfaceWater'
const JRC_PERIOD = '1984-2021'
const JRC_SCALE_M = 30

const DW_DATASET = 'GOOGLE/DYNAMICWORLD/V1'
const DW_WINDOW_DAYS = 180
const DW_SCALE_M = 10

/**
 * FA-15: surface water context for a patch — a historical view (JRC Global
 * Surface Water) plus a near-current view (Dynamic World), since JRC's
 * "1984-2021" composite is a fixed historical product that Google hasn't
 * (and may never) extend past 2021 — there's no way to make that specific
 * dataset "current". Dynamic World is a near-real-time land-cover classifier
 * (updated within days of each new Sentinel-2 pass) that includes a "water"
 * class, so it fills the "as of today" gap JRC can't.
 *
 * The two answer different questions: JRC's `occurrence`/`recurrence` describe
 * how persistent water has been at this location over 38 years; Dynamic
 * World's `water` band describes whether this location currently looks like
 * water. At 30m JRC resolution a 4-acre patch is only 2-3 pixels — Dynamic
 * World's 10m resolution gives ~10x more pixels over the same small patch,
 * so it can often resolve a signal where JRC can't.
 */
async function getWaterData(lat, lng, radiusM) {
  await initEarthEngine()

  const geometry = ee.Geometry.Point([lng, lat]).buffer(radiusM)

  const [historical, current] = await Promise.all([
    getHistorical(geometry),
    getCurrent(geometry),
  ])

  return { ...historical, ...current }
}

async function getHistorical(geometry) {
  const gsw = ee.Image(JRC_DATASET)
  const waterMask = gsw.select('occurrence').gt(0)

  const areaStats = ee.Image.pixelArea()
    .updateMask(waterMask)
    .reduceRegion({ reducer: ee.Reducer.sum(), geometry, scale: JRC_SCALE_M, maxPixels: 1e9 })

  const seasonalityStats = gsw.select('seasonality')
    .updateMask(waterMask)
    .reduceRegion({ reducer: ee.Reducer.mean(), geometry, scale: JRC_SCALE_M, maxPixels: 1e9 })

  const recurrenceStats = gsw.select('recurrence')
    .updateMask(waterMask)
    .reduceRegion({ reducer: ee.Reducer.mean(), geometry, scale: JRC_SCALE_M, maxPixels: 1e9 })

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
    period: JRC_PERIOD,
  }
}

async function getCurrent(geometry) {
  const now = new Date()
  const start = new Date(now.getTime() - DW_WINDOW_DAYS * 24 * 3600 * 1000)

  const recentScenes = ee.ImageCollection(DW_DATASET)
    .filterDate(start.toISOString(), now.toISOString())
    .filterBounds(geometry)

  const sceneCount = await evaluate(recentScenes.size())
  if (typeof sceneCount !== 'number' || sceneCount <= 0) {
    return { currentWaterPercent: 0, latestSceneDate: null, currentWindowDays: DW_WINDOW_DAYS }
  }

  const waterStats = recentScenes.select('water').mean()
    .reduceRegion({ reducer: ee.Reducer.mean(), geometry, scale: DW_SCALE_M, maxPixels: 1e9 })

  const latestSceneTimestamp = recentScenes
    .sort('system:time_start', false)
    .first()
    .get('system:time_start')

  const [stats, timestamp] = await Promise.all([
    evaluate(waterStats),
    evaluate(latestSceneTimestamp),
  ])

  return {
    currentWaterPercent: (stats.water || 0) * 100,
    latestSceneDate: typeof timestamp === 'number' ? new Date(timestamp).toISOString() : null,
    currentWindowDays: DW_WINDOW_DAYS,
  }
}

function classifyOccurrence(surfaceWaterHa, avgSeasonalityMonths) {
  if (surfaceWaterHa <= 0) return 'None'
  return avgSeasonalityMonths >= 10 ? 'Permanent' : 'Seasonal'
}

export { getWaterData }
