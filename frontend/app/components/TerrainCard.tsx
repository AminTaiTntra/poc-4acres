'use client'

import type { TerrainData } from '../lib/types'

const GLASS = 'bg-black/60 backdrop-blur-md border border-white/10 rounded-xl p-4 text-white'

interface Props {
  data: TerrainData | null
  className?: string
}

export function TerrainCard({ data, className }: Props) {
  return (
    <div className={`${GLASS} ${className ?? ''}`}>
      <p className="text-xs font-semibold text-orange-400 mb-2">⛰️ Terrain</p>
      {data ? (
        <div className="grid grid-cols-2 gap-2">
          <Stat
            label="Elevation"
            value={`${data.elevationMinM.toFixed(0)}–${data.elevationMaxM.toFixed(0)}`}
            unit="m"
          />
          <Stat label="Terrain" value={data.terrainClass} unit="" />
          <Stat label="Avg slope" value={data.avgSlopeDeg.toFixed(0)} unit="°" />
          <Stat label="Max slope" value={data.maxSlopeDeg.toFixed(0)} unit="°" />
        </div>
      ) : (
        <div className="space-y-2">
          <div className="h-4 bg-white/10 rounded animate-pulse" />
          <div className="h-3 bg-white/10 rounded animate-pulse w-3/4" />
        </div>
      )}
    </div>
  )
}

function Stat({ label, value, unit }: { label: string; value: string; unit: string }) {
  return (
    <div>
      <p className="text-slate-500 text-xs">{label}</p>
      <p className="text-sm font-medium leading-tight">
        {value}
        {unit && <span className="text-slate-400 text-xs ml-0.5">{unit}</span>}
      </p>
    </div>
  )
}
