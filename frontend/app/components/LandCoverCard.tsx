'use client'

import type { LandCoverData, LandCoverClassShare } from '../lib/types'

const GLASS = 'bg-black/60 backdrop-blur-md border border-white/10 rounded-xl p-4 text-white'

// Dynamic World V1 official class palette, keyed by the backend display name.
const CLASS_COLOR: Record<string, string> = {
  'Water': '#419BDF',
  'Trees': '#397D49',
  'Grass': '#88B053',
  'Flooded vegetation': '#7A87C6',
  'Crops': '#E49635',
  'Shrub & scrub': '#DFC35A',
  'Built area': '#C4281B',
  'Bare ground': '#A59B8F',
  'Snow & ice': '#B39FE1',
  'Other': '#64748b',
}

const TOP_N = 6         // classes listed before lumping into "Other"
const HISTORY_ROWS = 4  // classes shown as rows in the history table

interface Props {
  data: LandCoverData | null
  className?: string
}

function colorFor(name: string): string {
  return CLASS_COLOR[name] ?? '#94a3b8'
}

/** Top-N classes by percent, with the remainder folded into a single "Other" row. */
function withOther(classes: LandCoverClassShare[], n: number): LandCoverClassShare[] {
  if (classes.length <= n + 1) return classes
  const head = classes.slice(0, n)
  const rest = classes.slice(n)
  const other: LandCoverClassShare = {
    className: 'Other',
    percent: rest.reduce((s, c) => s + c.percent, 0),
    areaAcres: rest.reduce((s, c) => s + c.areaAcres, 0),
    areaHectares: rest.reduce((s, c) => s + c.areaHectares, 0),
  }
  return other.percent >= 0.5 ? [...head, other] : head
}

export function LandCoverCard({ data, className }: Props) {
  return (
    <div className={`${GLASS} ${className ?? ''}`}>
      <p className="text-xs font-semibold text-lime-400 mb-2">🗺️ Land Cover · Dynamic World</p>

      {!data ? (
        <div className="h-4 bg-white/10 rounded animate-pulse" />
      ) : !data.hasData ? (
        <p className="text-slate-500 text-xs">
          Land-cover data unavailable — Earth Engine not configured for this deployment.
        </p>
      ) : (
        <LandCoverBody data={data} />
      )}
    </div>
  )
}

function LandCoverBody({ data }: { data: LandCoverData }) {
  const comp = withOther(data.composition, TOP_N)

  // history table rows: the leading classes of the latest year, with Trees always pinned in
  const leading = data.composition.slice(0, HISTORY_ROWS).map(c => c.className)
  const hasTreesAnywhere = data.history.some(h => h.classes.some(c => c.className === 'Trees'))
  const rowClasses = leading.includes('Trees') || !hasTreesAnywhere
    ? leading
    : [...leading.slice(0, HISTORY_ROWS - 1), 'Trees']

  return (
    <>
      {/* Stacked composition bar */}
      <div className="flex h-2 w-full overflow-hidden rounded-full mb-2">
        {comp.map(c => (
          <div
            key={c.className}
            style={{ width: `${c.percent}%`, backgroundColor: colorFor(c.className) }}
            title={`${c.className} ${c.percent.toFixed(1)}%`}
          />
        ))}
      </div>

      {/* LAND COVER — class · % · area */}
      <ul className="space-y-0.5 mb-3">
        {comp.map(c => (
          <li key={c.className} className="flex items-center gap-1.5 text-xs">
            <span className="inline-block h-2 w-2 rounded-sm flex-shrink-0"
              style={{ backgroundColor: colorFor(c.className) }} />
            <span className="text-slate-300 flex-1 truncate">{c.className}</span>
            <span className="tabular-nums text-slate-500 w-14 text-right">{c.areaAcres.toFixed(2)} ac</span>
            <span className="tabular-nums font-medium w-9 text-right">{c.percent.toFixed(0)}%</span>
          </li>
        ))}
      </ul>

      {/* History — class rows × year columns */}
      {data.history.length > 1 && (
        <div className="overflow-x-auto mb-2">
          <table className="w-full text-[11px] tabular-nums">
            <thead>
              <tr className="text-slate-500">
                <th className="text-left font-normal pr-2">Class</th>
                {data.history.map(h => (
                  <th key={h.year} className="text-right font-normal px-1">{h.year.slice(2)}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rowClasses.map(name => (
                <tr key={name}>
                  <td className="text-left pr-2 truncate max-w-[84px]">
                    <span className="inline-block h-2 w-2 rounded-sm mr-1 align-middle"
                      style={{ backgroundColor: colorFor(name) }} />
                    <span className="text-slate-300 align-middle">{name}</span>
                  </td>
                  {data.history.map(h => {
                    const cell = h.classes.find(c => c.className === name)
                    return (
                      <td key={h.year} className="text-right px-1 text-slate-400">
                        {cell ? cell.percent.toFixed(0) : '·'}
                      </td>
                    )
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Transitions — class-to-class change, baseline → latest */}
      {data.transitions.length > 0 && (
        <div className="mb-2">
          <p className="text-[10px] uppercase tracking-wide text-slate-500 mb-1">
            Change {data.history[0]?.year}→{data.latestPeriod}
          </p>
          <ul className="space-y-0.5">
            {data.transitions.slice(0, 3).map(t => (
              <li key={`${t.fromClass}-${t.toClass}`} className="flex items-center gap-1.5 text-[11px]">
                <span className="inline-block h-2 w-2 rounded-sm flex-shrink-0"
                  style={{ backgroundColor: colorFor(t.fromClass) }} />
                <span className="text-slate-400">{t.fromClass}</span>
                <span className="text-slate-600">→</span>
                <span className="inline-block h-2 w-2 rounded-sm flex-shrink-0"
                  style={{ backgroundColor: colorFor(t.toClass) }} />
                <span className="text-slate-300 flex-1 truncate">{t.toClass}</span>
                <span className="tabular-nums text-slate-500">{t.areaAcres.toFixed(2)} ac</span>
                <span className="tabular-nums font-medium w-9 text-right">{t.percent.toFixed(0)}%</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      <p className="text-[11px] text-slate-400 leading-snug mb-1">{data.trend}</p>
      <p className="text-[10px] text-slate-600">
        10 m · {data.parcelAcres.toFixed(1)} ac · {data.confidentPercent.toFixed(0)}% of {data.latestPeriod}{' '}
        pixels ≥ {(data.probabilityThreshold * 100).toFixed(0)}% top-class prob ·{' '}
        {data.observationCount.toFixed(0)} obs/pixel
      </p>
    </>
  )
}
