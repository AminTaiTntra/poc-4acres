'use client'

import Map from 'react-map-gl'
import MapboxDraw from '@mapbox/mapbox-gl-draw'
import '@mapbox/mapbox-gl-draw/dist/mapbox-gl-draw.css'
import { useRef } from 'react'
import type { MapRef } from 'react-map-gl'

interface Props {
  onPolygonDrawn: (coords: number[][] | null) => void
}

export function DrawMap({ onPolygonDrawn }: Props) {
  const mapRef = useRef<MapRef>(null)
  const drawRef = useRef<MapboxDraw | null>(null)

  function handleLoad() {
    const map = mapRef.current?.getMap()
    if (!map) return

    const draw = new MapboxDraw({
      displayControlsDefault: false,
      controls: { polygon: true, trash: true },
    })
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    map.addControl(draw as any)
    drawRef.current = draw

    map.on('draw.create', updatePolygon)
    map.on('draw.update', updatePolygon)
    map.on('draw.delete', () => onPolygonDrawn(null))
  }

  function updatePolygon() {
    const features = drawRef.current?.getAll().features ?? []
    if (features.length > 0) {
      const coords = (features[0].geometry as GeoJSON.Polygon).coordinates[0] as number[][]
      onPolygonDrawn(coords)
    }
  }

  return (
    <Map
      ref={mapRef}
      mapboxAccessToken={process.env.NEXT_PUBLIC_MAPBOX_TOKEN}
      initialViewState={{ longitude: 0, latitude: 20, zoom: 2 }}
      style={{ width: '100%', height: '100%' }}
      mapStyle="mapbox://styles/mapbox/satellite-v9"
      onLoad={handleLoad}
    />
  )
}
