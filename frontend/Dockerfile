FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
# NEXT_PUBLIC_* vars must be present at build time (baked into the JS bundle)
ARG NEXT_PUBLIC_MAPBOX_TOKEN
ARG NEXT_PUBLIC_API_URL=http://localhost:8080
ENV NEXT_PUBLIC_MAPBOX_TOKEN=$NEXT_PUBLIC_MAPBOX_TOKEN
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL
RUN npm run build

FROM node:20-alpine
WORKDIR /app
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
# @google/earthengine is not bundled (serverExternalPackages) so NFT may miss it —
# copy full node_modules to guarantee the package is present at runtime
COPY --from=build /app/node_modules ./node_modules
ENV NODE_ENV=production
ENV HOSTNAME=0.0.0.0
EXPOSE 3000
CMD ["node", "server.js"]
