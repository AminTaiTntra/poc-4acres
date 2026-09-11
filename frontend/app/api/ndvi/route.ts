import { NextRequest, NextResponse } from 'next/server'
import { readFileSync } from 'fs'

export const runtime = 'nodejs'

// Top-level require so Next.js Node File Tracing includes the package in standalone builds
// eslint-disable-next-line @typescript-eslint/no-require-imports, @typescript-eslint/no-explicit-any
const ee: any = require('@google/earthengine')

let geeReady = false
let initPromise: Promise<boolean> | null = null

function initGee(): Promise<boolean> {
  if (geeReady) return Promise.resolve(true)
  if (initPromise) return initPromise
  initPromise = new Promise<boolean>((resolve) => {
    try {
      const keyPath = process.env.GEE_SERVICE_ACCOUNT_KEY ?? '/secrets/gee-sa-key.json'
      const privateKey = JSON.parse(readFileSync(keyPath, 'utf8'))
      ee.data.authenticateViaPrivateKey(
        privateKey,
        () => ee.initialize(null, null,
          () => { geeReady = true; resolve(true) },
          (e: Error) => { console.warn('[ndvi] GEE init failed:', e); initPromise = null; resolve(false) }
        ),
        (e: Error) => { console.warn('[ndvi] GEE auth failed:', e); initPromise = null; resolve(false) }
      )
    } catch (e) {
      console.warn('[ndvi] GEE setup error:', e)
      initPromise = null
      resolve(false)
    }
  })
  return initPromise
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function evaluate<T>(eeObj: any): Promise<T | null> {
  return new Promise<T | null>((resolve) => {
    eeObj.evaluate((result: T, error: Error | undefined) => {
      resolve(error ? null : result)
    })
  })
}

async function computeYear(
  coords: number[][][],
  year: number,
  startMonth: number,
  endMonth: number,
// eslint-disable-next-line @typescript-eslint/no-explicit-any
): Promise<any> {
  try {
    const geometry  = ee.Geometry.Polygon(coords)
    const lastDay   = new Date(year, endMonth, 0).getDate() // JS: day 0 of next month = last day of endMonth
    const start     = `${year}-${String(startMonth).padStart(2, '0')}-01`
    const end       = `${year}-${String(endMonth).padStart(2, '0')}-${String(lastDay).padStart(2, '0')}`

    const collection = ee.ImageCollection('COPERNICUS/S2_SR_HARMONIZED')
      .filterBounds(geometry)
      .filterDate(start, end)
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      .map((img: any) => {
        const qa   = img.select('QA60')
        const mask = qa.bitwiseAnd(1 << 10).eq(0).and(qa.bitwiseAnd(1 << 11).eq(0))
        return img.updateMask(mask)
      })
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      .map((img: any) => img.addBands(img.normalizedDifference(['B8', 'B4']).rename('NDVI')))

    const size = await evaluate<number>(collection.size())
    if (!size) return null

    const composite  = collection.median()
    const meanStats  = await evaluate<Record<string, number>>(
      composite.select('NDVI').reduceRegion({
        reducer:   ee.Reducer.mean(),
        geometry,
        scale:     10,
        maxPixels: 1e9,
      })
    )
    if (meanStats?.NDVI == null) return null

    const coverage = await evaluate<Record<string, number>>(
      composite.select('NDVI').mask().reduceRegion({
        reducer:   ee.Reducer.mean(),
        geometry,
        scale:     10,
        maxPixels: 1e9,
      })
    )

    const midMonth = Math.floor((startMonth + endMonth) / 2)
    return {
      year,
      ndvi:             Math.round(meanStats.NDVI * 10000) / 10000,
      valid_pixel_pct:  Math.round(((coverage?.NDVI ?? 0) * 100) * 10) / 10,
      observation_date: `${year}-${String(midMonth).padStart(2, '0')}-15`,
    }
  } catch (e) {
    console.warn(`[ndvi] year ${year} error:`, e)
    return null
  }
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function deriveMetrics(yearly: any[]) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const valid = yearly.filter((y: any) => y !== null && y.ndvi != null)

  if (valid.length === 0) {
    return { current_ndvi: 0, baseline_ndvi: 0, change_pct: 0, trend: 'stable', condition: 'poor' }
  }

  // Baseline: mean of first 3 valid years
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const pool     = valid.slice(0, 3)
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const baseline = pool.reduce((s: number, y: any) => s + y.ndvi, 0) / pool.length
  const current  = valid[valid.length - 1].ndvi
  const changePct = baseline ? ((current - baseline) / baseline) * 100 : 0

  // Linear regression slope across all valid points to determine trend
  const n     = valid.length
  const xMean = (n - 1) / 2
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const yMean = valid.reduce((s: number, y: any) => s + y.ndvi, 0) / n
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const numer = valid.reduce((s: number, y: any, i: number) => s + (i - xMean) * (y.ndvi - yMean), 0)
  const denom = valid.reduce((s: number, _: unknown, i: number) => s + (i - xMean) ** 2, 0)
  const slope = denom ? numer / denom : 0

  return {
    current_ndvi:  Math.round(current  * 10000) / 10000,
    baseline_ndvi: Math.round(baseline * 10000) / 10000,
    change_pct:    Math.round(changePct * 100) / 100,
    trend:     slope > 0.01 ? 'improving' : slope < -0.01 ? 'degrading' : 'stable',
    condition: current >= 0.6 ? 'good' : current >= 0.3 ? 'fair' : 'poor',
  }
}

export async function POST(request: NextRequest) {
  const ready = await initGee()
  if (!ready) {
    return NextResponse.json({ error: 'GEE not initialized' }, { status: 503 })
  }

  const { polygon, season_start_month, season_end_month, year_start, year_end } =
    await request.json()

  const coords: number[][][] = polygon.coordinates
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const yearlyRaw: any[] = []
  for (let year = year_start; year <= year_end; year++) {
    yearlyRaw.push(await computeYear(coords, year, season_start_month, season_end_month))
  }

  const metrics  = deriveMetrics(yearlyRaw)
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const validPts = yearlyRaw.filter((y: any) => y !== null)
  const lastObs  = validPts.length > 0
    ? validPts[validPts.length - 1].observation_date
    : `${year_end}-10-15`

  return NextResponse.json({
    yearly: yearlyRaw,
    ...metrics,
    last_observation: lastObs,
    resolution_m:     10,
    source:           'Sentinel-2',
  })
}
