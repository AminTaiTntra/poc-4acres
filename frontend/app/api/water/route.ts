import { NextRequest, NextResponse } from 'next/server'
import { getWaterData } from '../../lib/geoEngine/water'

// Reads request-time query params and calls out to Earth Engine, so this
// can never be statically prerendered.
export const dynamic = 'force-dynamic'
export const runtime = 'nodejs'

export async function GET(request: NextRequest) {
  const lat = Number(request.nextUrl.searchParams.get('lat'))
  const lng = Number(request.nextUrl.searchParams.get('lng'))
  const radiusM = Number(request.nextUrl.searchParams.get('radiusM'))

  if (!Number.isFinite(lat) || !Number.isFinite(lng) || !Number.isFinite(radiusM)) {
    return NextResponse.json(
      { error: 'lat, lng and radiusM query params are required numbers' },
      { status: 400 }
    )
  }

  try {
    const data = await getWaterData(lat, lng, radiusM)
    return NextResponse.json(data)
  } catch (err) {
    console.error('[api/water] Earth Engine query failed:', err)
    return NextResponse.json(
      { error: 'earth-engine query failed', message: String(err) },
      { status: 502 }
    )
  }
}
