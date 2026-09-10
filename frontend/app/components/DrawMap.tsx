'use client'

import Map from 'react-map-gl'
import MapboxDraw from '@mapbox/mapbox-gl-draw'
import '@mapbox/mapbox-gl-draw/dist/mapbox-gl-draw.css'
import { useRef, useState } from 'react'
import type { MapRef } from 'react-map-gl'

interface Props {
  onPolygonDrawn: (coords: number[][] | null) => void
}

const MAP_STYLES: Record<string, string> = {
  satellite: 'mapbox://styles/mapbox/satellite-v9',
  dark:      'mapbox://styles/mapbox/dark-v11',
  outdoors:  'mapbox://styles/mapbox/outdoors-v12',
}

const STYLE_LABELS: Record<string, string> = {
  satellite: 'Satellite',
  dark:      'Dark',
  outdoors:  'Outdoors',
}

// ── Inline SVG icons ──────────────────────────────────────────────────────────

function IconNavigate() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polygon points="3 11 22 2 13 21 11 13 3 11" />
    </svg>
  )
}

function IconMap() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polygon points="1 6 1 22 8 18 16 22 23 18 23 2 16 6 8 2 1 6" />
      <line x1="8" y1="2" x2="8" y2="18" />
      <line x1="16" y1="6" x2="16" y2="22" />
    </svg>
  )
}

function IconCompass() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="10" />
      <polygon points="16.24 7.76 14.12 14.12 7.76 16.24 9.88 9.88 16.24 7.76" />
    </svg>
  )
}

function IconLocate() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="3" />
      <path d="M12 2v3M12 19v3M2 12h3M19 12h3" />
    </svg>
  )
}

function IconPitch() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
    </svg>
  )
}

function IconFullscreen() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3" />
    </svg>
  )
}

function IconLayers() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polygon points="12 2 2 7 12 12 22 7 12 2" />
      <polyline points="2 17 12 22 22 17" />
      <polyline points="2 12 12 17 22 12" />
    </svg>
  )
}

// ── Control button ────────────────────────────────────────────────────────────

function CtrlBtn({ onClick, active, title, children }: {
  onClick: () => void
  active?: boolean
  title: string
  children: React.ReactNode
}) {
  return (
    <button
      onClick={onClick}
      title={title}
      className={`w-10 h-10 flex items-center justify-center transition-colors
        ${active
          ? 'text-emerald-300 bg-emerald-900/40'
          : 'text-[#7dd4a8] hover:bg-[#1a3a2a] hover:text-emerald-200'}`}
    >
      {children}
    </button>
  )
}

function Divider() {
  return <div className="h-px bg-[#2a4a3a] w-full" />
}

// ── Main component ────────────────────────────────────────────────────────────

export function DrawMap({ onPolygonDrawn }: Props) {
  const mapRef   = useRef<MapRef>(null)
  const drawRef  = useRef<MapboxDraw | null>(null)

  const [mode,          setMode]          = useState<'navigate' | 'map'>('map')
  const [is3D,          setIs3D]          = useState(false)
  const [styleKey,      setStyleKey]      = useState('satellite')
  const [showLayers,    setShowLayers]    = useState(false)
  const [hasCoords,     setHasCoords]     = useState(false)

  // ── Map load ─────────────────────────────────────────────────────────────

  function handleLoad() {
    const map = mapRef.current?.getMap()
    if (!map) return

    const draw = new MapboxDraw({
      displayControlsDefault: false,
      controls: { polygon: true },
    })
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    map.addControl(draw as any)
    drawRef.current = draw

    map.on('draw.create', syncPolygon)
    map.on('draw.update', syncPolygon)
    map.on('draw.delete', clearPolygon)
  }

  function syncPolygon() {
    const features = drawRef.current?.getAll().features ?? []
    if (features.length > 0) {
      const coords = (features[0].geometry as GeoJSON.Polygon).coordinates[0] as number[][]
      setHasCoords(true)
      onPolygonDrawn(coords)
    }
  }

  function clearPolygon() {
    setHasCoords(false)
    onPolygonDrawn(null)
  }

  // ── Control handlers ──────────────────────────────────────────────────────

  function zoomIn()  { mapRef.current?.getMap()?.zoomIn({ duration: 200 }) }
  function zoomOut() { mapRef.current?.getMap()?.zoomOut({ duration: 200 }) }

  function resetNorth() {
    mapRef.current?.getMap()?.easeTo({ bearing: 0, pitch: 0, duration: 400 })
    setIs3D(false)
  }

  function geolocate() {
    navigator.geolocation?.getCurrentPosition(({ coords }) => {
      mapRef.current?.getMap()?.easeTo({
        center: [coords.longitude, coords.latitude],
        zoom: 14,
        duration: 800,
      })
    })
  }

  function toggle3D() {
    const map = mapRef.current?.getMap()
    if (!map) return
    if (is3D) {
      map.easeTo({ pitch: 0, duration: 400 })
      setIs3D(false)
    } else {
      map.easeTo({ pitch: 55, duration: 400 })
      setIs3D(true)
    }
  }

  function toggleFullscreen() {
    const container = mapRef.current?.getMap()?.getContainer()
    if (!container) return
    if (!document.fullscreenElement) {
      container.requestFullscreen?.()
    } else {
      document.exitFullscreen?.()
    }
  }

  function switchStyle(key: string) {
    setStyleKey(key)
    setShowLayers(false)
  }

  function handleClear() {
    drawRef.current?.deleteAll()
    setHasCoords(false)
    onPolygonDrawn(null)
  }

  function handleModeToggle(next: 'navigate' | 'map') {
    setMode(next)
    const map = mapRef.current?.getMap()
    if (!map || !drawRef.current) return
    if (next === 'navigate') {
      // Disable polygon drawing so user can freely pan
      drawRef.current.changeMode('simple_select')
    } else {
      drawRef.current.changeMode('draw_polygon')
    }
  }

  // ── Render ────────────────────────────────────────────────────────────────

  return (
    <div className="relative w-full h-full">
      <Map
        ref={mapRef}
        mapboxAccessToken={process.env.NEXT_PUBLIC_MAPBOX_TOKEN}
        initialViewState={{ longitude: 78, latitude: 20, zoom: 4 }}
        style={{ width: '100%', height: '100%' }}
        mapStyle={MAP_STYLES[styleKey]}
        onLoad={handleLoad}
      />

      {/* ── Navigate / Map toggle ── */}
      <div className="absolute top-4 left-1/2 -translate-x-1/2 z-10 flex rounded-lg overflow-hidden border border-[#2a4a3a] shadow-xl">
        {(['navigate', 'map'] as const).map((m) => (
          <button
            key={m}
            onClick={() => handleModeToggle(m)}
            className={`flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold tracking-widest uppercase transition-colors
              ${mode === m
                ? 'bg-[#1a3a2a] text-[#7dd4a8]'
                : 'bg-[#0d2318]/90 text-[#4a7a5a] hover:text-[#7dd4a8]'}`}
          >
            {m === 'navigate' ? <IconNavigate /> : <IconMap />}
            {m}
          </button>
        ))}
      </div>

      {/* ── Right-side control stack ── */}
      <div className="absolute right-4 top-16 z-10 flex flex-col gap-2">

        {/* Zoom */}
        <div className="flex flex-col bg-[#0d2318]/95 border border-[#2a4a3a] rounded-lg overflow-hidden shadow-lg backdrop-blur-sm">
          <CtrlBtn onClick={zoomIn}  title="Zoom in">
            <span className="text-lg font-light leading-none">+</span>
          </CtrlBtn>
          <Divider />
          <CtrlBtn onClick={zoomOut} title="Zoom out">
            <span className="text-lg font-light leading-none">−</span>
          </CtrlBtn>
        </div>

        {/* Compass · Locate · 3D · Fullscreen */}
        <div className="flex flex-col bg-[#0d2318]/95 border border-[#2a4a3a] rounded-lg overflow-hidden shadow-lg backdrop-blur-sm">
          <CtrlBtn onClick={resetNorth}      title="Reset north / pitch"><IconCompass /></CtrlBtn>
          <Divider />
          <CtrlBtn onClick={geolocate}       title="My location"><IconLocate /></CtrlBtn>
          <Divider />
          <CtrlBtn onClick={toggle3D} active={is3D} title={is3D ? 'Reset to 2D' : 'Tilt to 3D'}><IconPitch /></CtrlBtn>
          <Divider />
          <CtrlBtn onClick={toggleFullscreen} title="Fullscreen"><IconFullscreen /></CtrlBtn>
        </div>

        {/* Layer switcher */}
        <div className="relative">
          <div className="flex flex-col bg-[#0d2318]/95 border border-[#2a4a3a] rounded-lg overflow-hidden shadow-lg backdrop-blur-sm">
            <CtrlBtn onClick={() => setShowLayers(v => !v)} active={showLayers} title="Map style">
              <IconLayers />
            </CtrlBtn>
          </div>

          {showLayers && (
            <div className="absolute right-12 top-0 bg-[#0d2318]/95 border border-[#2a4a3a] rounded-lg overflow-hidden shadow-xl backdrop-blur-sm min-w-[120px]">
              {Object.keys(MAP_STYLES).map((key) => (
                <button
                  key={key}
                  onClick={() => switchStyle(key)}
                  className={`w-full text-left px-4 py-2.5 text-[11px] font-bold tracking-widest uppercase transition-colors
                    ${styleKey === key
                      ? 'text-emerald-300 bg-emerald-900/30'
                      : 'text-[#7dd4a8] hover:bg-[#1a3a2a]'}`}
                >
                  {STYLE_LABELS[key]}
                </button>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* ── Clear boundary ── */}
      {hasCoords && (
        <div className="absolute bottom-8 left-1/2 -translate-x-1/2 z-10">
          <button
            onClick={handleClear}
            className="flex items-center gap-2 bg-[#0d2318]/95 border border-[#2a4a3a] text-[#7dd4a8] hover:text-red-400 hover:border-red-900/60 px-5 py-2.5 rounded-lg text-[11px] font-bold tracking-widest uppercase shadow-xl backdrop-blur-sm transition-colors"
          >
            <span className="text-sm leading-none">×</span> CLEAR BOUNDARY
          </button>
        </div>
      )}

      {/* ── Hint when no boundary drawn ── */}
      {!hasCoords && mode === 'map' && (
        <div className="absolute bottom-8 left-1/2 -translate-x-1/2 z-10 pointer-events-none">
          <div className="bg-black/60 backdrop-blur-sm text-[#7dd4a8] text-[11px] font-medium tracking-wide px-4 py-2 rounded-full border border-[#2a4a3a]">
            Click the polygon tool to draw your boundary
          </div>
        </div>
      )}
    </div>
  )
}
