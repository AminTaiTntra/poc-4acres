'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { useClaimPatch } from '../lib/queries'
import type { Patch } from '../lib/types'

interface Props {
  patch: Patch
  onClose: () => void
}

export function ClaimModal({ patch, onClose }: Props) {
  const router = useRouter()
  const [form, setForm] = useState({ stewardName: '', stewardEmail: '' })
  const [error, setError] = useState('')
  const { mutate: claim, isPending } = useClaimPatch()

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    claim(
      { patchId: patch.id, ...form },
      {
        onSuccess: () => router.push(`/patch/${patch.id}`),
        onError: (err) => setError(err instanceof Error ? err.message : 'Claim failed'),
      }
    )
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm">
      <div className="bg-slate-900 border border-slate-700 rounded-2xl p-6 w-full max-w-md mx-4 shadow-2xl">
        <div className="flex items-start justify-between mb-4">
          <div>
            <h2 className="text-lg font-bold text-white">Claim Your 4 Acres</h2>
            <p className="text-slate-400 text-sm mt-0.5">{patch.name}</p>
          </div>
          <button
            onClick={onClose}
            className="text-slate-400 hover:text-white text-2xl leading-none ml-4"
          >
            ×
          </button>
        </div>

        <p className="text-slate-300 text-sm mb-5">
          Enter your details to become the steward of this patch.
          You will receive a unique URL to view your land.
        </p>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-xs font-medium text-slate-400 mb-1">Your Name *</label>
            <input
              required
              value={form.stewardName}
              onChange={e => setForm(f => ({ ...f, stewardName: e.target.value }))}
              className="w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-white text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500"
              placeholder="Full name"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-400 mb-1">Email *</label>
            <input
              required
              type="email"
              value={form.stewardEmail}
              onChange={e => setForm(f => ({ ...f, stewardEmail: e.target.value }))}
              className="w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-white text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500"
              placeholder="you@example.com"
            />
          </div>

          {error && <p className="text-red-400 text-sm">{error}</p>}

          <div className="flex gap-3 pt-1">
            <button
              type="button"
              onClick={onClose}
              className="flex-1 bg-slate-700 hover:bg-slate-600 text-white font-medium py-2.5 rounded-lg transition-colors text-sm"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isPending}
              className="flex-1 bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 text-white font-medium py-2.5 rounded-lg transition-colors text-sm"
            >
              {isPending ? 'Claiming...' : 'Claim This Patch'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
