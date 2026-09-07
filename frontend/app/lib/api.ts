import { ClaimDto, ClaimRequest, Patch, PatchInsights, PatchRegistrationRequest } from './types'

const BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080'

export async function fetchPatches(): Promise<Patch[]> {
  const res = await fetch(`${BASE}/api/patches`)
  if (!res.ok) throw new Error(`fetchPatches failed: ${res.status}`)
  return res.json()
}

export async function fetchPatch(patchId: string): Promise<Patch> {
  const res = await fetch(`${BASE}/api/patches/${patchId}`)
  if (!res.ok) throw new Error(`fetchPatch failed: ${res.status}`)
  return res.json()
}

export async function fetchInsights(patchId: string): Promise<PatchInsights> {
  const res = await fetch(`${BASE}/api/patches/${patchId}/insights`)
  if (!res.ok) throw new Error(`fetchInsights failed: ${res.status}`)
  return res.json()
}

export async function fetchClaim(patchId: string): Promise<ClaimDto> {
  const res = await fetch(`${BASE}/api/patches/${patchId}/claim`)
  if (!res.ok) throw new Error(`fetchClaim failed: ${res.status}`)
  return res.json()
}

export async function registerPatch(req: PatchRegistrationRequest): Promise<Patch> {
  const res = await fetch(`${BASE}/api/patches`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  })
  if (!res.ok) throw new Error(`registerPatch failed: ${res.status}`)
  return res.json()
}

export async function claimPatch(patchId: string, req: ClaimRequest): Promise<ClaimDto> {
  const res = await fetch(`${BASE}/api/patches/${patchId}/claim`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  })
  if (res.status === 409) throw new Error('This patch has already been claimed')
  if (!res.ok) throw new Error(`claimPatch failed: ${res.status}`)
  return res.json()
}
