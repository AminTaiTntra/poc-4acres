'use client'

import Map, { Marker } from 'react-map-gl'
import { useEffect, useRef } from 'react'
import type { MapRef } from 'react-map-gl'
import type { Patch } from '../lib/types'

interface Props {
  patches: Patch[]
}

const globeProjection = { name: 'globe' }
const globeFog = { color: '#0a1628', 'high-color': '#1a3a6b', 'horizon-blend': 0.02 }

export function GlobeHero({ patches }: Props) {
  const mapRef = useRef<MapRef>(null)
  const animRef = useRef<number>(0)

  function startRotation() {
    function rotate() {
      const map = mapRef.current?.getMap()
      if (!map) return
      map.setBearing((map.getBearing() + 0.05) % 360)
      animRef.current = requestAnimationFrame(rotate)
    }
    animRef.current = requestAnimationFrame(rotate)
  }

  useEffect(() => {
    return () => cancelAnimationFrame(animRef.current)
  }, [])

  return (
    <Map
      ref={mapRef}
      mapboxAccessToken={process.env.NEXT_PUBLIC_MAPBOX_TOKEN}
      initialViewState={{ longitude: 0, latitude: 20, zoom: 1.5 }}
      style={{ width: '100%', height: '100vh' }}
      mapStyle="mapbox://styles/mapbox/satellite-v9"
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      projection={globeProjection as any}
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      fog={globeFog as any}
      onLoad={startRotation}
      onError={e => { if (!e.error?.message?.includes('LngLat')) console.error(e) }}
      interactiveLayerIds={[]}
    >
      {patches.map(p => (
        <Marker
          key={p.id}
          longitude={p.centerLng}
          latitude={p.centerLat}
          anchor="center"
        >
          <div
            className="w-3 h-3 rounded-full bg-emerald-400 shadow-lg shadow-emerald-400/50
                       ring-2 ring-emerald-300 ring-offset-1 ring-offset-transparent"
            title={p.name}
          />
        </Marker>
      ))}
    </Map>
  )
}
