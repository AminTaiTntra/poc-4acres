'use client'

import { useState } from 'react'
import type { PatchInsights, VegetationData } from '../lib/types'
import { PatchInfoCard } from './PatchInfoCard'
import { GeographicContextCard } from './GeographicContextCard'
import { WeatherCard } from './WeatherCard'
import { SatelliteCard } from './SatelliteCard'
import { WaterCard } from './WaterCard'
import { TerrainCard } from './TerrainCard'
import { VegetationCard } from './VegetationCard'
import { LandCoverCard } from './LandCoverCard'

interface Props {
  insights: PatchInsights | null | undefined
  vegetation: VegetationData | null | undefined
}

export function InsightsSheet({ insights, vegetation }: Props) {
  const [expanded, setExpanded] = useState(false)

  return (
    <div className="absolute inset-x-0 bottom-0 z-20 flex justify-center px-4 pb-4 pointer-events-none">
      <div className="w-full max-w-6xl pointer-events-auto bg-slate-900/95 backdrop-blur-md border border-white/10 rounded-2xl shadow-2xl overflow-hidden">
        <button
          onClick={() => setExpanded(v => !v)}
          className="w-full flex items-center gap-3 px-4 py-3 text-left"
          aria-expanded={expanded}
        >
          <span className="text-white text-sm font-semibold flex-shrink-0">
            Insights
          </span>
          <div className="flex items-center gap-2 overflow-x-auto flex-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
            <SummaryChip
              icon="🌿"
              value={insights?.biodiversity ? `${insights.biodiversity.speciesCount} species` : '—'}
            />
            <SummaryChip
              icon="🌲"
              value={insights?.carbon ? `${insights.carbon.treeCoverPercent.toFixed(0)}% tree cover` : '—'}
            />
            <SummaryChip
              icon="💧"
              value={insights?.water ? insights.water.occurrenceClass : '—'}
            />
            <SummaryChip
              icon="☁️"
              value={insights?.weather ? `${insights.weather.tempC.toFixed(0)}°C` : '—'}
            />
            <SummaryChip
              icon="🗺️"
              value={insights?.landCover?.hasData ? 'Land cover ready' : '—'}
            />
          </div>
          <svg
            className={`w-4 h-4 text-white/60 flex-shrink-0 transition-transform ${expanded ? 'rotate-180' : ''}`}
            viewBox="0 0 20 20"
            fill="none"
          >
            <path d="M5 12l5-5 5 5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>

        <div
          className={`transition-[max-height] duration-300 ease-out overflow-hidden ${
            expanded ? 'max-h-[65vh]' : 'max-h-0'
          }`}
        >
          <div className="max-h-[65vh] overflow-y-auto px-4 pb-4 pt-1 border-t border-white/10">
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3 pt-3">
              <LandCoverCard data={insights?.landCover ?? null} />
              <WaterCard data={insights?.water ?? null} />
              <TerrainCard data={insights?.terrain ?? null} />
              <GeographicContextCard data={insights?.geographicContext ?? null} />
              <WeatherCard data={insights?.weather ?? null} />
              <SatelliteCard data={insights?.satellite ?? null} />
              <VegetationCard data={vegetation ?? null} />
              <PatchInfoCard type="biodiversity" insights={insights} />
              <PatchInfoCard type="soil" insights={insights} />
              <PatchInfoCard type="carbon" insights={insights} />
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

function SummaryChip({ icon, value }: { icon: string; value: string }) {
  return (
    <span className="flex items-center gap-1 flex-shrink-0 bg-white/5 text-slate-300 text-xs px-2 py-1 rounded-full">
      <span>{icon}</span>
      <span>{value}</span>
    </span>
  )
}
