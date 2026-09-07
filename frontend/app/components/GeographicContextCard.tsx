'use client'

import type { PatchInsights } from '../lib/types'

const GLASS = 'bg-black/60 backdrop-blur-md border border-white/10 rounded-xl p-4 text-white'

interface Props {
  insights: PatchInsights | undefined
  className?: string
}

export function GeographicContextCard({ insights, className }: Props) {
  const geo = insights?.geographicContext

  return (
    <div className={`${GLASS} ${className ?? ''}`}>
      <p className="text-xs font-semibold text-violet-400 mb-2">📍 Location</p>
      {geo ? (
        <>
          <p className="text-sm font-medium leading-tight mb-1">
            {geo.neighborhood ? `${geo.neighborhood}, ` : ''}{geo.city}
          </p>
          <p className="text-slate-400 text-xs">{geo.region}</p>
          <p className="text-slate-500 text-xs">{geo.country}</p>
        </>
      ) : (
        <div className="h-4 bg-white/10 rounded animate-pulse" />
      )}
    </div>
  )
}
