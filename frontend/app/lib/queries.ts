import { useQuery } from '@tanstack/react-query'
import { fetchInsights, fetchPatches } from './api'

export function usePatches() {
  return useQuery({
    queryKey: ['patches'],
    queryFn: fetchPatches,
    staleTime: Infinity,
  })
}

export function useInsights(patchId: string | null) {
  return useQuery({
    queryKey: ['insights', patchId],
    queryFn: () => fetchInsights(patchId!),
    enabled: patchId != null,
    staleTime: 1000 * 60 * 60 * 24, // 24h
  })
}
