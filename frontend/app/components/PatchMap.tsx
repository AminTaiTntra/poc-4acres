'use client'

import Map, { Source, Layer } from 'react-map-gl'
import { useEffect, useRef, useState } from 'react'
import type { MapRef } from 'react-map-gl'
import type { Patch } from '../lib/types'
import type { FeatureCollection, Polygon } from 'geojson'
import { MapControls, MAP_STYLES } from './MapControls'

interface Props {
  patches: Patch[]
  selectedPatchId: string | null
  onPatchSelect: (id: string) => void
}

export function PatchMap({ patches, selectedPatchId, onPatchSelect }: Props) {
  const mapRef   = useRef<MapRef>(null)
  const [styleKey, setStyleKey] = useState('satellite')

  const geojson: FeatureCollection<Polygon> = {
    type: 'FeatureCollection',
    features: patches.map(p => ({
      type: 'Feature',
      id: p.id,
      properties: { id: p.id, name: p.name, selected: p.id === selectedPatchId, status: p.status },
      geometry: p.boundaryGeoJson as Polygon,
    })),
  }

  useEffect(() => {
    const selected = patches.find(p => p.id === selectedPatchId)
    if (!selected || !mapRef.current) return
    mapRef.current.flyTo({
      center:   [selected.centerLng, selected.centerLat],
      zoom:     15,
      duration: 1500,
    })
  }, [selectedPatchId, patches])

  return (
    <div className="relative w-full h-full">
      <Map
        ref={mapRef}
        mapboxAccessToken={process.env.NEXT_PUBLIC_MAPBOX_TOKEN}
        initialViewState={{ longitude: 20, latitude: 10, zoom: 2 }}
        style={{ width: '100%', height: '100%' }}
        mapStyle={MAP_STYLES[styleKey]}
        interactiveLayerIds={['patch-fill']}
        onClick={e => {
          const feature = e.features?.[0]
          if (feature?.properties?.id) onPatchSelect(feature.properties.id)
        }}
      >
        <Source id="patches" type="geojson" data={geojson}>
          <Layer
            id="patch-fill"
            type="fill"
            paint={{
              'fill-color': [
                'case',
                ['==', ['get', 'selected'], true], '#10b981',
                ['==', ['get', 'status'], 'AVAILABLE'], '#f59e0b',
                '#10b981',
              ],
              'fill-opacity': ['case', ['==', ['get', 'selected'], true], 0.5, 0.3],
            }}
          />
          <Layer
            id="patch-outline"
            type="line"
            paint={{ 'line-color': '#34d399', 'line-width': 2 }}
          />
        </Source>
      </Map>

      <MapControls
        mapRef={mapRef}
        currentStyle={styleKey}
        onStyleChange={setStyleKey}
      />
    </div>
  )
}
