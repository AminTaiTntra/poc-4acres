/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  transpilePackages: ['react-map-gl', 'mapbox-gl'],
  experimental: {
    // Prevent webpack from bundling the GEE package — it must load via Node.js require() at runtime
    serverComponentsExternalPackages: ['@google/earthengine'],
  },
}

module.exports = nextConfig
