'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import dynamic from 'next/dynamic'
import { usePatches } from '../lib/queries'
import { PatchSidebar } from '../components/PatchSidebar'
import { InsightsDrawer } from '../components/InsightsDrawer'
import { ClaimModal } from '../components/ClaimModal'
import type { Patch } from '../lib/types'

const PatchMap = dynamic(
  () => import('../components/PatchMap').then(m => ({ default: m.PatchMap })),
  { ssr: false }
)

export default function ExplorePage() {
  const router = useRouter()
  const [selectedPatchId, setSelectedPatchId] = useState<string | null>(null)
  const [claimTarget, setClaimTarget] = useState<Patch | null>(null)
  const { data: patches = [], isLoading } = usePatches()

  const selectedPatch = patches.find(p => p.id === selectedPatchId)

  function handlePatchSelect(id: string) {
    const patch = patches.find(p => p.id === id)
    if (patch?.status === 'CLAIMED') {
      router.push(`/patch/${id}`)
    } else {
      setSelectedPatchId(id)
    }
  }

  if (isLoading) {
    return (
      <div className="h-screen flex items-center justify-center bg-[#0a1628]">
        <div className="text-slate-400 animate-pulse">Loading patches...</div>
      </div>
    )
  }

  return (
    <div className="h-screen flex overflow-hidden">
      <PatchSidebar
        patches={patches}
        selectedPatchId={selectedPatchId}
        onSelect={setSelectedPatchId}
        onClaim={setClaimTarget}
      />
      <div className="flex-1 relative">
        <PatchMap
          patches={patches}
          selectedPatchId={selectedPatchId}
          onPatchSelect={handlePatchSelect}
        />
      </div>
      <InsightsDrawer
        patchId={selectedPatchId}
        patchName={selectedPatch?.name ?? ''}
      />
      {claimTarget && (
        <ClaimModal
          patch={claimTarget}
          onClose={() => setClaimTarget(null)}
        />
      )}
    </div>
  )
}
