import { Patch, PatchInsights } from './types'

const BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080'

export async function fetchPatches(): Promise<Patch[]> {
  const res = await fetch(`${BASE}/api/patches`)
  if (!res.ok) throw new Error(`fetchPatches failed: ${res.status}`)
  return res.json()
}

export async function fetchInsights(patchId: string): Promise<PatchInsights> {
  const res = await fetch(`${BASE}/api/patches/${patchId}/insights`)
  if (!res.ok) throw new Error(`fetchInsights failed: ${res.status}`)
  return res.json()
}
