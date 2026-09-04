import type { Metadata } from 'next'
import { Providers } from './providers'
import './globals.css'

export const metadata: Metadata = {
  title: '4Acres Earth',
  description: 'Environmental intelligence for your 4-acre patch',
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="bg-[#0a1628] text-white antialiased">
        <Providers>{children}</Providers>
      </body>
    </html>
  )
}
