'use client'

import dynamic from 'next/dynamic'
import Link from 'next/link'
import { useState } from 'react'
import { useRegisterPatch } from '../lib/queries'

const DrawMap = dynamic(
  () => import('../components/DrawMap').then(m => ({ default: m.DrawMap })),
  { ssr: false }
)

const ECOSYSTEMS = ['FOREST', 'WETLAND', 'SAVANNA', 'HIGHLAND', 'DRYLAND', 'COASTAL']

export default function RegisterPage() {
  const [coords, setCoords] = useState<number[][] | null>(null)
  const [form, setForm] = useState({
    name: '', ownerName: '', country: '', ecosystemType: 'FOREST', description: '',
  })
  const [error, setError] = useState('')
  const [registeredName, setRegisteredName] = useState<string | null>(null)
  const { mutate: register, isPending } = useRegisterPatch()

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!coords) { setError('Please draw your land boundary first'); return }
    setError('')
    register(
      { ...form, boundary: { type: 'Polygon', coordinates: [coords] } },
      {
        onSuccess: (patch) => setRegisteredName(patch.name),
        onError: (err) => setError(err instanceof Error ? err.message : 'Registration failed'),
      }
    )
  }

  return (
    <div className="h-screen flex overflow-hidden bg-[#0a1628]">
      {/* Form panel */}
      <div className="w-96 flex-shrink-0 flex flex-col bg-slate-900/90 backdrop-blur border-r border-slate-700 overflow-y-auto">
        <div className="p-6 border-b border-slate-700">
          <a href="/" className="text-emerald-400 text-sm hover:text-emerald-300 transition-colors">
            ← 4Acres Earth
          </a>
          <h1 className="text-xl font-bold text-white mt-3">Register Your Land</h1>
          <p className="text-slate-400 text-sm mt-1">
            Draw your 4-acre boundary on the satellite map, then complete the details.
          </p>
        </div>

        {registeredName !== null ? (
          <div className="p-6 flex flex-col gap-4">
            <div className="bg-black/70 backdrop-blur-md border border-white/10 rounded-xl p-6 flex flex-col items-center gap-4 text-center">
              <div className="text-4xl">🌿</div>
              <h2 className="text-lg font-semibold text-white">{registeredName}</h2>
              <p className="text-slate-400 text-sm">
                Successfully registered on the 4Acres Earth network.
              </p>
              <Link
                href="/explore"
                className="mt-2 inline-flex items-center gap-1.5 bg-emerald-600 hover:bg-emerald-500 text-white font-medium px-5 py-2.5 rounded-lg transition-colors text-sm"
              >
                View on map →
              </Link>
            </div>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="p-6 space-y-4 flex-1 flex flex-col">
            {[
              { label: 'Land Name', key: 'name', placeholder: 'e.g. Sunlit Meadow' },
              { label: 'Your Name', key: 'ownerName', placeholder: 'Land owner name' },
              { label: 'Country', key: 'country', placeholder: 'e.g. Scotland' },
            ].map(({ label, key, placeholder }) => (
              <div key={key}>
                <label className="block text-xs font-medium text-slate-400 mb-1">{label} *</label>
                <input
                  required
                  value={form[key as keyof typeof form]}
                  onChange={e => setForm(f => ({ ...f, [key]: e.target.value }))}
                  className="w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-white text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500"
                  placeholder={placeholder}
                />
              </div>
            ))}

            <div>
              <label className="block text-xs font-medium text-slate-400 mb-1">Ecosystem Type *</label>
              <select
                value={form.ecosystemType}
                onChange={e => setForm(f => ({ ...f, ecosystemType: e.target.value }))}
                className="w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-white text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500"
              >
                {ECOSYSTEMS.map(eco => <option key={eco} value={eco}>{eco}</option>)}
              </select>
            </div>

            <div>
              <label className="block text-xs font-medium text-slate-400 mb-1">Description</label>
              <textarea
                value={form.description}
                onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
                rows={3}
                className="w-full bg-slate-800 border border-slate-600 rounded-lg px-3 py-2 text-white text-sm focus:outline-none focus:ring-2 focus:ring-emerald-500 resize-none"
                placeholder="Describe the land..."
              />
            </div>

            <div className="flex-1" />

            {coords && (
              <p className="text-emerald-400 text-sm flex items-center gap-1.5">
                <span>✓</span> Boundary drawn ({coords.length - 1} points)
              </p>
            )}

            {error && <p className="text-red-400 text-sm">{error}</p>}

            <button
              type="submit"
              disabled={isPending}
              className="w-full bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 text-white font-medium py-2.5 rounded-lg transition-colors text-sm"
            >
              {isPending ? 'Registering...' : 'Register Land →'}
            </button>
          </form>
        )}
      </div>

      {/* Map */}
      <div className="flex-1 relative">
        <DrawMap onPolygonDrawn={setCoords} />
      </div>
    </div>
  )
}
