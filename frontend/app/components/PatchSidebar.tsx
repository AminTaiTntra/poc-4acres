'use client'

import { useState } from 'react'
import type { Patch } from '../lib/types'

const ECOSYSTEMS = ['ALL', 'FOREST', 'WETLAND', 'SAVANNA', 'HIGHLAND', 'DRYLAND', 'COASTAL']

const ECOSYSTEM_COLORS: Record<string, string> = {
  FOREST: 'bg-emerald-600',
  WETLAND: 'bg-blue-600',
  SAVANNA: 'bg-amber-600',
  HIGHLAND: 'bg-purple-600',
  DRYLAND: 'bg-orange-600',
  COASTAL: 'bg-cyan-600',
}

interface Props {
  patches: Patch[]
  selectedPatchId: string | null
  onSelect: (id: string) => void
  onClaim: (patch: Patch) => void
}

export function PatchSidebar({ patches, selectedPatchId, onSelect, onClaim }: Props) {
  const [filter, setFilter] = useState('ALL')

  const visible = filter === 'ALL' ? patches : patches.filter(p => p.ecosystemType === filter)
  const selectedPatch = patches.find(p => p.id === selectedPatchId)

  return (
    <aside className="w-72 flex-shrink-0 bg-slate-900/90 backdrop-blur border-r border-slate-700
                      flex flex-col h-full overflow-hidden">
      <div className="p-4 border-b border-slate-700">
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-sm font-semibold text-slate-300 uppercase tracking-wider">Ecosystem</h2>
          <a
            href="/register"
            className="text-xs text-emerald-400 hover:text-emerald-300 transition-colors"
          >
            + Register Land
          </a>
        </div>
        <div className="flex flex-wrap gap-1">
          {ECOSYSTEMS.map(eco => (
            <button
              key={eco}
              onClick={() => setFilter(eco)}
              className={`px-2 py-1 rounded text-xs font-medium transition-colors ${
                filter === eco
                  ? 'bg-emerald-500 text-white'
                  : 'bg-slate-700 text-slate-300 hover:bg-slate-600'
              }`}
            >
              {eco}
            </button>
          ))}
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-3 space-y-2">
        {visible.map(patch => (
          <button
            key={patch.id}
            onClick={() => onSelect(patch.id)}
            className={`w-full text-left p-3 rounded-lg transition-all ${
              patch.id === selectedPatchId
                ? 'bg-emerald-900/60 ring-1 ring-emerald-500'
                : 'bg-slate-800 hover:bg-slate-700'
            }`}
          >
            <div className="flex items-center gap-2 mb-1">
              <span className={`w-2 h-2 rounded-full flex-shrink-0 ${
                ECOSYSTEM_COLORS[patch.ecosystemType] ?? 'bg-slate-500'
              }`} />
              <span className="text-sm font-medium text-white truncate flex-1">{patch.name}</span>
              <span className={`text-xs px-1.5 py-0.5 rounded font-medium flex-shrink-0 ${
                patch.status === 'AVAILABLE'
                  ? 'bg-amber-500/20 text-amber-300'
                  : 'bg-emerald-500/20 text-emerald-300'
              }`}>
                {patch.status === 'AVAILABLE' ? 'Open' : 'Claimed'}
              </span>
            </div>
            <div className="text-xs text-slate-400 ml-4">
              {patch.country} · {patch.ecosystemType}
            </div>
            {patch.ownerName && (
              <div className="text-xs text-slate-500 ml-4 mt-0.5">Owner: {patch.ownerName}</div>
            )}
          </button>
        ))}
        {visible.length === 0 && (
          <p className="text-slate-500 text-sm text-center py-8">No patches in this ecosystem</p>
        )}
      </div>

      {selectedPatch && (
        <div className="p-3 border-t border-slate-700">
          {selectedPatch.status === 'AVAILABLE' ? (
            <button
              onClick={() => onClaim(selectedPatch)}
              className="w-full bg-emerald-600 hover:bg-emerald-500 text-white font-medium py-2.5 rounded-lg text-sm transition-colors"
            >
              Claim This Patch
            </button>
          ) : (
            <a
              href={`/patch/${selectedPatch.id}`}
              className="block w-full text-center bg-slate-700 hover:bg-slate-600 text-white font-medium py-2.5 rounded-lg text-sm transition-colors"
            >
              View Digital Twin →
            </a>
          )}
        </div>
      )}
    </aside>
  )
}
