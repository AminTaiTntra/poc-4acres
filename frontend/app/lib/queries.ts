import { useMutation, useQuery } from '@tanstack/react-query'
import {
  claimPatch, fetchClaim, fetchInsights, fetchPatch, fetchPatches, fetchVegetation, registerPatch,
} from './api'
import type { ClaimRequest, PatchRegistrationRequest } from './types'

export function usePatches() {
  return useQuery({
    queryKey: ['patches'],
    queryFn: fetchPatches,
    staleTime: Infinity,
  })
}

export function usePatch(patchId: string | null) {
  return useQuery({
    queryKey: ['patch', patchId],
    queryFn: () => fetchPatch(patchId!),
    enabled: patchId != null,
    staleTime: Infinity,
  })
}

export function useInsights(patchId: string | null) {
  return useQuery({
    queryKey: ['insights', patchId],
    queryFn: () => fetchInsights(patchId!),
    enabled: patchId != null,
    staleTime: 1000 * 60 * 60 * 24,
  })
}

export function useClaim(patchId: string | null) {
  return useQuery({
    queryKey: ['claim', patchId],
    queryFn: () => fetchClaim(patchId!),
    enabled: patchId != null,
    retry: false,
  })
}

export function useRegisterPatch() {
  return useMutation({
    mutationFn: (req: PatchRegistrationRequest) => registerPatch(req),
  })
}

export function useClaimPatch() {
  return useMutation({
    mutationFn: ({ patchId, ...req }: { patchId: string } & ClaimRequest) =>
      claimPatch(patchId, req),
  })
}

export function useVegetation(patchId: string | null) {
  return useQuery({
    queryKey: ['vegetation', patchId],
    queryFn: () => fetchVegetation(patchId!),
    enabled: patchId != null,
    staleTime: 1000 * 60 * 60 * 24,
    retry: false,
  })
}
