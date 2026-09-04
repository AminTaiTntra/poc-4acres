# 4Acres Earth Geospatial POC — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a working local POC that fetches real biodiversity, soil, and carbon data per 4-acre patch and displays it on an interactive satellite map.

**Architecture:** Spring Boot 3 backend fetches GBIF, SoilGrids, and GFW in parallel via `CompletableFuture.allOf()`, caches results as JSONB in PostgreSQL for 24h, and exposes three REST endpoints. Next.js 14 frontend renders a Mapbox globe on the landing page and a satellite patch explorer with a sidebar and insights drawer on `/explore`.

**Tech Stack:** Java 21 + Spring Boot 3, PostgreSQL 15 + PostGIS 3.4, Flyway, Next.js 14 (App Router), react-map-gl v7 + Mapbox GL JS v3, @tanstack/react-query v5, Tailwind CSS v4, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-09-04-geospatial-poc-design.md`

## Global Constraints

- Java 21 (not 17, not 22)
- Spring Boot 3.x (not 2.x)
- PostgreSQL 15 + PostGIS 3.4 — use `postgis/postgis:15-3.4` Docker image
- Flyway for all DB schema changes — no manual SQL outside migrations
- Next.js 14 App Router only — no Pages Router patterns
- react-map-gl v7 — not v6, not v8
- Mapbox GL JS v3 — not mapbox-gl v2
- @tanstack/react-query v5 — not v4 (v5 has different API)
- Tailwind CSS v4 — not v3 (v4 uses `@import "tailwindcss"` not `@tailwind` directives)
- All services run via Docker Compose — no cloud deployment
- MAPBOX_TOKEN and GFW_API_KEY come from `.env` (gitignored); `.env.example` is committed
- Patch boundary: fixed 127m × 127m square in WGS84 (EPSG:4326)
- Ecosystem types (enum): `FOREST`, `SAVANNA`, `WETLAND`, `COASTAL`, `HIGHLAND`, `DRYLAND`
- Cache TTL: 24 hours
- GBIF radius: 120m, limit: 300
- SoilGrids depth: `0-5cm`, properties: `soc,phh2o,clay`, value: `mean`

---

## File Structure

```
4-acres-tech-poc/
├── docker-compose.yml
├── .env.example
├── scripts/
│   └── register-gfw-geostores.js     # one-time: registers 10 patches with GFW, prints IDs
├── backend/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/io/fouracres/
│       │   │   ├── FourAcresApplication.java
│       │   │   ├── config/
│       │   │   │   └── WebConfig.java          # CORS allow localhost:3000
│       │   │   ├── model/
│       │   │   │   ├── Patch.java              # JPA entity, PostGIS geometry field
│       │   │   │   ├── InsightsCache.java      # JPA entity, composite PK
│       │   │   │   └── EcosystemType.java      # enum
│       │   │   ├── repository/
│       │   │   │   ├── PatchRepository.java
│       │   │   │   └── InsightsCacheRepository.java
│       │   │   ├── dto/
│       │   │   │   ├── PatchDto.java
│       │   │   │   ├── PatchInsightsDto.java
│       │   │   │   ├── BiodiversityData.java
│       │   │   │   ├── SoilData.java
│       │   │   │   └── CarbonData.java
│       │   │   ├── client/
│       │   │   │   ├── GbifClient.java
│       │   │   │   ├── SoilGridsClient.java
│       │   │   │   └── GfwClient.java
│       │   │   ├── service/
│       │   │   │   └── InsightsService.java
│       │   │   └── controller/
│       │   │       └── PatchController.java
│       │   └── resources/
│       │       ├── application.yml
│       │       └── db/migration/
│       │           ├── V1__create_schema.sql
│       │           └── V2__seed_patches.sql
│       └── test/
│           └── java/io/fouracres/
│               ├── client/
│               │   ├── GbifClientTest.java
│               │   ├── SoilGridsClientTest.java
│               │   └── GfwClientTest.java
│               └── service/
│                   └── InsightsServiceTest.java
└── frontend/
    ├── Dockerfile
    ├── package.json
    ├── next.config.js
    ├── tailwind.config.ts
    └── app/
        ├── layout.tsx
        ├── globals.css
        ├── page.tsx                   # landing: GlobeHero + CTA
        ├── explore/
        │   └── page.tsx               # main POC experience
        ├── components/
        │   ├── GlobeHero.tsx
        │   ├── PatchMap.tsx
        │   ├── PatchSidebar.tsx
        │   ├── InsightsDrawer.tsx
        │   ├── BiodiversityCard.tsx
        │   ├── SoilCard.tsx
        │   └── CarbonCard.tsx
        └── lib/
            ├── types.ts               # shared TypeScript interfaces
            ├── api.ts                 # fetch wrappers
            └── queries.ts             # react-query definitions
```

---

### Task 1: Project Scaffolding & Docker Compose

**Files:**
- Create: `docker-compose.yml`
- Create: `.env.example`
- Create: `backend/pom.xml`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/java/io/fouracres/FourAcresApplication.java`
- Create: `backend/Dockerfile`
- Create: `frontend/package.json`
- Create: `frontend/next.config.js`
- Create: `frontend/tailwind.config.ts`
- Create: `frontend/app/globals.css`
- Create: `frontend/app/layout.tsx`
- Create: `frontend/Dockerfile`

**Interfaces:**
- Produces: running `docker compose up --build` starts DB on :5432, API on :8080, frontend on :3000

- [ ] **Step 1: Create `docker-compose.yml`**

```yaml
services:
  db:
    image: postgis/postgis:15-3.4
    environment:
      POSTGRES_DB: fouracres
      POSTGRES_USER: fouracres
      POSTGRES_PASSWORD: fouracres_dev
    ports: ["5432:5432"]
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U fouracres -d fouracres"]
      interval: 5s
      timeout: 5s
      retries: 10

  api:
    build: ./backend
    ports: ["8080:8080"]
    env_file: .env
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/fouracres
      SPRING_DATASOURCE_USERNAME: fouracres
      SPRING_DATASOURCE_PASSWORD: fouracres_dev
    depends_on:
      db:
        condition: service_healthy

  frontend:
    build: ./frontend
    ports: ["3000:3000"]
    environment:
      NEXT_PUBLIC_API_URL: http://localhost:8080
      NEXT_PUBLIC_MAPBOX_TOKEN: ${MAPBOX_TOKEN}
    depends_on: [api]

volumes:
  pgdata:
```

- [ ] **Step 2: Create `.env.example`**

```
MAPBOX_TOKEN=pk.eyJ1...your_mapbox_public_token_here
GFW_API_KEY=your_gfw_api_key_here
```

- [ ] **Step 3: Create `backend/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.4</version>
    <relativePath/>
  </parent>
  <groupId>io.fouracres</groupId>
  <artifactId>api</artifactId>
  <version>0.0.1-SNAPSHOT</version>
  <properties>
    <java.version>21</java.version>
    <hibernate-spatial.version>6.5.2.Final</hibernate-spatial.version>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>org.hibernate.orm</groupId>
      <artifactId>hibernate-spatial</artifactId>
      <version>${hibernate-spatial.version}</version>
    </dependency>
    <dependency>
      <groupId>org.flywaydb</groupId>
      <artifactId>flyway-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 4: Create `backend/src/main/resources/application.yml`**

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/fouracres}
    username: ${SPRING_DATASOURCE_USERNAME:fouracres}
    password: ${SPRING_DATASOURCE_PASSWORD:fouracres_dev}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: true
    locations: classpath:db/migration

server:
  port: 8080

app:
  gfw:
    api-key: ${GFW_API_KEY:}
    base-url: https://data-api.globalforestwatch.org
  gbif:
    base-url: https://api.gbif.org/v1
  soilgrids:
    base-url: https://rest.soilgrids.org
```

- [ ] **Step 5: Create `backend/src/main/java/io/fouracres/FourAcresApplication.java`**

```java
package io.fouracres;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FourAcresApplication {
    public static void main(String[] args) {
        SpringApplication.run(FourAcresApplication.class, args);
    }
}
```

- [ ] **Step 6: Create `backend/Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN ./mvnw -q package -DskipTests || (apt-get update && apt-get install -y maven && mvn -q package -DskipTests)

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Note: The build stage needs Maven. Use a Maven base image instead:

```dockerfile
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 7: Bootstrap Next.js frontend**

```bash
cd frontend
npx create-next-app@14 . \
  --typescript \
  --tailwind \
  --app \
  --no-src-dir \
  --import-alias "@/*"
npm install react-map-gl@7 mapbox-gl@3 \
  @tanstack/react-query@5 \
  @tanstack/react-query-devtools@5
```

- [ ] **Step 8: Create `frontend/next.config.js`**

```js
/** @type {import('next').NextConfig} */
const nextConfig = {
  transpilePackages: ['react-map-gl', 'mapbox-gl'],
}

module.exports = nextConfig
```

- [ ] **Step 9: Update `frontend/app/globals.css` to import mapbox CSS**

```css
@import "tailwindcss";
@import "mapbox-gl/dist/mapbox-gl.css";
```

- [ ] **Step 10: Create `frontend/app/layout.tsx`**

```tsx
import type { Metadata } from 'next'
import './globals.css'

export const metadata: Metadata = {
  title: '4Acres Earth',
  description: 'Environmental intelligence for your 4-acre patch',
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body className="bg-[#0a1628] text-white antialiased">{children}</body>
    </html>
  )
}
```

- [ ] **Step 11: Create `frontend/Dockerfile`**

```dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM node:20-alpine
WORKDIR /app
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public
ENV NODE_ENV=production
EXPOSE 3000
CMD ["node", "server.js"]
```

Also add to `frontend/next.config.js`:
```js
const nextConfig = {
  output: 'standalone',
  transpilePackages: ['react-map-gl', 'mapbox-gl'],
}
```

- [ ] **Step 12: Verify Docker Compose starts DB**

```bash
cp .env.example .env  # fill in tokens later — DB works without them
docker compose up db -d
docker compose ps     # db should be healthy
```

Expected: `db` container shows `healthy`

- [ ] **Step 13: Commit**

```bash
git init
git add docker-compose.yml .env.example backend/ frontend/
git commit -m "feat: project scaffolding, Docker Compose, Spring Boot + Next.js 14"
```

---

### Task 2: Database Schema (Flyway Migrations)

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__create_schema.sql`
- Create: `backend/src/main/resources/db/migration/V2__seed_patches.sql`

**Interfaces:**
- Produces: `patches` table with PostGIS geometry, `insights_cache` table with JSONB and composite PK

- [ ] **Step 1: Create `V1__create_schema.sql`**

```sql
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE patches (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name            VARCHAR(100) NOT NULL,
  ecosystem_type  VARCHAR(50)  NOT NULL,
  country         VARCHAR(100) NOT NULL,
  description     TEXT,
  center_lat      DECIMAL(10,7) NOT NULL,
  center_lng      DECIMAL(10,7) NOT NULL,
  boundary        GEOMETRY(Polygon, 4326) NOT NULL,
  area_acres      DECIMAL(5,2) NOT NULL DEFAULT 4.0,
  gfw_geostore_id VARCHAR(100),
  created_at      TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_patches_boundary ON patches USING GIST(boundary);

CREATE TABLE insights_cache (
  patch_id    UUID        NOT NULL REFERENCES patches(id) ON DELETE CASCADE,
  layer       VARCHAR(20) NOT NULL,
  payload     JSONB       NOT NULL,
  fetched_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
  expires_at  TIMESTAMP   NOT NULL,
  PRIMARY KEY (patch_id, layer)
);
```

- [ ] **Step 2: Write the `generate_square_polygon` helper comment**

The 127m × 127m boundary in WGS84 degrees: at the equator, 1 degree latitude ≈ 111,320m, 1 degree longitude ≈ 111,320m × cos(lat). Half-side in degrees:

```
delta_lat = 0.0635 / 111.320  ≈ 0.0005705°
delta_lng = 0.0635 / (111.320 × cos(lat_radians))
```

Each patch boundary in `V2` is computed from these formulas per row.

- [ ] **Step 3: Create `V2__seed_patches.sql`**

The 10 patches with pre-computed boundaries. For each, the polygon is a clockwise square: SW → SE → NE → NW → SW (closed). The delta values below use `delta_lat = 0.0005705` and `delta_lng = 0.0005705 / cos(lat_rad)` rounded to 7 decimal places. `gfw_geostore_id` is left NULL — filled by the GFW registration script in Task 3.

```sql
INSERT INTO patches (name, ecosystem_type, country, center_lat, center_lng, boundary) VALUES

('Amazon Várzea Forest', 'FOREST', 'Brazil', -3.4653, -62.2159,
  ST_GeomFromText('POLYGON((-62.2165706 -3.4658705,-62.2152294 -3.4658705,-62.2152294 -3.4647295,-62.2165706 -3.4647295,-62.2165706 -3.4658705))', 4326)),

('Sundarbans Mangrove', 'WETLAND', 'Bangladesh', 21.9497, 89.1833,
  ST_GeomFromText('POLYGON((89.1826852 21.9491295,89.1839148 21.9491295,89.1839148 21.9502705,89.1826852 21.9502705,89.1826852 21.9491295))', 4326)),

('Maasai Mara Savanna', 'SAVANNA', 'Kenya', -1.5442, 35.1042,
  ST_GeomFromText('POLYGON((35.1035694 -1.5447705,35.1048306 -1.5447705,35.1048306 -1.5436295,35.1035694 -1.5436295,35.1035694 -1.5447705))', 4326)),

('Cairngorms Highland', 'HIGHLAND', 'Scotland', 57.1230, -3.8940,
  ST_GeomFromText('POLYGON((-3.8951537 57.1224295,-3.8928463 57.1224295,-3.8928463 57.1235705,-3.8951537 57.1235705,-3.8951537 57.1224295))', 4326)),

('Borneo Rainforest', 'FOREST', 'Malaysia', 2.1896, 113.9944,
  ST_GeomFromText('POLYGON((113.9937696 2.1890295,113.9950304 2.1890295,113.9950304 2.1901705,113.9937696 2.1901705,113.9937696 2.1890295))', 4326)),

('Daintree Rainforest', 'FOREST', 'Australia', -16.1700, 145.4200,
  ST_GeomFromText('POLYGON((145.4193076 -16.1705705,145.4206924 -16.1705705,145.4206924 -16.1694295,145.4193076 -16.1694295,145.4193076 -16.1705705))', 4326)),

('Yellowstone Forest', 'FOREST', 'USA', 44.4280, -110.5885,
  ST_GeomFromText('POLYGON((-110.5893005 44.4274295,-110.5876995 44.4274295,-110.5876995 44.4285705,-110.5893005 44.4285705,-110.5893005 44.4274295))', 4326)),

('Sahel Dryland', 'DRYLAND', 'Mali', 14.8833, -5.0000,
  ST_GeomFromText('POLYGON((-5.0005912 14.8827295,-4.9994088 14.8827295,-4.9994088 14.8838705,-5.0005912 14.8838705,-5.0005912 14.8827295))', 4326)),

('Białowieża Primeval Forest', 'FOREST', 'Poland', 52.7069, 23.8601,
  ST_GeomFromText('POLYGON((23.8594139 52.7063295,23.8607861 52.7063295,23.8607861 52.7074705,23.8594139 52.7074705,23.8594139 52.7063295))', 4326)),

('Patagonian Steppe', 'HIGHLAND', 'Argentina', -50.3498, -72.2660,
  ST_GeomFromText('POLYGON((-72.2668902 -50.3503705,-72.2651098 -50.3503705,-72.2651098 -50.3492295,-72.2668902 -50.3492295,-72.2668902 -50.3503705))', 4326));
```

- [ ] **Step 4: Start DB and run migrations to verify**

```bash
docker compose up db -d
# temporarily run the backend locally to trigger Flyway
cd backend
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.flyway.enabled=true" &
sleep 10
# or just check tables via psql
docker exec -it $(docker compose ps -q db) psql -U fouracres -d fouracres -c "\dt"
```

Expected: `patches` and `insights_cache` tables exist; `SELECT COUNT(*) FROM patches;` → 10 rows.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/
git commit -m "feat: Flyway migrations — create schema + seed 10 patches"
```

---

### Task 3: GFW Geostore Registration Script

**Files:**
- Create: `scripts/register-gfw-geostores.js`

**Interfaces:**
- Consumes: DB with 10 seeded patches (Task 2), `GFW_API_KEY` in env
- Produces: prints SQL `UPDATE patches SET gfw_geostore_id = '...' WHERE name = '...';` for each patch — operator runs these manually, then `V2` is updated

- [ ] **Step 1: Create `scripts/register-gfw-geostores.js`**

```js
#!/usr/bin/env node
// Run once: node scripts/register-gfw-geostores.js
// Requires: GFW_API_KEY env var, node-fetch (npm i -g node-fetch or use Node 18+)

const GFW_API_KEY = process.env.GFW_API_KEY;
if (!GFW_API_KEY) { console.error('GFW_API_KEY required'); process.exit(1); }

const patches = [
  { name: 'Amazon Várzea Forest',        coords: [[-62.2165706,-3.4658705],[-62.2152294,-3.4658705],[-62.2152294,-3.4647295],[-62.2165706,-3.4647295],[-62.2165706,-3.4658705]] },
  { name: 'Sundarbans Mangrove',          coords: [[89.1826852,21.9491295],[89.1839148,21.9491295],[89.1839148,21.9502705],[89.1826852,21.9502705],[89.1826852,21.9491295]] },
  { name: 'Maasai Mara Savanna',          coords: [[35.1035694,-1.5447705],[35.1048306,-1.5447705],[35.1048306,-1.5436295],[35.1035694,-1.5436295],[35.1035694,-1.5447705]] },
  { name: 'Cairngorms Highland',          coords: [[-3.8951537,57.1224295],[-3.8928463,57.1224295],[-3.8928463,57.1235705],[-3.8951537,57.1235705],[-3.8951537,57.1224295]] },
  { name: 'Borneo Rainforest',            coords: [[113.9937696,2.1890295],[113.9950304,2.1890295],[113.9950304,2.1901705],[113.9937696,2.1901705],[113.9937696,2.1890295]] },
  { name: 'Daintree Rainforest',          coords: [[145.4193076,-16.1705705],[145.4206924,-16.1705705],[145.4206924,-16.1694295],[145.4193076,-16.1694295],[145.4193076,-16.1705705]] },
  { name: 'Yellowstone Forest',           coords: [[-110.5893005,44.4274295],[-110.5876995,44.4274295],[-110.5876995,44.4285705],[-110.5893005,44.4285705],[-110.5893005,44.4274295]] },
  { name: 'Sahel Dryland',               coords: [[-5.0005912,14.8827295],[-4.9994088,14.8827295],[-4.9994088,14.8838705],[-5.0005912,14.8838705],[-5.0005912,14.8827295]] },
  { name: 'Białowieża Primeval Forest',   coords: [[23.8594139,52.7063295],[23.8607861,52.7063295],[23.8607861,52.7074705],[23.8594139,52.7074705],[23.8594139,52.7063295]] },
  { name: 'Patagonian Steppe',           coords: [[-72.2668902,-50.3503705],[-72.2651098,-50.3503705],[-72.2651098,-50.3492295],[-72.2668902,-50.3492295],[-72.2668902,-50.3503705]] },
];

async function register(patch) {
  const body = {
    geojson: {
      type: 'FeatureCollection',
      features: [{
        type: 'Feature',
        geometry: { type: 'Polygon', coordinates: [patch.coords] },
        properties: {}
      }]
    }
  };
  const res = await fetch('https://data-api.globalforestwatch.org/dataset/geostore', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'x-api-key': GFW_API_KEY,
    },
    body: JSON.stringify(body),
  });
  const json = await res.json();
  if (!res.ok) throw new Error(`GFW error for ${patch.name}: ${JSON.stringify(json)}`);
  return json.data?.id || json.geostore_id;
}

(async () => {
  console.log('-- Run these SQL updates after registration:');
  for (const patch of patches) {
    try {
      const id = await register(patch);
      console.log(`UPDATE patches SET gfw_geostore_id = '${id}' WHERE name = '${patch.name}';`);
    } catch (e) {
      console.error(e.message);
    }
  }
})();
```

- [ ] **Step 2: Note for operator**

After running the script, copy the printed UPDATE statements into `V2__seed_patches.sql` as a second batch of statements (or create `V3__set_gfw_ids.sql`). The `gfwClient` in Task 7 handles `null` geostore IDs gracefully — it returns zeroed carbon data rather than throwing.

- [ ] **Step 3: Commit**

```bash
git add scripts/register-gfw-geostores.js
git commit -m "feat: GFW geostore registration script"
```

---

### Task 4: Backend JPA Models, DTOs, and Repositories

**Files:**
- Create: `backend/src/main/java/io/fouracres/model/EcosystemType.java`
- Create: `backend/src/main/java/io/fouracres/model/Patch.java`
- Create: `backend/src/main/java/io/fouracres/model/InsightsCache.java`
- Create: `backend/src/main/java/io/fouracres/model/InsightsCacheId.java`
- Create: `backend/src/main/java/io/fouracres/repository/PatchRepository.java`
- Create: `backend/src/main/java/io/fouracres/repository/InsightsCacheRepository.java`
- Create: `backend/src/main/java/io/fouracres/dto/BiodiversityData.java`
- Create: `backend/src/main/java/io/fouracres/dto/SoilData.java`
- Create: `backend/src/main/java/io/fouracres/dto/CarbonData.java`
- Create: `backend/src/main/java/io/fouracres/dto/PatchInsightsDto.java`
- Create: `backend/src/main/java/io/fouracres/dto/PatchDto.java`
- Create: `backend/src/main/java/io/fouracres/config/WebConfig.java`

**Interfaces:**
- Produces:
  - `PatchRepository.findAll()` → `List<Patch>`
  - `PatchRepository.findById(UUID)` → `Optional<Patch>`
  - `InsightsCacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(UUID, String, Instant)` → `Optional<InsightsCache>`
  - `PatchDto` with fields: `id`, `name`, `ecosystemType`, `country`, `centerLat`, `centerLng`, `boundaryGeoJson`
  - `PatchInsightsDto` with fields: `biodiversity: BiodiversityData`, `soil: SoilData`, `carbon: CarbonData`
  - `BiodiversityData`: `speciesCount: int`, `topSpecies: List<SpeciesEntry>`, `threatenedCount: int`
  - `SoilData`: `organicCarbonGKg: double`, `ph: double`, `clayPercent: double`
  - `CarbonData`: `treeCoverPercent: double`, `carbonDensityMgHa: double`, `coverLossHa: double`

- [ ] **Step 1: Create `EcosystemType.java`**

```java
package io.fouracres.model;

public enum EcosystemType {
    FOREST, SAVANNA, WETLAND, COASTAL, HIGHLAND, DRYLAND
}
```

- [ ] **Step 2: Create `Patch.java`**

```java
package io.fouracres.model;

import jakarta.persistence.*;
import org.locationtech.jts.geom.Polygon;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "patches")
public class Patch {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "ecosystem_type", nullable = false, length = 50)
    private EcosystemType ecosystemType;

    @Column(nullable = false, length = 100)
    private String country;

    private String description;

    @Column(name = "center_lat", nullable = false, precision = 10, scale = 7)
    private BigDecimal centerLat;

    @Column(name = "center_lng", nullable = false, precision = 10, scale = 7)
    private BigDecimal centerLng;

    @Column(nullable = false, columnDefinition = "geometry(Polygon,4326)")
    private Polygon boundary;

    @Column(name = "area_acres", nullable = false, precision = 5, scale = 2)
    private BigDecimal areaAcres = BigDecimal.valueOf(4.0);

    @Column(name = "gfw_geostore_id", length = 100)
    private String gfwGeostoreId;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    // Getters
    public UUID getId() { return id; }
    public String getName() { return name; }
    public EcosystemType getEcosystemType() { return ecosystemType; }
    public String getCountry() { return country; }
    public String getDescription() { return description; }
    public BigDecimal getCenterLat() { return centerLat; }
    public BigDecimal getCenterLng() { return centerLng; }
    public Polygon getBoundary() { return boundary; }
    public BigDecimal getAreaAcres() { return areaAcres; }
    public String getGfwGeostoreId() { return gfwGeostoreId; }
}
```

- [ ] **Step 3: Create `InsightsCacheId.java` (composite PK)**

```java
package io.fouracres.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class InsightsCacheId implements Serializable {
    private UUID patchId;
    private String layer;

    public InsightsCacheId() {}
    public InsightsCacheId(UUID patchId, String layer) {
        this.patchId = patchId;
        this.layer = layer;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InsightsCacheId that)) return false;
        return Objects.equals(patchId, that.patchId) && Objects.equals(layer, that.layer);
    }

    @Override
    public int hashCode() { return Objects.hash(patchId, layer); }
}
```

- [ ] **Step 4: Create `InsightsCache.java`**

```java
package io.fouracres.model;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "insights_cache")
@IdClass(InsightsCacheId.class)
public class InsightsCache {
    @Id
    @Column(name = "patch_id")
    private UUID patchId;

    @Id
    @Column(name = "layer", length = 20)
    private String layer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private String payload;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public InsightsCache() {}
    public InsightsCache(UUID patchId, String layer, String payload, Instant expiresAt) {
        this.patchId = patchId;
        this.layer = layer;
        this.payload = payload;
        this.expiresAt = expiresAt;
    }

    public String getPayload() { return payload; }
    public Instant getExpiresAt() { return expiresAt; }
}
```

- [ ] **Step 5: Create repositories**

`PatchRepository.java`:
```java
package io.fouracres.repository;

import io.fouracres.model.Patch;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface PatchRepository extends JpaRepository<Patch, UUID> {}
```

`InsightsCacheRepository.java`:
```java
package io.fouracres.repository;

import io.fouracres.model.InsightsCache;
import io.fouracres.model.InsightsCacheId;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface InsightsCacheRepository extends JpaRepository<InsightsCache, InsightsCacheId> {
    Optional<InsightsCache> findByPatchIdAndLayerAndExpiresAtAfter(
        UUID patchId, String layer, Instant now);
}
```

- [ ] **Step 6: Create DTOs**

`BiodiversityData.java`:
```java
package io.fouracres.dto;

import java.util.List;

public record BiodiversityData(
    int speciesCount,
    List<SpeciesEntry> topSpecies,
    int threatenedCount
) {
    public record SpeciesEntry(String name, String kingdom) {}
}
```

`SoilData.java`:
```java
package io.fouracres.dto;

public record SoilData(
    double organicCarbonGKg,
    double ph,
    double clayPercent
) {}
```

`CarbonData.java`:
```java
package io.fouracres.dto;

public record CarbonData(
    double treeCoverPercent,
    double carbonDensityMgHa,
    double coverLossHa
) {}
```

`PatchInsightsDto.java`:
```java
package io.fouracres.dto;

public record PatchInsightsDto(
    BiodiversityData biodiversity,
    SoilData soil,
    CarbonData carbon
) {}
```

`PatchDto.java`:
```java
package io.fouracres.dto;

import io.fouracres.model.Patch;
import org.locationtech.jts.geom.Coordinate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public record PatchDto(
    UUID id,
    String name,
    String ecosystemType,
    String country,
    double centerLat,
    double centerLng,
    Object boundaryGeoJson
) {
    public static PatchDto from(Patch p) {
        var coords = Arrays.stream(p.getBoundary().getCoordinates())
            .map(c -> List.of(c.x, c.y))
            .toList();
        var geojson = java.util.Map.of(
            "type", "Polygon",
            "coordinates", List.of(coords)
        );
        return new PatchDto(
            p.getId(),
            p.getName(),
            p.getEcosystemType().name(),
            p.getCountry(),
            p.getCenterLat().doubleValue(),
            p.getCenterLng().doubleValue(),
            geojson
        );
    }
}
```

- [ ] **Step 7: Create `WebConfig.java`**

```java
package io.fouracres.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins("http://localhost:3000")
            .allowedMethods("GET");
    }
}
```

- [ ] **Step 8: Compile to verify no errors**

```bash
cd backend && mvn compile -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/
git commit -m "feat: JPA models, DTOs, repositories, CORS config"
```

---

### Task 5: GBIF Client

**Files:**
- Create: `backend/src/main/java/io/fouracres/client/GbifClient.java`
- Create: `backend/src/test/java/io/fouracres/client/GbifClientTest.java`

**Interfaces:**
- Consumes: `Patch` (needs `centerLat`, `centerLng`)
- Produces: `GbifClient.fetch(Patch patch)` → `BiodiversityData`

- [ ] **Step 1: Write the failing test**

`GbifClientTest.java`:
```java
package io.fouracres.client;

import io.fouracres.dto.BiodiversityData;
import io.fouracres.model.Patch;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class GbifClientTest {

    private static final String GBIF_RESPONSE = """
        {
          "results": [
            {"species": "Panthera onca", "kingdom": "Animalia", "iucnRedListCategory": "VU"},
            {"species": "Panthera onca", "kingdom": "Animalia", "iucnRedListCategory": "VU"},
            {"species": "Ara macao", "kingdom": "Animalia", "iucnRedListCategory": null},
            {"species": "Heliconia bihai", "kingdom": "Plantae", "iucnRedListCategory": null},
            {"species": "Tapirus terrestris", "kingdom": "Animalia", "iucnRedListCategory": "VU"},
            {"species": "Morpho menelaus", "kingdom": "Animalia", "iucnRedListCategory": null},
            {"species": "Cedrela odorata", "kingdom": "Plantae", "iucnRedListCategory": "VU"}
          ]
        }
        """;

    @Test
    void fetch_parsesSpeciesCountAndThreatenedCount() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        var response = Mockito.mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(GBIF_RESPONSE);
        when(httpClient.send(any(), any())).thenReturn(response);

        var client = new GbifClient(httpClient, "https://api.gbif.org/v1");
        var patch = mockPatch(-3.4653, -62.2159);

        BiodiversityData result = client.fetch(patch);

        assertThat(result.speciesCount()).isEqualTo(6); // 6 distinct species
        assertThat(result.threatenedCount()).isEqualTo(4); // VU records
        assertThat(result.topSpecies()).hasSize(5);
        assertThat(result.topSpecies().get(0).name()).isEqualTo("Panthera onca"); // highest count
    }

    private Patch mockPatch(double lat, double lng) {
        var patch = Mockito.mock(Patch.class);
        when(patch.getCenterLat()).thenReturn(BigDecimal.valueOf(lat));
        when(patch.getCenterLng()).thenReturn(BigDecimal.valueOf(lng));
        return patch;
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
cd backend && mvn test -pl . -Dtest=GbifClientTest -q 2>&1 | tail -5
```

Expected: FAIL — `GbifClient` not found

- [ ] **Step 3: Implement `GbifClient.java`**

```java
package io.fouracres.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.dto.BiodiversityData;
import io.fouracres.model.Patch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Component
public class GbifClient {
    private static final Set<String> THREATENED = Set.of("VU", "EN", "CR");
    private final HttpClient httpClient;
    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public GbifClient(HttpClient httpClient,
                      @Value("${app.gbif.base-url}") String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
    }

    public BiodiversityData fetch(Patch patch) {
        String url = "%s/occurrence/search?decimalLatitude=%s&decimalLongitude=%s&radius=120&limit=300&hasCoordinate=true"
            .formatted(baseUrl, patch.getCenterLat(), patch.getCenterLng());
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parse(response.body());
        } catch (Exception e) {
            return new BiodiversityData(0, List.of(), 0);
        }
    }

    private BiodiversityData parse(String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        JsonNode results = root.path("results");

        Map<String, String> speciesKingdom = new LinkedHashMap<>();
        Map<String, Long> speciesCount = new LinkedHashMap<>();
        int threatened = 0;

        for (JsonNode r : results) {
            String species = r.path("species").asText(null);
            if (species == null || species.isBlank()) continue;
            String kingdom = r.path("kingdom").asText("Unknown");
            speciesKingdom.putIfAbsent(species, kingdom);
            speciesCount.merge(species, 1L, Long::sum);
            String iucn = r.path("iucnRedListCategory").asText("");
            if (THREATENED.contains(iucn)) threatened++;
        }

        var top5 = speciesCount.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(5)
            .map(e -> new BiodiversityData.SpeciesEntry(e.getKey(), speciesKingdom.getOrDefault(e.getKey(), "Unknown")))
            .toList();

        return new BiodiversityData(speciesKingdom.size(), top5, threatened);
    }
}
```

Also register `HttpClient` as a bean in a `HttpClientConfig.java`:
```java
package io.fouracres.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.http.HttpClient;

@Configuration
public class HttpClientConfig {
    @Bean
    public HttpClient httpClient() {
        return HttpClient.newHttpClient();
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

```bash
cd backend && mvn test -Dtest=GbifClientTest -q
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/fouracres/client/GbifClient.java \
        backend/src/main/java/io/fouracres/config/HttpClientConfig.java \
        backend/src/test/java/io/fouracres/client/GbifClientTest.java
git commit -m "feat: GbifClient — fetch and parse biodiversity data from GBIF"
```

---

### Task 6: SoilGrids Client

**Files:**
- Create: `backend/src/main/java/io/fouracres/client/SoilGridsClient.java`
- Create: `backend/src/test/java/io/fouracres/client/SoilGridsClientTest.java`

**Interfaces:**
- Consumes: `Patch` (needs `centerLat`, `centerLng`)
- Produces: `SoilGridsClient.fetch(Patch patch)` → `SoilData`

- [ ] **Step 1: Write the failing test**

`SoilGridsClientTest.java`:
```java
package io.fouracres.client;

import io.fouracres.dto.SoilData;
import io.fouracres.model.Patch;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class SoilGridsClientTest {

    // SoilGrids stores: soc as g/kg×10, phh2o as pH×10, clay as %×10
    private static final String SOILGRIDS_RESPONSE = """
        {
          "properties": {
            "layers": [
              {
                "name": "soc",
                "depths": [{"label":"0-5cm","values":{"mean":215}}]
              },
              {
                "name": "phh2o",
                "depths": [{"label":"0-5cm","values":{"mean":57}}]
              },
              {
                "name": "clay",
                "depths": [{"label":"0-5cm","values":{"mean":324}}]
              }
            ]
          }
        }
        """;

    @Test
    void fetch_dividesByTenAndMapsFields() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        var response = Mockito.mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(SOILGRIDS_RESPONSE);
        when(httpClient.send(any(), any())).thenReturn(response);

        var client = new SoilGridsClient(httpClient, "https://rest.soilgrids.org");
        var patch = Mockito.mock(Patch.class);
        when(patch.getCenterLat()).thenReturn(BigDecimal.valueOf(-3.4653));
        when(patch.getCenterLng()).thenReturn(BigDecimal.valueOf(-62.2159));

        SoilData result = client.fetch(patch);

        assertThat(result.organicCarbonGKg()).isCloseTo(21.5, within(0.01));
        assertThat(result.ph()).isCloseTo(5.7, within(0.01));
        assertThat(result.clayPercent()).isCloseTo(32.4, within(0.01));
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
cd backend && mvn test -Dtest=SoilGridsClientTest -q 2>&1 | tail -5
```

Expected: FAIL

- [ ] **Step 3: Implement `SoilGridsClient.java`**

```java
package io.fouracres.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.dto.SoilData;
import io.fouracres.model.Patch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

@Component
public class SoilGridsClient {
    private final HttpClient httpClient;
    private final String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public SoilGridsClient(HttpClient httpClient,
                           @Value("${app.soilgrids.base-url}") String baseUrl) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
    }

    public SoilData fetch(Patch patch) {
        String url = "%s/soilgrids/v2.0/properties/query?lon=%s&lat=%s&property=soc,phh2o,clay&depth=0-5cm&value=mean"
            .formatted(baseUrl, patch.getCenterLng(), patch.getCenterLat());
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parse(response.body());
        } catch (Exception e) {
            return new SoilData(0, 0, 0);
        }
    }

    private SoilData parse(String body) throws Exception {
        JsonNode layers = mapper.readTree(body).path("properties").path("layers");
        Map<String, Double> values = new HashMap<>();
        for (JsonNode layer : layers) {
            String name = layer.path("name").asText();
            JsonNode mean = layer.path("depths").get(0).path("values").path("mean");
            if (!mean.isMissingNode()) values.put(name, mean.asDouble());
        }
        double soc = values.getOrDefault("soc", 0.0) / 10.0;
        double ph  = values.getOrDefault("phh2o", 0.0) / 10.0;
        double clay = values.getOrDefault("clay", 0.0) / 10.0;
        return new SoilData(soc, ph, clay);
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

```bash
cd backend && mvn test -Dtest=SoilGridsClientTest -q
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/fouracres/client/SoilGridsClient.java \
        backend/src/test/java/io/fouracres/client/SoilGridsClientTest.java
git commit -m "feat: SoilGridsClient — fetch soil organic carbon, pH, clay"
```

---

### Task 7: GFW Client

**Files:**
- Create: `backend/src/main/java/io/fouracres/client/GfwClient.java`
- Create: `backend/src/test/java/io/fouracres/client/GfwClientTest.java`

**Interfaces:**
- Consumes: `Patch` (needs `gfwGeostoreId`)
- Produces: `GfwClient.fetch(Patch patch)` → `CarbonData`; returns zeroed `CarbonData` when `gfwGeostoreId` is null

- [ ] **Step 1: Write the failing test**

`GfwClientTest.java`:
```java
package io.fouracres.client;

import io.fouracres.dto.CarbonData;
import io.fouracres.model.Patch;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class GfwClientTest {

    private static final String GFW_RESPONSE = """
        {
          "data": [{
            "umd_tree_cover_density_2020__threshold": 30,
            "umd_tree_cover_density_2020__ha": 1.4,
            "gfw_aboveground_carbon_stocks_2000__Mg_C_ha-1": 210.5,
            "umd_tree_cover_loss__ha": 0.08
          }]
        }
        """;

    @Test
    void fetch_mapsFieldsCorrectly() throws Exception {
        var httpClient = Mockito.mock(HttpClient.class);
        var response = Mockito.mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(GFW_RESPONSE);
        when(httpClient.send(any(), any())).thenReturn(response);

        var patch = Mockito.mock(Patch.class);
        when(patch.getGfwGeostoreId()).thenReturn("abc123");

        var client = new GfwClient(httpClient, "https://data-api.globalforestwatch.org", "test-key");
        CarbonData result = client.fetch(patch);

        assertThat(result.treeCoverPercent()).isCloseTo(35.0, within(1.0)); // 1.4ha / 4ac → %
        assertThat(result.carbonDensityMgHa()).isCloseTo(210.5, within(0.1));
        assertThat(result.coverLossHa()).isCloseTo(0.08, within(0.001));
    }

    @Test
    void fetch_returnsZeroedData_whenGeostoreIdNull() {
        var httpClient = Mockito.mock(HttpClient.class);
        var patch = Mockito.mock(Patch.class);
        when(patch.getGfwGeostoreId()).thenReturn(null);

        var client = new GfwClient(httpClient, "https://data-api.globalforestwatch.org", "test-key");
        CarbonData result = client.fetch(patch);

        assertThat(result.treeCoverPercent()).isZero();
        assertThat(result.carbonDensityMgHa()).isZero();
        assertThat(result.coverLossHa()).isZero();
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
cd backend && mvn test -Dtest=GfwClientTest -q 2>&1 | tail -5
```

Expected: FAIL

- [ ] **Step 3: Implement `GfwClient.java`**

```java
package io.fouracres.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.dto.CarbonData;
import io.fouracres.model.Patch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Component
public class GfwClient {
    private static final double ACRES_TO_HA = 0.404686;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public GfwClient(HttpClient httpClient,
                     @Value("${app.gfw.base-url}") String baseUrl,
                     @Value("${app.gfw.api-key}") String apiKey) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    public CarbonData fetch(Patch patch) {
        if (patch.getGfwGeostoreId() == null) return new CarbonData(0, 0, 0);
        String url = "%s/dataset/umd_tree_cover_density_2020/latest/query?geostore_id=%s"
            .formatted(baseUrl, patch.getGfwGeostoreId());
        try {
            var request = HttpRequest.newBuilder(URI.create(url))
                .header("x-api-key", apiKey)
                .GET()
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parse(response.body());
        } catch (Exception e) {
            return new CarbonData(0, 0, 0);
        }
    }

    private CarbonData parse(String body) throws Exception {
        JsonNode data = mapper.readTree(body).path("data");
        if (!data.isArray() || data.isEmpty()) return new CarbonData(0, 0, 0);
        JsonNode row = data.get(0);
        double coverHa = row.path("umd_tree_cover_density_2020__ha").asDouble(0);
        double patchHa = 4 * ACRES_TO_HA;
        double treeCoverPercent = patchHa > 0 ? (coverHa / patchHa) * 100 : 0;
        double carbonDensity = row.path("gfw_aboveground_carbon_stocks_2000__Mg_C_ha-1").asDouble(0);
        double coverLoss = row.path("umd_tree_cover_loss__ha").asDouble(0);
        return new CarbonData(treeCoverPercent, carbonDensity, coverLoss);
    }
}
```

- [ ] **Step 4: Run test — verify it passes**

```bash
cd backend && mvn test -Dtest=GfwClientTest -q
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/fouracres/client/GfwClient.java \
        backend/src/test/java/io/fouracres/client/GfwClientTest.java
git commit -m "feat: GfwClient — fetch carbon/forest data from GFW API"
```

---

### Task 8: InsightsService (Parallel Fetch + 24h Cache)

**Files:**
- Create: `backend/src/main/java/io/fouracres/service/InsightsService.java`
- Create: `backend/src/test/java/io/fouracres/service/InsightsServiceTest.java`

**Interfaces:**
- Consumes: `PatchRepository`, `InsightsCacheRepository`, `GbifClient`, `SoilGridsClient`, `GfwClient`
- Produces: `InsightsService.getInsights(UUID patchId)` → `PatchInsightsDto`; throws `NoSuchElementException` for unknown patch IDs

- [ ] **Step 1: Write the failing test**

`InsightsServiceTest.java`:
```java
package io.fouracres.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.client.GbifClient;
import io.fouracres.client.GfwClient;
import io.fouracres.client.SoilGridsClient;
import io.fouracres.dto.*;
import io.fouracres.model.Patch;
import io.fouracres.repository.InsightsCacheRepository;
import io.fouracres.repository.PatchRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InsightsServiceTest {

    @Test
    void getInsights_callsAllClientsAndCaches() {
        var patchId = UUID.randomUUID();
        var patch = Mockito.mock(Patch.class);

        var patchRepo = Mockito.mock(PatchRepository.class);
        var cacheRepo = Mockito.mock(InsightsCacheRepository.class);
        var gbif = Mockito.mock(GbifClient.class);
        var soil = Mockito.mock(SoilGridsClient.class);
        var gfw = Mockito.mock(GfwClient.class);

        when(patchRepo.findById(patchId)).thenReturn(Optional.of(patch));
        when(cacheRepo.findByPatchIdAndLayerAndExpiresAtAfter(eq(patchId), any(), any()))
            .thenReturn(Optional.empty()); // cache miss

        var bioData = new BiodiversityData(42, List.of(), 3);
        var soilData = new SoilData(21.5, 5.7, 32.4);
        var carbonData = new CarbonData(35.0, 210.5, 0.08);

        when(gbif.fetch(patch)).thenReturn(bioData);
        when(soil.fetch(patch)).thenReturn(soilData);
        when(gfw.fetch(patch)).thenReturn(carbonData);

        var service = new InsightsService(patchRepo, cacheRepo, gbif, soil, gfw, new ObjectMapper());
        PatchInsightsDto result = service.getInsights(patchId);

        assertThat(result.biodiversity().speciesCount()).isEqualTo(42);
        assertThat(result.soil().ph()).isEqualTo(5.7);
        assertThat(result.carbon().carbonDensityMgHa()).isEqualTo(210.5);

        // verify cache was written for all 3 layers
        verify(cacheRepo, times(3)).save(any());
    }

    @Test
    void getInsights_returnsCachedData_onCacheHit() throws Exception {
        var patchId = UUID.randomUUID();
        var patchRepo = Mockito.mock(PatchRepository.class);
        var cacheRepo = Mockito.mock(InsightsCacheRepository.class);
        var gbif = Mockito.mock(GbifClient.class);
        var soil = Mockito.mock(SoilGridsClient.class);
        var gfw = Mockito.mock(GfwClient.class);

        var mapper = new ObjectMapper();
        var bioData = new BiodiversityData(7, List.of(), 1);

        var cachedBio = Mockito.mock(io.fouracres.model.InsightsCache.class);
        var cachedSoil = Mockito.mock(io.fouracres.model.InsightsCache.class);
        var cachedCarbon = Mockito.mock(io.fouracres.model.InsightsCache.class);

        when(cachedBio.getPayload()).thenReturn(mapper.writeValueAsString(bioData));
        when(cachedSoil.getPayload()).thenReturn(mapper.writeValueAsString(new SoilData(10.0, 6.0, 20.0)));
        when(cachedCarbon.getPayload()).thenReturn(mapper.writeValueAsString(new CarbonData(50.0, 100.0, 0.0)));

        when(cacheRepo.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "BIODIVERSITY", any()))
            .thenReturn(Optional.of(cachedBio));
        when(cacheRepo.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "SOIL", any()))
            .thenReturn(Optional.of(cachedSoil));
        when(cacheRepo.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "CARBON", any()))
            .thenReturn(Optional.of(cachedCarbon));

        var service = new InsightsService(patchRepo, cacheRepo, gbif, soil, gfw, mapper);
        PatchInsightsDto result = service.getInsights(patchId);

        assertThat(result.biodiversity().speciesCount()).isEqualTo(7);
        verifyNoInteractions(gbif, soil, gfw); // clients never called
    }
}
```

- [ ] **Step 2: Run test — verify it fails**

```bash
cd backend && mvn test -Dtest=InsightsServiceTest -q 2>&1 | tail -5
```

Expected: FAIL

- [ ] **Step 3: Implement `InsightsService.java`**

```java
package io.fouracres.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fouracres.client.GbifClient;
import io.fouracres.client.GfwClient;
import io.fouracres.client.SoilGridsClient;
import io.fouracres.dto.*;
import io.fouracres.model.InsightsCache;
import io.fouracres.model.Patch;
import io.fouracres.repository.InsightsCacheRepository;
import io.fouracres.repository.PatchRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class InsightsService {
    private final PatchRepository patchRepository;
    private final InsightsCacheRepository cacheRepository;
    private final GbifClient gbifClient;
    private final SoilGridsClient soilGridsClient;
    private final GfwClient gfwClient;
    private final ObjectMapper mapper;

    public InsightsService(PatchRepository patchRepository,
                           InsightsCacheRepository cacheRepository,
                           GbifClient gbifClient,
                           SoilGridsClient soilGridsClient,
                           GfwClient gfwClient,
                           ObjectMapper mapper) {
        this.patchRepository = patchRepository;
        this.cacheRepository = cacheRepository;
        this.gbifClient = gbifClient;
        this.soilGridsClient = soilGridsClient;
        this.gfwClient = gfwClient;
        this.mapper = mapper;
    }

    public PatchInsightsDto getInsights(UUID patchId) {
        var now = Instant.now();

        Optional<InsightsCache> bioCached   = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "BIODIVERSITY", now);
        Optional<InsightsCache> soilCached  = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "SOIL", now);
        Optional<InsightsCache> carbonCached = cacheRepository.findByPatchIdAndLayerAndExpiresAtAfter(patchId, "CARBON", now);

        if (bioCached.isPresent() && soilCached.isPresent() && carbonCached.isPresent()) {
            return deserialize(bioCached.get(), soilCached.get(), carbonCached.get());
        }

        Patch patch = patchRepository.findById(patchId)
            .orElseThrow(() -> new NoSuchElementException("Patch not found: " + patchId));

        var bioFuture    = CompletableFuture.supplyAsync(() -> gbifClient.fetch(patch));
        var soilFuture   = CompletableFuture.supplyAsync(() -> soilGridsClient.fetch(patch));
        var carbonFuture = CompletableFuture.supplyAsync(() -> gfwClient.fetch(patch));

        CompletableFuture.allOf(bioFuture, soilFuture, carbonFuture).join();

        var bio    = bioFuture.join();
        var soil   = soilFuture.join();
        var carbon = carbonFuture.join();

        var expires = now.plus(Duration.ofHours(24));
        saveCache(patchId, "BIODIVERSITY", bio, expires);
        saveCache(patchId, "SOIL", soil, expires);
        saveCache(patchId, "CARBON", carbon, expires);

        return new PatchInsightsDto(bio, soil, carbon);
    }

    private void saveCache(UUID patchId, String layer, Object data, Instant expires) {
        try {
            var cache = new InsightsCache(patchId, layer, mapper.writeValueAsString(data), expires);
            cacheRepository.save(cache);
        } catch (Exception ignored) {}
    }

    private PatchInsightsDto deserialize(InsightsCache bio, InsightsCache soil, InsightsCache carbon) {
        try {
            return new PatchInsightsDto(
                mapper.readValue(bio.getPayload(), BiodiversityData.class),
                mapper.readValue(soil.getPayload(), SoilData.class),
                mapper.readValue(carbon.getPayload(), CarbonData.class)
            );
        } catch (Exception e) {
            throw new RuntimeException("Cache deserialization failed", e);
        }
    }
}
```

- [ ] **Step 4: Run tests — verify they pass**

```bash
cd backend && mvn test -Dtest=InsightsServiceTest -q
```

Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/io/fouracres/service/InsightsService.java \
        backend/src/test/java/io/fouracres/service/InsightsServiceTest.java
git commit -m "feat: InsightsService — parallel fetch with 24h PostgreSQL cache"
```

---

### Task 9: PatchController (REST Endpoints)

**Files:**
- Create: `backend/src/main/java/io/fouracres/controller/PatchController.java`

**Interfaces:**
- Produces:
  - `GET /api/patches` → `200 List<PatchDto>`
  - `GET /api/patches/{id}` → `200 PatchDto` or `404`
  - `GET /api/patches/{id}/insights` → `200 PatchInsightsDto` or `404`

- [ ] **Step 1: Create `PatchController.java`**

```java
package io.fouracres.controller;

import io.fouracres.dto.PatchDto;
import io.fouracres.dto.PatchInsightsDto;
import io.fouracres.repository.PatchRepository;
import io.fouracres.service.InsightsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/api/patches")
public class PatchController {
    private final PatchRepository patchRepository;
    private final InsightsService insightsService;

    public PatchController(PatchRepository patchRepository, InsightsService insightsService) {
        this.patchRepository = patchRepository;
        this.insightsService = insightsService;
    }

    @GetMapping
    public List<PatchDto> listPatches() {
        return patchRepository.findAll().stream().map(PatchDto::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<PatchDto> getPatch(@PathVariable UUID id) {
        return patchRepository.findById(id)
            .map(p -> ResponseEntity.ok(PatchDto.from(p)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/insights")
    public ResponseEntity<PatchInsightsDto> getInsights(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(insightsService.getInsights(id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
```

- [ ] **Step 2: Full backend integration smoke test**

```bash
docker compose up db -d
cd backend && mvn spring-boot:run &
sleep 15

# Test list endpoint
curl -s http://localhost:8080/api/patches | python3 -m json.tool | head -30

# Test insights (replace ID with one from list output)
PATCH_ID=$(curl -s http://localhost:8080/api/patches | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")
curl -s http://localhost:8080/api/patches/$PATCH_ID/insights | python3 -m json.tool
```

Expected: `GET /api/patches` returns 10 patches with GeoJSON boundaries; insights endpoint returns `{ biodiversity: {...}, soil: {...}, carbon: {...} }` (first call may take ~3-5s while APIs respond; subsequent calls return instantly from cache).

- [ ] **Step 3: Run all backend tests**

```bash
cd backend && mvn test -q
```

Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/io/fouracres/controller/PatchController.java
git commit -m "feat: PatchController — GET /api/patches, /{id}, /{id}/insights"
```

---

### Task 10: Frontend Types, API Lib, React Query Setup

**Files:**
- Create: `frontend/app/lib/types.ts`
- Create: `frontend/app/lib/api.ts`
- Create: `frontend/app/lib/queries.ts`
- Create: `frontend/app/providers.tsx`
- Modify: `frontend/app/layout.tsx`

**Interfaces:**
- Produces:
  - `Patch` TypeScript interface
  - `PatchInsights` TypeScript interface
  - `usePatches()` hook → `UseQueryResult<Patch[]>`
  - `useInsights(patchId: string | null)` hook → `UseQueryResult<PatchInsights>`

- [ ] **Step 1: Create `frontend/app/lib/types.ts`**

```typescript
export interface SpeciesEntry {
  name: string
  kingdom: string
}

export interface BiodiversityData {
  speciesCount: number
  topSpecies: SpeciesEntry[]
  threatenedCount: number
}

export interface SoilData {
  organicCarbonGKg: number
  ph: number
  clayPercent: number
}

export interface CarbonData {
  treeCoverPercent: number
  carbonDensityMgHa: number
  coverLossHa: number
}

export interface PatchInsights {
  biodiversity: BiodiversityData
  soil: SoilData
  carbon: CarbonData
}

export interface BoundaryGeoJson {
  type: 'Polygon'
  coordinates: number[][][]
}

export interface Patch {
  id: string
  name: string
  ecosystemType: string
  country: string
  centerLat: number
  centerLng: number
  boundaryGeoJson: BoundaryGeoJson
}
```

- [ ] **Step 2: Create `frontend/app/lib/api.ts`**

```typescript
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
```

- [ ] **Step 3: Create `frontend/app/lib/queries.ts`**

```typescript
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
```

- [ ] **Step 4: Create `frontend/app/providers.tsx`**

```tsx
'use client'

import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'

export function Providers({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(() => new QueryClient())
  return (
    <QueryClientProvider client={queryClient}>
      {children}
    </QueryClientProvider>
  )
}
```

- [ ] **Step 5: Wrap layout in Providers**

Edit `frontend/app/layout.tsx`:
```tsx
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
```

- [ ] **Step 6: Verify TypeScript compiles**

```bash
cd frontend && npx tsc --noEmit
```

Expected: No errors

- [ ] **Step 7: Commit**

```bash
git add frontend/app/lib/ frontend/app/providers.tsx frontend/app/layout.tsx
git commit -m "feat: frontend types, API fetch wrappers, react-query hooks"
```

---

### Task 11: GlobeHero Component (Landing Page)

**Files:**
- Create: `frontend/app/components/GlobeHero.tsx`
- Modify: `frontend/app/page.tsx`

**Interfaces:**
- Consumes: `usePatches()` hook, `Patch[]`
- Produces: Full-screen Mapbox globe with fog, auto-rotation, and patch pin symbols

- [ ] **Step 1: Create `frontend/app/components/GlobeHero.tsx`**

```tsx
'use client'

import Map, { Source, Layer, Marker } from 'react-map-gl'
import { useEffect, useRef } from 'react'
import type { MapRef } from 'react-map-gl'
import type { Patch } from '../lib/types'

interface Props {
  patches: Patch[]
}

export function GlobeHero({ patches }: Props) {
  const mapRef = useRef<MapRef>(null)
  const animRef = useRef<number>(0)

  function startRotation() {
    function rotate() {
      const map = mapRef.current?.getMap()
      if (!map) return
      map.setBearing((map.getBearing() + 0.05) % 360)
      animRef.current = requestAnimationFrame(rotate)
    }
    animRef.current = requestAnimationFrame(rotate)
  }

  useEffect(() => {
    return () => cancelAnimationFrame(animRef.current)
  }, [])

  return (
    <Map
      ref={mapRef}
      mapboxAccessToken={process.env.NEXT_PUBLIC_MAPBOX_TOKEN}
      initialViewState={{ longitude: 0, latitude: 20, zoom: 1.5 }}
      style={{ width: '100%', height: '100vh' }}
      mapStyle="mapbox://styles/mapbox/satellite-v9"
      projection={{ name: 'globe' } as any}
      fog={{
        color: '#0a1628',
        'high-color': '#1a3a6b',
        'horizon-blend': 0.02,
      } as any}
      onLoad={startRotation}
      interactiveLayerIds={[]}
    >
      {patches.map(p => (
        <Marker
          key={p.id}
          longitude={p.centerLng}
          latitude={p.centerLat}
          anchor="center"
        >
          <div
            className="w-3 h-3 rounded-full bg-emerald-400 shadow-lg shadow-emerald-400/50
                       ring-2 ring-emerald-300 ring-offset-1 ring-offset-transparent"
            title={p.name}
          />
        </Marker>
      ))}
    </Map>
  )
}
```

- [ ] **Step 2: Create `frontend/app/page.tsx`**

```tsx
import Link from 'next/link'
import { Suspense } from 'react'

// GlobeHero is client-only (uses Mapbox); wrapped in Suspense
import dynamic from 'next/dynamic'
const GlobeHero = dynamic(
  () => import('./components/GlobeHero').then(m => ({ default: m.GlobeHero })),
  { ssr: false }
)

async function getPatches() {
  try {
    const res = await fetch(
      `${process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080'}/api/patches`,
      { cache: 'no-store' }
    )
    return res.ok ? res.json() : []
  } catch {
    return []
  }
}

export default async function HomePage() {
  const patches = await getPatches()
  return (
    <main className="relative w-full h-screen overflow-hidden">
      <GlobeHero patches={patches} />
      <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
        <h1 className="text-5xl font-bold text-white drop-shadow-lg mb-4">
          4Acres Earth
        </h1>
        <p className="text-lg text-slate-300 mb-8 max-w-md text-center">
          Real environmental intelligence for every 4-acre patch on Earth.
        </p>
        <Link
          href="/explore"
          className="pointer-events-auto bg-emerald-500 hover:bg-emerald-400 text-white
                     font-semibold px-6 py-3 rounded-lg transition-colors shadow-lg"
        >
          Explore Patches
        </Link>
      </div>
    </main>
  )
}
```

- [ ] **Step 3: Run the dev server and verify the globe renders**

```bash
cd frontend && npm run dev
# Open http://localhost:3000
```

Expected: Full-screen rotating satellite globe with emerald pin markers on each patch center, "Explore Patches" CTA overlaid.

- [ ] **Step 4: Commit**

```bash
git add frontend/app/components/GlobeHero.tsx frontend/app/page.tsx
git commit -m "feat: GlobeHero landing page — Mapbox globe with patch pins and auto-rotation"
```

---

### Task 12: PatchMap Component

**Files:**
- Create: `frontend/app/components/PatchMap.tsx`

**Interfaces:**
- Consumes: `patches: Patch[]`, `selectedPatchId: string | null`, `onPatchSelect: (id: string) => void`
- Produces: satellite map that flies to selected patch, renders boundary polygon, fires `onPatchSelect` on polygon click

- [ ] **Step 1: Create `frontend/app/components/PatchMap.tsx`**

```tsx
'use client'

import Map, { Source, Layer } from 'react-map-gl'
import { useEffect, useRef } from 'react'
import type { MapRef } from 'react-map-gl'
import type { Patch } from '../lib/types'
import type { FeatureCollection, Polygon } from 'geojson'

interface Props {
  patches: Patch[]
  selectedPatchId: string | null
  onPatchSelect: (id: string) => void
}

export function PatchMap({ patches, selectedPatchId, onPatchSelect }: Props) {
  const mapRef = useRef<MapRef>(null)

  const geojson: FeatureCollection<Polygon> = {
    type: 'FeatureCollection',
    features: patches.map(p => ({
      type: 'Feature',
      id: p.id,
      properties: { id: p.id, name: p.name, selected: p.id === selectedPatchId },
      geometry: p.boundaryGeoJson as Polygon,
    })),
  }

  useEffect(() => {
    const selected = patches.find(p => p.id === selectedPatchId)
    if (!selected || !mapRef.current) return
    mapRef.current.flyTo({
      center: [selected.centerLng, selected.centerLat],
      zoom: 15,
      duration: 1500,
    })
  }, [selectedPatchId, patches])

  return (
    <Map
      ref={mapRef}
      mapboxAccessToken={process.env.NEXT_PUBLIC_MAPBOX_TOKEN}
      initialViewState={{ longitude: 20, latitude: 10, zoom: 2 }}
      style={{ width: '100%', height: '100%' }}
      mapStyle="mapbox://styles/mapbox/satellite-v9"
      interactiveLayerIds={['patch-fill']}
      onClick={e => {
        const feature = e.features?.[0]
        if (feature?.properties?.id) onPatchSelect(feature.properties.id)
      }}
    >
      <Source id="patches" type="geojson" data={geojson}>
        <Layer
          id="patch-fill"
          type="fill"
          paint={{
            'fill-color': [
              'case',
              ['==', ['get', 'selected'], true], '#10b981',
              '#6ee7b7',
            ],
            'fill-opacity': [
              'case',
              ['==', ['get', 'selected'], true], 0.5,
              0.25,
            ],
          }}
        />
        <Layer
          id="patch-outline"
          type="line"
          paint={{
            'line-color': '#34d399',
            'line-width': 2,
          }}
        />
      </Source>
    </Map>
  )
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/app/components/PatchMap.tsx
git commit -m "feat: PatchMap — satellite basemap with polygon overlay and fly-to"
```

---

### Task 13: PatchSidebar Component

**Files:**
- Create: `frontend/app/components/PatchSidebar.tsx`

**Interfaces:**
- Consumes: `patches: Patch[]`, `selectedPatchId: string | null`, `onSelect: (id: string) => void`
- Produces: sidebar with ecosystem filter tabs and scrollable patch card list

- [ ] **Step 1: Create `frontend/app/components/PatchSidebar.tsx`**

```tsx
'use client'

import { useState } from 'react'
import type { Patch } from '../lib/types'

const ECOSYSTEMS = ['ALL', 'FOREST', 'WETLAND', 'SAVANNA', 'HIGHLAND', 'DRYLAND', 'COASTAL']

const ECOSYSTEM_COLORS: Record<string, string> = {
  FOREST: 'bg-emerald-600',
  WETLAND: 'bg-blue-600',
  SAVANNA: 'bg-amber-600',
  HIGHLAND: 'bg-purple-600',
  DRYLAND: 'bg-orange-600',
  COASTAL: 'bg-cyan-600',
}

interface Props {
  patches: Patch[]
  selectedPatchId: string | null
  onSelect: (id: string) => void
}

export function PatchSidebar({ patches, selectedPatchId, onSelect }: Props) {
  const [filter, setFilter] = useState('ALL')

  const visible = filter === 'ALL'
    ? patches
    : patches.filter(p => p.ecosystemType === filter)

  return (
    <aside className="w-72 flex-shrink-0 bg-slate-900/90 backdrop-blur border-r border-slate-700
                      flex flex-col h-full overflow-hidden">
      <div className="p-4 border-b border-slate-700">
        <h2 className="text-sm font-semibold text-slate-300 uppercase tracking-wider mb-3">
          Ecosystem
        </h2>
        <div className="flex flex-wrap gap-1">
          {ECOSYSTEMS.map(eco => (
            <button
              key={eco}
              onClick={() => setFilter(eco)}
              className={`px-2 py-1 rounded text-xs font-medium transition-colors ${
                filter === eco
                  ? 'bg-emerald-500 text-white'
                  : 'bg-slate-700 text-slate-300 hover:bg-slate-600'
              }`}
            >
              {eco}
            </button>
          ))}
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-3 space-y-2">
        {visible.map(patch => (
          <button
            key={patch.id}
            onClick={() => onSelect(patch.id)}
            className={`w-full text-left p-3 rounded-lg transition-all ${
              patch.id === selectedPatchId
                ? 'bg-emerald-900/60 ring-1 ring-emerald-500'
                : 'bg-slate-800 hover:bg-slate-700'
            }`}
          >
            <div className="flex items-center gap-2 mb-1">
              <span className={`w-2 h-2 rounded-full flex-shrink-0 ${
                ECOSYSTEM_COLORS[patch.ecosystemType] ?? 'bg-slate-500'
              }`} />
              <span className="text-sm font-medium text-white truncate">{patch.name}</span>
            </div>
            <div className="text-xs text-slate-400 ml-4">
              {patch.country} · {patch.ecosystemType}
            </div>
          </button>
        ))}
        {visible.length === 0 && (
          <p className="text-slate-500 text-sm text-center py-8">No patches in this ecosystem</p>
        )}
      </div>
    </aside>
  )
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/app/components/PatchSidebar.tsx
git commit -m "feat: PatchSidebar — ecosystem filter tabs and patch card list"
```

---

### Task 14: InsightsDrawer and Data Cards

**Files:**
- Create: `frontend/app/components/BiodiversityCard.tsx`
- Create: `frontend/app/components/SoilCard.tsx`
- Create: `frontend/app/components/CarbonCard.tsx`
- Create: `frontend/app/components/InsightsDrawer.tsx`

**Interfaces:**
- Consumes: `InsightsDrawer` takes `patchId: string | null`, `patchName: string`
- Produces: slide-in drawer with 3 data cards; skeleton while loading

- [ ] **Step 1: Create `frontend/app/components/BiodiversityCard.tsx`**

```tsx
import type { BiodiversityData } from '../lib/types'

export function BiodiversityCard({ data }: { data: BiodiversityData }) {
  return (
    <div className="bg-slate-800 rounded-xl p-4 space-y-4">
      <h3 className="text-emerald-400 font-semibold flex items-center gap-2">
        <span>🌿</span> Biodiversity
      </h3>
      <div className="grid grid-cols-2 gap-3">
        <Stat label="Species recorded" value={data.speciesCount.toString()} />
        <Stat label="Threatened" value={data.threatenedCount.toString()} highlight={data.threatenedCount > 0} />
      </div>
      {data.topSpecies.length > 0 && (
        <div>
          <p className="text-xs text-slate-400 mb-2 uppercase tracking-wider">Top Species</p>
          <ul className="space-y-1">
            {data.topSpecies.map(s => (
              <li key={s.name} className="text-sm flex justify-between">
                <span className="text-slate-200 italic">{s.name}</span>
                <span className="text-slate-500 text-xs">{s.kingdom}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

function Stat({ label, value, highlight }: { label: string; value: string; highlight?: boolean }) {
  return (
    <div className="bg-slate-700/50 rounded-lg p-3">
      <p className="text-xs text-slate-400 mb-1">{label}</p>
      <p className={`text-2xl font-bold ${highlight ? 'text-red-400' : 'text-white'}`}>{value}</p>
    </div>
  )
}
```

- [ ] **Step 2: Create `frontend/app/components/SoilCard.tsx`**

```tsx
import type { SoilData } from '../lib/types'

export function SoilCard({ data }: { data: SoilData }) {
  return (
    <div className="bg-slate-800 rounded-xl p-4 space-y-4">
      <h3 className="text-amber-400 font-semibold flex items-center gap-2">
        <span>🌱</span> Soil Health
      </h3>
      <div className="grid grid-cols-3 gap-3">
        <Stat label="Organic carbon" value={data.organicCarbonGKg.toFixed(1)} unit="g/kg" />
        <Stat label="pH" value={data.ph.toFixed(1)} unit="" />
        <Stat label="Clay" value={data.clayPercent.toFixed(1)} unit="%" />
      </div>
    </div>
  )
}

function Stat({ label, value, unit }: { label: string; value: string; unit: string }) {
  return (
    <div className="bg-slate-700/50 rounded-lg p-3">
      <p className="text-xs text-slate-400 mb-1">{label}</p>
      <p className="text-xl font-bold text-white">
        {value}
        {unit && <span className="text-sm font-normal text-slate-400 ml-1">{unit}</span>}
      </p>
    </div>
  )
}
```

- [ ] **Step 3: Create `frontend/app/components/CarbonCard.tsx`**

```tsx
import type { CarbonData } from '../lib/types'

export function CarbonCard({ data }: { data: CarbonData }) {
  return (
    <div className="bg-slate-800 rounded-xl p-4 space-y-4">
      <h3 className="text-blue-400 font-semibold flex items-center gap-2">
        <span>🌳</span> Carbon / Forest
      </h3>
      <div className="grid grid-cols-3 gap-3">
        <Stat label="Tree cover" value={data.treeCoverPercent.toFixed(1)} unit="%" />
        <Stat label="Carbon density" value={data.carbonDensityMgHa.toFixed(1)} unit="Mg/ha" />
        <Stat label="Cover loss" value={data.coverLossHa.toFixed(2)} unit="ha" highlight={data.coverLossHa > 0.1} />
      </div>
    </div>
  )
}

function Stat({ label, value, unit, highlight }: { label: string; value: string; unit: string; highlight?: boolean }) {
  return (
    <div className="bg-slate-700/50 rounded-lg p-3">
      <p className="text-xs text-slate-400 mb-1">{label}</p>
      <p className={`text-xl font-bold ${highlight ? 'text-red-400' : 'text-white'}`}>
        {value}
        {unit && <span className="text-sm font-normal text-slate-400 ml-1">{unit}</span>}
      </p>
    </div>
  )
}
```

- [ ] **Step 4: Create `frontend/app/components/InsightsDrawer.tsx`**

```tsx
'use client'

import { useInsights } from '../lib/queries'
import { BiodiversityCard } from './BiodiversityCard'
import { SoilCard } from './SoilCard'
import { CarbonCard } from './CarbonCard'

interface Props {
  patchId: string | null
  patchName: string
}

export function InsightsDrawer({ patchId, patchName }: Props) {
  const { data, isLoading, isError } = useInsights(patchId)

  if (!patchId) {
    return (
      <div className="w-96 flex-shrink-0 bg-slate-900/90 backdrop-blur border-l border-slate-700
                      flex items-center justify-center text-slate-500 text-sm p-6 text-center">
        Select a patch to view environmental insights
      </div>
    )
  }

  return (
    <aside className="w-96 flex-shrink-0 bg-slate-900/90 backdrop-blur border-l border-slate-700
                      overflow-y-auto">
      <div className="p-4 border-b border-slate-700">
        <h2 className="text-white font-semibold text-lg">{patchName}</h2>
        <p className="text-slate-400 text-xs mt-1">4 acres · Environmental insights</p>
      </div>

      <div className="p-4 space-y-4">
        {isLoading && (
          <>
            <SkeletonCard />
            <SkeletonCard />
            <SkeletonCard />
          </>
        )}
        {isError && (
          <div className="text-red-400 text-sm bg-red-900/20 rounded-lg p-4">
            Failed to load insights. The external APIs may be unavailable.
          </div>
        )}
        {data && (
          <>
            <BiodiversityCard data={data.biodiversity} />
            <SoilCard data={data.soil} />
            <CarbonCard data={data.carbon} />
          </>
        )}
      </div>
    </aside>
  )
}

function SkeletonCard() {
  return (
    <div className="bg-slate-800 rounded-xl p-4 space-y-3 animate-pulse">
      <div className="h-4 bg-slate-700 rounded w-32" />
      <div className="grid grid-cols-3 gap-3">
        {[0, 1, 2].map(i => (
          <div key={i} className="bg-slate-700 rounded-lg h-16" />
        ))}
      </div>
    </div>
  )
}
```

- [ ] **Step 5: Commit**

```bash
git add frontend/app/components/BiodiversityCard.tsx \
        frontend/app/components/SoilCard.tsx \
        frontend/app/components/CarbonCard.tsx \
        frontend/app/components/InsightsDrawer.tsx
git commit -m "feat: InsightsDrawer + BiodiversityCard + SoilCard + CarbonCard"
```

---

### Task 15: Wire Up /explore Page

**Files:**
- Create: `frontend/app/explore/page.tsx`

**Interfaces:**
- Consumes: `usePatches()`, `PatchMap`, `PatchSidebar`, `InsightsDrawer`
- Produces: full `/explore` experience — sidebar + map + insights drawer with shared `selectedPatchId` state

- [ ] **Step 1: Create `frontend/app/explore/page.tsx`**

```tsx
'use client'

import { useState } from 'react'
import dynamic from 'next/dynamic'
import { usePatches } from '../lib/queries'
import { PatchSidebar } from '../components/PatchSidebar'
import { InsightsDrawer } from '../components/InsightsDrawer'

const PatchMap = dynamic(
  () => import('../components/PatchMap').then(m => ({ default: m.PatchMap })),
  { ssr: false }
)

export default function ExplorePage() {
  const [selectedPatchId, setSelectedPatchId] = useState<string | null>(null)
  const { data: patches = [], isLoading } = usePatches()

  const selectedPatch = patches.find(p => p.id === selectedPatchId)

  if (isLoading) {
    return (
      <div className="h-screen flex items-center justify-center bg-[#0a1628]">
        <div className="text-slate-400 animate-pulse">Loading patches...</div>
      </div>
    )
  }

  return (
    <div className="h-screen flex overflow-hidden">
      <PatchSidebar
        patches={patches}
        selectedPatchId={selectedPatchId}
        onSelect={setSelectedPatchId}
      />
      <div className="flex-1 relative">
        <PatchMap
          patches={patches}
          selectedPatchId={selectedPatchId}
          onPatchSelect={setSelectedPatchId}
        />
      </div>
      <InsightsDrawer
        patchId={selectedPatchId}
        patchName={selectedPatch?.name ?? ''}
      />
    </div>
  )
}
```

- [ ] **Step 2: Verify full explore page in browser**

```bash
cd frontend && npm run dev
# Open http://localhost:3000/explore
# (backend must be running: cd backend && mvn spring-boot:run)
```

Verify manually:
- 10 patch cards in sidebar; ecosystem filter buttons work
- Click a patch → map flies to that location, polygon highlights in green
- InsightsDrawer shows skeleton, then loads biodiversity / soil / carbon cards
- Click another patch → map flies, drawer updates
- Second click on same patch → instant load from react-query cache

- [ ] **Step 3: TypeScript check**

```bash
cd frontend && npx tsc --noEmit
```

Expected: No errors

- [ ] **Step 4: Commit**

```bash
git add frontend/app/explore/
git commit -m "feat: /explore page — sidebar + satellite map + insights drawer wired up"
```

---

### Task 16: Docker Compose Full Build & End-to-End Smoke Test

**Files:**
- No new files — validates everything works together

- [ ] **Step 1: Copy and fill `.env`**

```bash
cp .env.example .env
# Edit .env — set MAPBOX_TOKEN and GFW_API_KEY
```

- [ ] **Step 2: Full build and start**

```bash
docker compose up --build
```

Expected: All three services start. Watch for:
- `db` → `database system is ready to accept connections`
- `api` → `Started FourAcresApplication` (after Flyway runs migrations)
- `frontend` → listening on port 3000

- [ ] **Step 3: Verify API**

```bash
curl -s http://localhost:8080/api/patches | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(f'{len(data)} patches returned')
print('First patch:', data[0]['name'], '| has boundary:', 'coordinates' in str(data[0]['boundaryGeoJson']))
"
```

Expected: `10 patches returned` with valid GeoJSON boundaries.

- [ ] **Step 4: Verify insights endpoint**

```bash
PATCH_ID=$(curl -s http://localhost:8080/api/patches | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")
curl -s http://localhost:8080/api/patches/$PATCH_ID/insights | python3 -m json.tool
```

Expected: JSON with `biodiversity`, `soil`, `carbon` keys, all with non-null numeric values.

- [ ] **Step 5: Verify frontend**

Open http://localhost:3000 in a browser:
- Globe rotates on landing page, emerald pins visible
- Click "Explore Patches" → navigate to `/explore`
- Sidebar shows 10 patches
- Click any patch → map flies, insights load

- [ ] **Step 6: Final commit**

```bash
git add .
git commit -m "feat: complete geospatial POC — globe hero, explore map, GBIF/SoilGrids/GFW insights"
```

---

## Self-Review Against Spec

**Spec section coverage:**

| Spec section | Covered by task(s) |
|---|---|
| Tech stack (§2) | Task 1 (all versions pinned in pom.xml + package.json) |
| System architecture — 3 REST endpoints | Task 9 |
| Parallel API calls via CompletableFuture.allOf() | Task 8 |
| 24h DB cache in PostgreSQL JSONB | Task 8 |
| PostGIS polygon storage | Task 2 |
| Data model — patches + insights_cache tables | Task 2 |
| GBIF — species_count, top_species, threatened_count | Task 5 |
| SoilGrids — soc÷10, phh2o÷10, clay÷10 | Task 6 |
| GFW — tree_cover_percent, carbon_density, cover_loss | Task 7 |
| GFW geostore registration at seed time | Task 3 |
| InsightsService shape (§6) | Task 8 |
| Frontend file structure (§7) | Tasks 10–15 |
| Globe hero — projection:globe, fog, auto-rotation, pins | Task 11 |
| PatchMap — satellite, polygon overlay, fly-to | Task 12 |
| PatchSidebar — ecosystem filter + PatchCard list | Task 13 |
| InsightsDrawer — 3-panel with skeleton | Task 14 |
| Data flow on /explore (§7) | Task 15 |
| 10 pre-seeded patches with exact coordinates | Task 2 |
| Docker Compose with correct images/env | Task 1 |
| .env.example with MAPBOX_TOKEN + GFW_API_KEY | Task 1 |
| CORS allow localhost:3000 | Task 4 |

**No placeholders found.**

**Type consistency verified:** `BiodiversityData`, `SoilData`, `CarbonData` records defined in Task 4 (Java) and Task 10 (TypeScript), referenced by name throughout. `PatchDto.from(Patch)` static factory defined in Task 4, used in Task 9. `GbifClient.fetch(Patch)` → `BiodiversityData` defined in Task 5, consumed in Task 8. All consistent.
