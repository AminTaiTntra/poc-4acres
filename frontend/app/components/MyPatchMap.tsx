'use client'

import Map from 'react-map-gl'
import { useRef, useState } from 'react'
import type { MapRef } from 'react-map-gl'
import type { Patch } from '../lib/types'
import type { Feature, Polygon } from 'geojson'
import { MapControls, MAP_STYLES } from './MapControls'

interface Props {
  patch: Patch
}

export function MyPatchMap({ patch }: Props) {
  const mapRef = useRef<MapRef>(null)
  const [styleKey, setStyleKey] = useState('satellite')

  function handleLoad() {
    const map = mapRef.current?.getMap()
    if (!map) return

    map.addSource('mapbox-dem', {
      type: 'raster-dem',
      url: 'mapbox://mapbox.mapbox-terrain-dem-v1',
      tileSize: 512,
    })

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(map as any).setTerrain({ source: 'mapbox-dem', exaggeration: 1.5 })

    map.addLayer({
      id: 'sky',
      type: 'sky',
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      paint: { 'sky-type': 'atmosphere', 'sky-atmosphere-sun': [0.0, 90.0], 'sky-atmosphere-sun-intensity': 15 } as any,
    })

    const boundaryFeature: Feature<Polygon> = {
      type: 'Feature',
      geometry: patch.boundaryGeoJson as Polygon,
      properties: {},
    }

    map.addSource('patch-boundary', { type: 'geojson', data: boundaryFeature })
    map.addLayer({
      id: 'patch-fill',
      type: 'fill',
      source: 'patch-boundary',
      paint: { 'fill-color': '#10b981', 'fill-opacity': 0.25 },
    })
    map.addLayer({
      id: 'patch-outline',
      type: 'line',
      source: 'patch-boundary',
      paint: { 'line-color': '#10b981', 'line-width': 3, 'line-blur': 1 },
    })

    map.flyTo({
      center:   [patch.centerLng, patch.centerLat],
      zoom:     15.5,
      pitch:    60,
      bearing:  -20,
      duration: 3500,
      essential: true,
    })
  }

  return (
    <div className="relative w-full h-full">
      <Map
        ref={mapRef}
        mapboxAccessToken={process.env.NEXT_PUBLIC_MAPBOX_TOKEN}
        initialViewState={{ longitude: patch.centerLng, latitude: patch.centerLat, zoom: 5 }}
        style={{ width: '100%', height: '100%' }}
        mapStyle={MAP_STYLES[styleKey]}
        onLoad={handleLoad}
        onError={e => { if (!e.error?.message?.includes('LngLat')) console.error(e) }}
      />

      <MapControls
        mapRef={mapRef}
        currentStyle={styleKey}
        onStyleChange={setStyleKey}
        show3D
        initialIs3D
        flyTo={{ lng: patch.centerLng, lat: patch.centerLat, zoom: 15.5, pitch: 60, bearing: -20 }}
      />
    </div>
  )
}
