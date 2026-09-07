import Link from 'next/link'
import { Suspense } from 'react'

// GlobeHero is client-only (uses Mapbox); wrapped in Suspense
import dynamic from 'next/dynamic'
const GlobeHero = dynamic(
  () => import('./components/GlobeHero').then(m => ({ default: m.GlobeHero })),
  { ssr: false }
)

async function getPatches() {
  try {
    const res = await fetch(
      `${process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080'}/api/patches`,
      { cache: 'no-store' }
    )
    return res.ok ? res.json() : []
  } catch {
    return []
  }
}

export default async function HomePage() {
  const patches = await getPatches()
  return (
    <main className="relative w-full h-screen overflow-hidden">
      <GlobeHero patches={patches} />
      <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
        <h1 className="text-5xl font-bold text-white drop-shadow-lg mb-4">
          4Acres Earth
        </h1>
        <p className="text-lg text-slate-300 mb-8 max-w-md text-center">
          Real environmental intelligence for every 4-acre patch on Earth.
        </p>
        <Link
          href="/explore"
          className="pointer-events-auto bg-emerald-500 hover:bg-emerald-400 text-white
                     font-semibold px-6 py-3 rounded-lg transition-colors shadow-lg"
        >
          Explore Patches
        </Link>
      </div>
    </main>
  )
}
