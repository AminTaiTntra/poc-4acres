'use client'

import dynamic from 'next/dynamic'
import { useParams } from 'next/navigation'
import { usePatch, useInsights, useClaim } from '../../lib/queries'
import { PatchInfoCard } from '../../components/PatchInfoCard'
import { GeographicContextCard } from '../../components/GeographicContextCard'
import { WeatherCard } from '../../components/WeatherCard'
import { SatelliteCard } from '../../components/SatelliteCard'

const MyPatchMap = dynamic(
  () => import('../../components/MyPatchMap').then(m => ({ default: m.MyPatchMap })),
  { ssr: false }
)

export default function PatchPage() {
  const { id } = useParams<{ id: string }>()
  const { data: patch, isLoading } = usePatch(id)
  const { data: insights } = useInsights(id)
  const { data: claim } = useClaim(id)

  if (isLoading) {
    return (
      <div className="h-screen flex items-center justify-center bg-[#0a1628]">
        <div className="text-slate-400 animate-pulse">Loading your patch...</div>
      </div>
    )
  }

  if (!patch) {
    return (
      <div className="h-screen flex items-center justify-center bg-[#0a1628]">
        <div className="text-center">
          <p className="text-slate-400 mb-4">Patch not found</p>
          <a href="/explore" className="text-emerald-400 hover:text-emerald-300 text-sm transition-colors">
            ← Back to explore
          </a>
        </div>
      </div>
    )
  }

  return (
    <div className="relative h-screen overflow-hidden">
      <MyPatchMap patch={patch} />

      {/* Top-right nav */}
      <div className="absolute top-4 right-4 z-10">
        <a
          href="/explore"
          className="text-white/70 hover:text-white text-sm bg-black/40 backdrop-blur-sm px-3 py-1.5 rounded-full transition-colors"
        >
          ← 4Acres Earth
        </a>
      </div>

      {/* Identity card — top-left */}
      <div className="absolute top-4 left-4 z-10 w-64">
        <PatchInfoCard type="identity" patch={patch} claim={claim} />
      </div>

      {/* New integrations row — above existing strip */}
      <div className="absolute bottom-36 left-4 right-4 z-10 flex gap-3">
        <GeographicContextCard data={insights?.geographicContext ?? null} className="flex-1" />
        <WeatherCard data={insights?.weather ?? null} className="flex-1" />
        <SatelliteCard data={insights?.satellite ?? null} className="flex-1" />
      </div>

      {/* Existing metrics row — bottom */}
      <div className="absolute bottom-6 left-4 right-4 z-10 flex gap-3">
        <PatchInfoCard type="biodiversity" insights={insights} className="flex-1" />
        <PatchInfoCard type="soil" insights={insights} className="flex-1" />
        <PatchInfoCard type="carbon" insights={insights} className="flex-1" />
      </div>
    </div>
  )
}
