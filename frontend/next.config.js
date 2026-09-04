/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  transpilePackages: ['react-map-gl', 'mapbox-gl'],
}

module.exports = nextConfig
