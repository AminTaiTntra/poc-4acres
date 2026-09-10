/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  transpilePackages: ['react-map-gl', 'mapbox-gl'],
  // @google/earthengine is a large legacy CommonJS SDK with dynamic requires
  // that Next's file-tracing can't fully see statically — bundling it through
  // webpack (the default for route handlers) silently drops files it needs at
  // runtime. Marking it external makes Next require() it directly from
  // node_modules instead, and copies its whole package into the `standalone`
  // output rather than a best-guess trace of "used" files.
  experimental: {
    serverComponentsExternalPackages: ['@google/earthengine'],
  },
}

module.exports = nextConfig
