'use client'

import type { GeographicContextData } from '../lib/types'

const GLASS = 'bg-black/60 backdrop-blur-md border border-white/10 rounded-xl p-4 text-white'

interface Props {
  data: GeographicContextData | null
  className?: string
}

export function GeographicContextCard({ data, className }: Props) {
  return (
    <div className={`${GLASS} ${className ?? ''}`}>
      <p className="text-xs font-semibold text-violet-400 mb-2">📍 Location</p>
      {data ? (
        <>
          <p className="text-sm font-medium leading-tight mb-1">{data.placeName}</p>
          {data.neighborhood && (
            <p className="text-slate-300 text-xs">{data.neighborhood}</p>
          )}
          <p className="text-slate-400 text-xs">{data.city}, {data.region}</p>
          <p className="text-slate-500 text-xs">{data.country}</p>
          <p className="text-slate-500 text-xs mt-1 truncate" title={data.fullAddress}>{data.fullAddress}</p>
        </>
      ) : (
        <div className="space-y-2">
          <div className="h-4 bg-white/10 rounded animate-pulse" />
          <div className="h-3 bg-white/10 rounded animate-pulse w-3/4" />
          <div className="h-3 bg-white/10 rounded animate-pulse w-1/2" />
        </div>
      )}
    </div>
  )
}
