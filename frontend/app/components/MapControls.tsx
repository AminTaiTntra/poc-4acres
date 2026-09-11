'use client'

import { useState } from 'react'
import type { MapRef } from 'react-map-gl'

// ── Shared map styles ─────────────────────────────────────────────────────────

export const MAP_STYLES: Record<string, string> = {
  satellite: 'mapbox://styles/mapbox/satellite-v9',
  dark:      'mapbox://styles/mapbox/dark-v11',
  outdoors:  'mapbox://styles/mapbox/outdoors-v12',
}

const STYLE_LABELS: Record<string, string> = {
  satellite: 'Satellite',
  dark:      'Dark',
  outdoors:  'Outdoors',
}

// ── Icons ─────────────────────────────────────────────────────────────────────

export function IconCompass() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="10" />
      <polygon points="16.24 7.76 14.12 14.12 7.76 16.24 9.88 9.88 16.24 7.76" />
    </svg>
  )
}

export function IconLocate() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="3" />
      <path d="M12 2v3M12 19v3M2 12h3M19 12h3" />
    </svg>
  )
}

export function IconPitch() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
    </svg>
  )
}

export function IconFullscreen() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3" />
    </svg>
  )
}

export function IconLayers() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polygon points="12 2 2 7 12 12 22 7 12 2" />
      <polyline points="2 17 12 22 22 17" />
      <polyline points="2 12 12 17 22 12" />
    </svg>
  )
}

export function IconTarget() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="10" />
      <circle cx="12" cy="12" r="3" />
      <line x1="12" y1="2"  x2="12" y2="5"  />
      <line x1="12" y1="19" x2="12" y2="22" />
      <line x1="2"  y1="12" x2="5"  y2="12" />
      <line x1="19" y1="12" x2="22" y2="12" />
    </svg>
  )
}

// ── Shared control button ─────────────────────────────────────────────────────

export function CtrlBtn({ onClick, active, title, children }: {
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

export function CtrlDivider() {
  return <div className="h-px bg-[#2a4a3a] w-full" />
}

export const CTRL_PANEL = 'flex flex-col bg-[#0d2318]/95 border border-[#2a4a3a] rounded-lg overflow-hidden shadow-lg backdrop-blur-sm'

// ── MapControls ───────────────────────────────────────────────────────────────

interface FlyTarget {
  lng: number
  lat: number
  zoom: number
  pitch?: number
  bearing?: number
}

interface MapControlsProps {
  mapRef: React.RefObject<MapRef>
  currentStyle: string
  onStyleChange: (key: string) => void
  show3D?: boolean
  initialIs3D?: boolean
  flyTo?: FlyTarget
}

export function MapControls({
  mapRef,
  currentStyle,
  onStyleChange,
  show3D = false,
  initialIs3D = false,
  flyTo,
}: MapControlsProps) {
  const [is3D,       setIs3D]       = useState(initialIs3D)
  const [showLayers, setShowLayers] = useState(false)

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
      map.easeTo({ pitch: 60, duration: 400 })
      setIs3D(true)
    }
  }

  function handleFlyTo() {
    if (!flyTo) return
    mapRef.current?.getMap()?.flyTo({
      center:  [flyTo.lng, flyTo.lat],
      zoom:    flyTo.zoom,
      pitch:   flyTo.pitch   ?? 0,
      bearing: flyTo.bearing ?? 0,
      duration: 1500,
    })
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

  return (
    <div className="absolute right-4 top-4 z-10 flex flex-col gap-2">

      {/* Zoom */}
      <div className={CTRL_PANEL}>
        <CtrlBtn onClick={zoomIn}  title="Zoom in">
          <span className="text-lg font-light leading-none">+</span>
        </CtrlBtn>
        <CtrlDivider />
        <CtrlBtn onClick={zoomOut} title="Zoom out">
          <span className="text-lg font-light leading-none">−</span>
        </CtrlBtn>
      </div>

      {/* Compass · Locate · 3D · Fullscreen */}
      <div className={CTRL_PANEL}>
        <CtrlBtn onClick={resetNorth} title="Reset north &amp; pitch">
          <IconCompass />
        </CtrlBtn>
        <CtrlDivider />
        <CtrlBtn onClick={geolocate} title="My location">
          <IconLocate />
        </CtrlBtn>
        {show3D && (
          <>
            <CtrlDivider />
            <CtrlBtn onClick={toggle3D} active={is3D} title={is3D ? 'Switch to 2D' : 'Switch to 3D'}>
              <IconPitch />
            </CtrlBtn>
          </>
        )}
        <CtrlDivider />
        <CtrlBtn onClick={toggleFullscreen} title="Fullscreen">
          <IconFullscreen />
        </CtrlBtn>
      </div>

      {/* Fly-to-patch (optional) */}
      {flyTo && (
        <div className={CTRL_PANEL}>
          <CtrlBtn onClick={handleFlyTo} title="Fly back to patch">
            <IconTarget />
          </CtrlBtn>
        </div>
      )}

      {/* Layer switcher */}
      <div className="relative">
        <div className={CTRL_PANEL}>
          <CtrlBtn
            onClick={() => setShowLayers(v => !v)}
            active={showLayers}
            title="Map style"
          >
            <IconLayers />
          </CtrlBtn>
        </div>

        {showLayers && (
          <div className="absolute right-12 top-0 bg-[#0d2318]/95 border border-[#2a4a3a] rounded-lg overflow-hidden shadow-xl backdrop-blur-sm min-w-[120px]">
            {Object.keys(MAP_STYLES).map(key => (
              <button
                key={key}
                onClick={() => { onStyleChange(key); setShowLayers(false) }}
                className={`w-full text-left px-4 py-2.5 text-[11px] font-bold tracking-widest uppercase transition-colors
                  ${currentStyle === key
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
  )
}
