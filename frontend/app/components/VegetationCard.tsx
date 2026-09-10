'use client'

import type { VegetationData } from '../lib/types'

const GLASS = 'bg-black/60 backdrop-blur-md border border-white/10 rounded-xl p-4 text-white'

const TREND_COLOR = {
  improving: 'text-emerald-400',
  stable: 'text-amber-400',
  degrading: 'text-red-400',
} as const

const TREND_ARROW = {
  improving: '↑',
  stable: '→',
  degrading: '↓',
} as const

const CONDITION_COLOR = {
  good: 'text-emerald-400',
  fair: 'text-amber-400',
  poor: 'text-red-400',
} as const

interface Props {
  data: VegetationData | null
  className?: string
}

export function VegetationCard({ data, className }: Props) {
  return (
    <div className={`${GLASS} ${className ?? ''}`}>
      <p className="text-xs font-semibold text-emerald-400 mb-3">🌿 VEGETATION</p>
      {data ? (
        <div className="space-y-2">
          <Row label="Current condition">
            <span className={CONDITION_COLOR[data.condition]}>
              {capitalize(data.condition)}
            </span>
          </Row>
          <Row label="NDVI" value={data.currentNdvi.toFixed(2)} />
          <Row label="Historical baseline" value={data.baselineNdvi.toFixed(2)} />
          <Row label="Change">
            <span className={data.changePct >= 0 ? 'text-emerald-400' : 'text-red-400'}>
              {data.changePct >= 0 ? '+' : ''}{data.changePct.toFixed(1)}%
            </span>
          </Row>
          <Row label="Trend">
            <span className={TREND_COLOR[data.trend]}>
              {TREND_ARROW[data.trend]} {capitalize(data.trend)}
            </span>
          </Row>
          <Row label="Last observation" value={formatDate(data.lastObservation)} />
          <Row label="Resolution" value={`${data.resolutionM}m`} />
          <Row label="Source" value={data.source} />
          {data.yearly.length >= 2 && <NdviSparkline data={data} />}
        </div>
      ) : (
        <div className="space-y-2">
          {Array.from({ length: 8 }).map((_, i) => (
            <div key={i} className="h-4 bg-white/10 rounded animate-pulse" />
          ))}
        </div>
      )}
    </div>
  )
}

function Row({
  label,
  value,
  children,
}: {
  label: string
  value?: string
  children?: React.ReactNode
}) {
  return (
    <div className="flex justify-between items-center text-sm">
      <span className="text-white/60">{label}</span>
      <span className="font-medium">{children ?? value}</span>
    </div>
  )
}

function NdviSparkline({ data }: { data: VegetationData }) {
  const W = 180
  const H = 32
  const points = data.yearly
  const validPoints = points.filter((p): p is NonNullable<typeof p> & { ndvi: number } =>
    p !== null && p.ndvi !== null
  )
  if (validPoints.length < 2) return null

  const minV = Math.min(...validPoints.map(p => p.ndvi))
  const maxV = Math.max(...validPoints.map(p => p.ndvi))
  const range = maxV - minV || 0.1

  const toX = (i: number) => (i / (points.length - 1)) * W
  const toY = (v: number) => H - ((v - minV) / range) * (H - 4) - 2

  const segments: string[][] = []
  let current: string[] = []
  points.forEach((p, i) => {
    if (p !== null && p.ndvi !== null) {
      current.push(`${toX(i).toFixed(1)},${toY(p.ndvi).toFixed(1)}`)
    } else if (current.length > 0) {
      segments.push(current)
      current = []
    }
  })
  if (current.length > 0) segments.push(current)

  const firstYear = points[0]?.year ?? ''
  const lastYear = points[points.length - 1]?.year ?? ''

  return (
    <div className="mt-2 pt-2 border-t border-white/10">
      <p className="text-xs text-white/40 mb-1">
        NDVI trend ({firstYear}–{lastYear})
      </p>
      <svg width={W} height={H} className="overflow-visible">
        {segments.map((seg, i) => (
          <polyline
            key={i}
            points={seg.join(' ')}
            fill="none"
            stroke="#34d399"
            strokeWidth="1.5"
            strokeLinejoin="round"
          />
        ))}
        {points.map((p, i) =>
          p !== null && p.ndvi !== null ? (
            <circle
              key={i}
              cx={toX(i)}
              cy={toY(p.ndvi)}
              r="2.5"
              fill="#34d399"
            />
          ) : null
        )}
      </svg>
    </div>
  )
}

function capitalize(s: string) {
  return s.charAt(0).toUpperCase() + s.slice(1)
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString('en-GB', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  })
}
