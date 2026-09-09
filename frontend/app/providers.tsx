'use client'

import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useEffect, useState } from 'react'

export function Providers({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(() => new QueryClient())

  useEffect(() => {
    // Mapbox GL throws Invalid LngLat (NaN, NaN) when the cursor leaves the
    // canvas while terrain or globe projection is active. Suppress it globally.
    const suppress = (e: ErrorEvent) => {
      if (e.message?.includes('Invalid LngLat')) e.preventDefault()
    }
    window.addEventListener('error', suppress)
    return () => window.removeEventListener('error', suppress)
  }, [])

  return (
    <QueryClientProvider client={queryClient}>
      {children}
    </QueryClientProvider>
  )
}
