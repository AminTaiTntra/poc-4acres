'use client'

import { useState } from 'react'
import type { SatelliteSceneData } from '../lib/types'

const GLASS = 'bg-black/60 backdrop-blur-md border border-white/10 rounded-xl p-4 text-white'

interface Props {
  data: SatelliteSceneData | null
  className?: string
}

function cloudBadgeClass(pct: number): string {
  if (pct < 20)  return 'bg-emerald-500/20 text-emerald-300'
  if (pct < 60)  return 'bg-amber-500/20 text-amber-300'
  return 'bg-red-500/20 text-red-300'
}

export function SatelliteCard({ data, className }: Props) {
  const [thumbError, setThumbError] = useState(false)

  return (
    <div className={`${GLASS} ${className ?? ''}`}>
      <p className="text-xs font-semibold text-teal-400 mb-2">🛰️ Sentinel-2</p>
      {!data ? (
        <div className="h-4 bg-white/10 rounded animate-pulse" />
      ) : !data.hasRecentScene ? (
        <p className="text-slate-500 text-xs">No scene in last 90 days</p>
      ) : (
        <>
          <p className="text-sm font-medium mb-1">
            {new Date(data.latestSceneDate!).toLocaleDateString('en', {
              day: 'numeric', month: 'short', year: 'numeric',
            })}
          </p>
          <div className="flex gap-1.5 flex-wrap mb-2">
            <span className={`text-xs px-1.5 py-0.5 rounded ${cloudBadgeClass(data.cloudCoverPercent)}`}>
              {data.cloudCoverPercent.toFixed(0)}% cloud
            </span>
            <span className="text-xs px-1.5 py-0.5 rounded bg-white/5 text-slate-300">
              {data.productType === 'S2MSI2A' ? 'Analysis-Ready' : 'Raw (L1C)'}
            </span>
          </div>
          {data.thumbnailUrl && !thumbError && (
            <img
              src={data.thumbnailUrl}
              alt="Satellite quicklook"
              className="w-full max-h-16 object-cover rounded"
              onError={() => setThumbError(true)}
            />
          )}
        </>
      )}
    </div>
  )
}
